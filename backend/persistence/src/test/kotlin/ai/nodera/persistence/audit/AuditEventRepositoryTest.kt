package ai.nodera.persistence.audit

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.audit.AuditEvent
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.audit.DENIED_CAPABILITY_KEY
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.seedProject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID
import kotlin.uuid.toKotlinUuid

private const val SELECT_EVENT =
    "select project_id, actor_id, actor_kind::text as actor_kind, on_behalf_of_actor_id, " +
        "surface::text as surface, tool_name, action, entity_type, entity_id, " +
        "before->>'status' as before_status, after->>'status' as after_status, " +
        "after->>'" + DENIED_CAPABILITY_KEY + "' as denied_capability, outcome, " +
        "(before is null) as before_absent " +
        "from audit_event where request_id = ?"

private fun rowOf(
    requestId: UUID,
    projectIds: List<UUID>,
): Map<String, Any?> =
    SchemaFixture.asApp(projectIds) { connection ->
        connection.prepareStatement(SELECT_EVENT).use { statement ->
            statement.setObject(1, requestId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use emptyMap<String, Any?>()
                val columns = rows.metaData
                (1..columns.columnCount).associate { columns.getColumnLabel(it) to rows.getObject(it) }
            }
        }
    }

/** What the trail actually holds after a use case wrote to it, column by column. */
class AuditEventRepositoryTest :
    StringSpec({

        val seeded = SchemaFixture.asOwner { it.seedProject() }
        val scope = listOf(seeded.projectId)
        val unitOfWork = auditedUnitOfWork(scope)
        val recorder = AuditRecorder(AuditEventRepository())

        val closing =
            AuditEntry(
                action = AuditAction("ticket.closed"),
                entityType = "ticket",
                entityId = seeded.ticketId.toKotlinUuid(),
                projectId = ProjectId(seeded.projectId.toKotlinUuid()),
                diff = AuditDiff(mapOf("status" to "in_review"), mapOf("status" to "done")),
                toolName = "ticket_transition",
            )

        "the row carries every field of the acting context and of the entry" {
            val request = UUID.randomUUID()

            unitOfWork.inTransaction {
                recorder.record(
                    context(seeded.agentActorId, request, onBehalfOf = seeded.humanActorId),
                    closing,
                )
            }

            val row = rowOf(request, scope)
            row["project_id"] shouldBe seeded.projectId
            row["actor_id"] shouldBe seeded.agentActorId
            row["actor_kind"] shouldBe "agent"
            row["on_behalf_of_actor_id"] shouldBe seeded.humanActorId
            row["surface"] shouldBe "mcp"
            row["tool_name"] shouldBe "ticket_transition"
            row["action"] shouldBe "ticket.closed"
            row["entity_type"] shouldBe "ticket"
            row["entity_id"] shouldBe seeded.ticketId
            row["before_status"] shouldBe "in_review"
            row["after_status"] shouldBe "done"
            row["outcome"] shouldBe "success"
        }

        // The paired negative for the delegation column: it is written from the context, so a
        // context without a delegation must leave it empty rather than borrow an actor from
        // anywhere else.
        "every optional column is null when neither the context nor the entry carries one" {
            val request = UUID.randomUUID()

            unitOfWork.inTransaction {
                recorder.record(
                    context(seeded.agentActorId, request),
                    AuditEntry(action = AuditAction("project.created"), entityType = "project"),
                )
            }

            val row = rowOf(request, scope)
            row["project_id"] shouldBe null
            row["on_behalf_of_actor_id"] shouldBe null
            row["entity_id"] shouldBe null
            row["tool_name"] shouldBe null
            row["before_absent"] shouldBe true
        }

        "a denied operation is recorded as denied, naming the verb the actor lacked" {
            val request = UUID.randomUUID()

            unitOfWork.inTransaction {
                recorder.recordDenied(
                    context(seeded.agentActorId, request, onBehalfOf = seeded.humanActorId),
                    closing,
                    Capability.TICKET_CLOSE,
                )
            }

            val row = rowOf(request, scope)
            row["outcome"] shouldBe "denied"
            row["denied_capability"] shouldBe Capability.TICKET_CLOSE.verb
            row["on_behalf_of_actor_id"] shouldBe seeded.humanActorId
            // Nothing changed, so `after` states no field of the entity — only why it was refused.
            row["after_status"] shouldBe null
            row["before_status"] shouldBe "in_review"
        }

        // The adapter spells the enum labels by lowercasing the Kotlin names. That coupling is
        // proved rather than assumed: an enum that gains a value the column refuses fails here.
        "every Surface, ActorKind and outcome value is a label the schema accepts" {
            val request = UUID.randomUUID()
            val actors = mapOf(ActorKind.HUMAN to seeded.humanActorId, ActorKind.AGENT to seeded.agentActorId)
            val combinations =
                Surface.entries.flatMap { surface ->
                    ActorKind.entries.flatMap { kind ->
                        AuditOutcome.entries.map { outcome -> Triple(surface, kind, outcome) }
                    }
                }

            unitOfWork.inTransaction {
                combinations.forEach { (surface, kind, outcome) ->
                    recorder.record(
                        context(requireNotNull(actors[kind]), request, kind = kind, surface = surface),
                        closing.copy(outcome = outcome),
                    )
                }
            }

            auditRows(request, scope) shouldBe combinations.size.toLong()
        }

        // The guard behind "in the mutation's transaction": the sink has no way to open one, so a
        // caller outside a unit of work is refused rather than quietly given a fresh connection.
        "the sink refuses to append when no transaction is open" {
            val event = AuditEvent(context(seeded.agentActorId, UUID.randomUUID()), closing)

            val failure = shouldThrow<IllegalStateException> { AuditEventRepository().append(event) }

            failure.message.orEmpty() shouldContain "mutation's own transaction"
        }
    })
