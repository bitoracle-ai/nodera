package ai.nodera.persistence.ticket

import ai.nodera.application.ticket.CreateTicketResult
import ai.nodera.application.ticket.TransitionResult
import ai.nodera.domain.ticket.ReviewRequirement
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketResolution
import ai.nodera.domain.ticket.TicketStatus
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.audit.context
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import kotlin.uuid.toJavaUuid

private const val BLOCKING = "blocking"
private const val NON_BLOCKING = "non_blocking"

private suspend fun TicketUseCases.inReview(project: TicketProject): Ticket {
    val ctx = context(project.actorId, UUID.randomUUID())
    val created =
        create
            .create(ctx, project.id, draft(project.prefix))
            .shouldBeInstanceOf<CreateTicketResult.Created>()
            .ticket
    transition.transition(ctx, project.id, created.key, TicketStatus.IN_PROGRESS, null)
    transition.transition(ctx, project.id, created.key, TicketStatus.IN_REVIEW, null)
    return created
}

private suspend fun TicketUseCases.closeAsDone(
    project: TicketProject,
    ticket: Ticket,
    requestId: UUID = UUID.randomUUID(),
): TransitionResult =
    transition.transition(
        context(project.actorId, requestId),
        project.id,
        ticket.key,
        TicketStatus.CLOSED,
        TicketResolution.DONE,
    )

class TicketClosureTest :
    StringSpec({

        "closure as done is refused while an acceptance criterion is unmet" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket = useCases.inReview(project)
            SchemaFixture.asOwner {
                it.seedReview(ticket.id.value.toJavaUuid(), project.reviewerId)
                it.seedCriterion(ticket.id.value.toJavaUuid(), 1, "the only criterion", met = false)
            }

            val unmet = useCases.closeAsDone(project, ticket).shouldBeInstanceOf<TransitionResult.GateFailed>().unmet

            unmet.acceptanceCriteria.map { it.text } shouldContainExactly listOf("the only criterion")
            unmet.unresolvedBlockingFindings.shouldBeEmpty()
            unmet.reviews shouldBe ReviewRequirement.PRESENT
            SchemaFixture.asOwner { it.statusOf(ticket) } shouldBe "in_review"
        }

        "closure as done is refused while a blocking finding is unresolved" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket = useCases.inReview(project)
            SchemaFixture.asOwner {
                val review = it.seedReview(ticket.id.value.toJavaUuid(), project.reviewerId)
                it.seedFinding(review, "a blocking finding", BLOCKING, resolved = false)
                it.seedFinding(review, "a non-blocking one", NON_BLOCKING, resolved = false)
                it.seedFinding(review, "a resolved blocking one", BLOCKING, resolved = true, project.actorId)
            }

            val unmet = useCases.closeAsDone(project, ticket).shouldBeInstanceOf<TransitionResult.GateFailed>().unmet

            unmet.unresolvedBlockingFindings.map { it.title } shouldContainExactly listOf("a blocking finding")
            unmet.acceptanceCriteria.shouldBeEmpty()
            unmet.reviews shouldBe ReviewRequirement.PRESENT
        }

        "closure as done is refused when no review exists" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket = useCases.inReview(project)
            SchemaFixture.asOwner {
                it.seedCriterion(ticket.id.value.toJavaUuid(), 1, "met", met = true, metBy = project.actorId)
            }

            val unmet = useCases.closeAsDone(project, ticket).shouldBeInstanceOf<TransitionResult.GateFailed>().unmet

            unmet.reviews shouldBe ReviewRequirement.ABSENT
            unmet.acceptanceCriteria.shouldBeEmpty()
        }

        // The criterion this package turns on: a refusal names everything missing, so an agent can
        // finish the work rather than retry and guess.
        "a refusal names every missing item, not just the first" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket = useCases.inReview(project)
            val ticketId = ticket.id.value.toJavaUuid()
            SchemaFixture.asOwner {
                it.seedCriterion(ticketId, 1, "first criterion", met = false)
                it.seedCriterion(ticketId, 2, "second criterion", met = true, metBy = project.actorId)
                it.seedCriterion(ticketId, 3, "third criterion", met = false)
            }

            val unmet = useCases.closeAsDone(project, ticket).shouldBeInstanceOf<TransitionResult.GateFailed>().unmet

            unmet.acceptanceCriteria.map { it.ordinal } shouldContainExactly listOf(1, 3)
            unmet.reviews shouldBe ReviewRequirement.ABSENT
        }

        "a reviewed ticket with every criterion met and no unresolved blocking finding closes" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket = useCases.inReview(project)
            val ticketId = ticket.id.value.toJavaUuid()
            SchemaFixture.asOwner {
                val review = it.seedReview(ticketId, project.reviewerId)
                it.seedFinding(review, "fixed", BLOCKING, resolved = true, project.actorId)
                it.seedCriterion(ticketId, 1, "met", met = true, metBy = project.actorId)
            }

            val closed = useCases.closeAsDone(project, ticket).shouldBeInstanceOf<TransitionResult.Transitioned>()

            closed.ticket.state.status shouldBe TicketStatus.CLOSED
            closed.ticket.state.resolution shouldBe TicketResolution.DONE
            SchemaFixture.asOwner { it.statusOf(ticket) } shouldBe "closed"
            SchemaFixture.asOwner { it.closedAt(ticket) }.shouldNotBeNull()
        }

        "a gate refusal is recorded as a failed attempt and changes nothing" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket = useCases.inReview(project)
            val request = UUID.randomUUID()

            useCases.closeAsDone(project, ticket, request).shouldBeInstanceOf<TransitionResult.GateFailed>()

            SchemaFixture.asOwner { it.auditOutcomes(request) } shouldContainExactly listOf("failed")
            SchemaFixture.asOwner { it.statusOf(ticket) } shouldBe "in_review"
        }

        "transitioning a ticket that does not exist is not found" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)

            useCases.transition.transition(
                context(project.actorId, UUID.randomUUID()),
                project.id,
                ticketKey(project.prefix, 99),
                TicketStatus.IN_PROGRESS,
                null,
            ) shouldBe TransitionResult.NotFound
        }

        // The failure this package is shaped to avoid: an unscoped read returns zero rows, and zero
        // unmet requirements is what "satisfied" looks like. The ticket is unreadable long before
        // the gate sees anything, and the attempt fails loudly rather than closing the ticket.
        "an unscoped caller cannot close a ticket whose gate would refuse it" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket = useCases.inReview(project)
            SchemaFixture.asOwner {
                it.seedCriterion(ticket.id.value.toJavaUuid(), 1, "never met", met = false)
            }
            val unscoped = TicketUseCases(emptyList(), project.actor)

            shouldThrowAny { unscoped.closeAsDone(project, ticket) }
            SchemaFixture.asOwner { it.statusOf(ticket) } shouldBe "in_review"
        }

        "closure facts are absent, not empty, for a ticket the reader cannot see" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ticket = useCases.inReview(project)
            val reader = JdbcClosureFacts()

            TicketUseCases(project.scope, project.actor)
                .unitOfWork
                .inTransaction {
                    reader.facts(ticket.id)
                }.shouldNotBeNull()

            TicketUseCases(emptyList(), project.actor)
                .unitOfWork
                .inTransaction {
                    reader.facts(ticket.id)
                }.shouldBeNull()
        }
    })
