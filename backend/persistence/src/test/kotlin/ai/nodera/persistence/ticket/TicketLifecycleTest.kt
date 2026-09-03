package ai.nodera.persistence.ticket

import ai.nodera.application.ticket.CreateTicketResult
import ai.nodera.application.ticket.NextTicketResult
import ai.nodera.application.ticket.TransitionResult
import ai.nodera.domain.collaboration.ReviewVerdict
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.ticket.FindingSeverity
import ai.nodera.domain.ticket.SelectionReason
import ai.nodera.domain.ticket.TicketPriority
import ai.nodera.domain.ticket.TicketResolution
import ai.nodera.domain.ticket.TicketState
import ai.nodera.domain.ticket.TicketStatus
import ai.nodera.domain.ticket.TransitionRefusal
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.audit.context
import ai.nodera.persistence.label
import ai.nodera.persistence.runSql
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import kotlin.uuid.toJavaUuid

// The Kotlin enum and the Postgres type have to agree on the labels AND on their order: the working
// order sorts by TicketPriority.ordinal, so a type whose labels run p4..p1 would invert it silently.
private val ENUM_TYPES =
    listOf(
        "ticket_status" to TicketStatus.entries.map { it.label },
        "ticket_resolution" to TicketResolution.entries.map { it.label },
        "ticket_priority" to TicketPriority.entries.map { it.label },
        // The gate parses this one too, and a label it does not know aborts a closure rather than
        // refusing it.
        "finding_severity" to FindingSeverity.entries.map { it.label },
        // CORE-04 writes this one, casting the label straight into the column.
        "review_verdict" to ReviewVerdict.entries.map { it.label },
    )

private const val CONTENDERS = 4

private const val ENUM_LABELS =
    "select e.enumlabel from pg_enum e join pg_type t on t.oid = e.enumtypid " +
        "where t.typname = ? order by e.enumsortorder"

private fun Connection.enumLabels(typeName: String): List<String> =
    prepareStatement(ENUM_LABELS).use { statement ->
        statement.setString(1, typeName)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }

class TicketLifecycleTest :
    StringSpec({

        ENUM_TYPES.forEach { (typeName, labels) ->
            "the $typeName column accepts exactly the values the domain has, in the same order" {
                SchemaFixture.asOwner { it.enumLabels(typeName) } shouldContainExactly labels
            }
        }

        "a create writes the ticket and exactly one audit event" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val request = UUID.randomUUID()

            val created =
                useCases.create
                    .create(context(project.actorId, request), project.id, draft(project.prefix, "the first one"))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()

            created.ticket.key.rendered shouldBe "${project.prefix.value}-1"
            created.ticket.state.status shouldBe TicketStatus.OPEN
            created.ticket.state.resolution shouldBe null
            SchemaFixture.asOwner { it.ticketKeys(project.projectId) } shouldContainExactly
                listOf(created.ticket.key.rendered)
            SchemaFixture.asOwner { it.auditOutcomes(request) } shouldContainExactly listOf("success")
        }

        // An observer holds ticket.read and nothing that writes, so this is the denial path with no
        // special case for what kind of actor is asking (invariant #1).
        "a create the caller may not make is denied, and leaves only the refusal behind" {
            val project = seedTicketProject()
            val observer = TicketUseCases(project.scope, project.actor, role = ProjectRole.OBSERVER)
            val request = UUID.randomUUID()

            observer.create.create(
                context(project.actorId, request),
                project.id,
                draft(project.prefix),
            ) shouldBe CreateTicketResult.Denied(Capability.TICKET_CREATE)

            SchemaFixture.asOwner { it.ticketKeys(project.projectId) }.shouldBeEmpty()
            SchemaFixture.asOwner { it.auditOutcomes(request) } shouldContainExactly listOf("denied")
            SchemaFixture.asOwner { it.nextNumber(project.projectId, project.prefix) } shouldBe null
        }

        // A contributor may move a ticket but not close one — docs/MCP.md § 3.3.
        "closing without ticket.close is denied even though the transition itself is permitted" {
            val project = seedTicketProject()
            val owner = TicketUseCases(project.scope, project.actor)
            val ticket =
                owner.create
                    .create(context(project.actorId, UUID.randomUUID()), project.id, draft(project.prefix))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()
                    .ticket
            val contributor = TicketUseCases(project.scope, project.actor, role = ProjectRole.CONTRIBUTOR)
            val ctx = context(project.actorId, UUID.randomUUID())

            contributor.transition
                .transition(
                    ctx,
                    project.id,
                    ticket.key,
                    TicketStatus.IN_PROGRESS,
                    null,
                ).shouldBeInstanceOf<TransitionResult.Transitioned>()

            contributor.transition.transition(
                context(project.actorId, UUID.randomUUID()),
                project.id,
                ticket.key,
                TicketStatus.CLOSED,
                TicketResolution.WONT_DO,
            ) shouldBe TransitionResult.Denied(Capability.TICKET_CLOSE)
        }

        "a transition the machine does not specify is refused, and the ticket does not move" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val request = UUID.randomUUID()
            val ticket =
                useCases.create
                    .create(context(project.actorId, UUID.randomUUID()), project.id, draft(project.prefix))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()
                    .ticket

            val refused =
                useCases.transition
                    .transition(
                        context(project.actorId, request),
                        project.id,
                        ticket.key,
                        TicketStatus.CLOSED,
                        TicketResolution.DUPLICATE,
                    ).shouldBeInstanceOf<TransitionResult.Refused>()

            refused.refusal.shouldBeInstanceOf<TransitionRefusal.UnknownEdge>()
            SchemaFixture.asOwner { it.statusOf(ticket) } shouldBe "open"
            SchemaFixture.asOwner { it.auditOutcomes(request) } shouldContainExactly listOf("failed")
            // V4 indexes the trail by (entity_type, entity_id): a refusal about a ticket that was
            // read has to be reachable from that ticket, or its history hides the refused attempts.
            SchemaFixture.asOwner { it.auditEntities(request) } shouldContainExactly
                listOf(ticket.id.value.toJavaUuid())
        }

        // Deterministic half of the lost-update guard: the row has already moved, so a write that
        // was decided against `open` must match nothing. Without the `and status = ?` clause it
        // would overwrite `in_progress` with a status the machine never allows from it.
        "a transition write matches nothing once the ticket has left the status it was decided against" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ctx = context(project.actorId, UUID.randomUUID())
            val ticket =
                useCases.create
                    .create(ctx, project.id, draft(project.prefix))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()
                    .ticket
            useCases.transition.transition(ctx, project.id, ticket.key, TicketStatus.IN_PROGRESS, null)

            val stale =
                useCases.unitOfWork.inTransaction {
                    val outcome =
                        JdbcTicketRepository().applyTransition(
                            ticket.id,
                            TicketStatus.OPEN,
                            TicketState(TicketStatus.IN_REVIEW),
                        )
                    // The statement still reaches the database, so invariant #3 wants its event.
                    useCases.recorder.record(
                        context(project.actorId, UUID.randomUUID()),
                        sequenceEntry(project.projectId),
                    )
                    outcome
                }

            stale shouldBe null
            SchemaFixture.asOwner { it.statusOf(ticket) } shouldBe "in_progress"
        }

        // The same guard under real concurrency: whichever of the four wins, the other three are
        // refused rather than silently overwriting it.
        "concurrent transitions of one ticket leave exactly one winner" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket =
                useCases.create
                    .create(context(project.actorId, UUID.randomUUID()), project.id, draft(project.prefix))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()
                    .ticket
            val gate = CyclicBarrier(CONTENDERS)

            val outcomes =
                coroutineScope {
                    (1..CONTENDERS)
                        .map {
                            async(Dispatchers.IO) {
                                gate.await()
                                useCases.transition.transition(
                                    context(project.actorId, UUID.randomUUID()),
                                    project.id,
                                    ticket.key,
                                    TicketStatus.IN_PROGRESS,
                                    null,
                                )
                            }
                        }.awaitAll()
                }

            outcomes.count { it is TransitionResult.Transitioned } shouldBe 1
            outcomes.count { it is TransitionResult.Refused } shouldBe CONTENDERS - 1
            SchemaFixture.asOwner { it.statusOf(ticket) } shouldBe "in_progress"
        }

        "the working order skips a ticket whose dependency is still open" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ctx = context(project.actorId, UUID.randomUUID())

            val blocker =
                useCases.create
                    .create(ctx, project.id, draft(project.prefix, "the blocker", TicketPriority.P4))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()
                    .ticket
            val dependent =
                useCases.create
                    .create(ctx, project.id, draft(project.prefix, "the dependent", TicketPriority.P1))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()
                    .ticket
            SchemaFixture.asOwner {
                it.runSql(
                    "insert into ticket_dependency (ticket_id, depends_on_ticket_id) values (?, ?)",
                    dependent.id.value.toJavaUuid(),
                    blocker.id.value.toJavaUuid(),
                )
            }

            // The dependent is P1 and would come first, so only the edge can be holding it back.
            val selected =
                useCases.next
                    .next(ctx, project.id)
                    .shouldBeInstanceOf<NextTicketResult.Selected>()

            selected.ready.candidate.id shouldBe blocker.id
            selected.ready.reason shouldBe SelectionReason.NEXT_BY_PRIORITY
        }

        "a dependency closed as wont_do releases the ticket that waited on it" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ctx = context(project.actorId, UUID.randomUUID())

            val blocker =
                useCases.create
                    .create(ctx, project.id, draft(project.prefix, "abandoned"))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()
                    .ticket
            val dependent =
                useCases.create
                    .create(ctx, project.id, draft(project.prefix, "waiting"))
                    .shouldBeInstanceOf<CreateTicketResult.Created>()
                    .ticket
            SchemaFixture.asOwner {
                it.runSql(
                    "insert into ticket_dependency (ticket_id, depends_on_ticket_id) values (?, ?)",
                    dependent.id.value.toJavaUuid(),
                    blocker.id.value.toJavaUuid(),
                )
            }

            listOf(TicketStatus.IN_PROGRESS, TicketStatus.IN_REVIEW).forEach {
                useCases.transition.transition(
                    context(project.actorId, UUID.randomUUID()),
                    project.id,
                    blocker.key,
                    it,
                    null,
                )
            }
            useCases.transition.transition(
                context(project.actorId, UUID.randomUUID()),
                project.id,
                blocker.key,
                TicketStatus.CLOSED,
                TicketResolution.WONT_DO,
            )

            useCases.next
                .next(ctx, project.id)
                .shouldBeInstanceOf<NextTicketResult.Selected>()
                .ready.candidate.id shouldBe dependent.id
        }

        "an observer may still be told what to start next" {
            val project = seedTicketProject()
            val owner = TicketUseCases(project.scope, project.actor)
            val ctx = context(project.actorId, UUID.randomUUID())
            owner.create.create(ctx, project.id, draft(project.prefix))

            TicketUseCases(project.scope, project.actor, role = ProjectRole.OBSERVER)
                .next
                .next(ctx, project.id)
                .shouldBeInstanceOf<NextTicketResult.Selected>()
        }

        "a project with nothing startable offers nothing" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)

            useCases.next.next(context(project.actorId, UUID.randomUUID()), project.id) shouldBe
                NextTicketResult.NothingReady
        }
    })
