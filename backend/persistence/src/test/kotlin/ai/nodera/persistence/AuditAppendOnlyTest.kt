package ai.nodera.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCaseOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.SQLException
import java.util.UUID

private const val INSUFFICIENT_PRIVILEGE = "42501"
private const val COUNT_BY_REQUEST = "select count(*) from audit_event where request_id = ?"

private const val APP_AUDIT_ROW =
    "insert into audit_event " +
        "(project_id, actor_id, actor_kind, surface, action, entity_type, entity_id, request_id) " +
        "values (?, ?, 'agent', 'mcp', 'ticket.closed', 'ticket', ?, ?)"

private data class RowMutation(
    val name: String,
    val grantPrivilege: String,
    val disableTrigger: String,
    val sql: String,
)

private val ROW_MUTATIONS =
    listOf(
        RowMutation(
            "update",
            "grant update on audit_event to nodera_app",
            "alter table audit_event disable trigger audit_event_no_update",
            "update audit_event set outcome = 'failed' where request_id = ?",
        ),
        RowMutation(
            "delete",
            "grant delete on audit_event to nodera_app",
            "alter table audit_event disable trigger audit_event_no_delete",
            "delete from audit_event where request_id = ?",
        ),
    )

/**
 * Invariant #3 — the audit trail is append-only, at the database level.
 *
 * Two layers guard it and the privilege refuses first, so a test that stops at the first refusal
 * proves the grant and leaves the triggers as untested as they were. Each mutation is therefore run
 * three times: refused by the grant, refused by the trigger once the grant is added, and permitted
 * only once both are gone. The last run is the demonstrable red.
 */
class AuditAppendOnlyTest : StringSpec() {
    // Pinned rather than inherited. The closing integrity test in this spec only proves anything if
    // it runs after the probes, and Kotest's declaration order is a default, not a guarantee.
    override fun testCaseOrder(): TestCaseOrder = TestCaseOrder.Sequential

    init {
        val seeded = SchemaFixture.asOwner { it.seedProject() }
        val context = listOf(seeded.projectId)

        "the application role can append to the audit trail" {
            SchemaFixture.asApp(context) { connection ->
                connection.runSql(
                    APP_AUDIT_ROW,
                    seeded.projectId,
                    seeded.agentActorId,
                    seeded.ticketId,
                    UUID.randomUUID(),
                )
            } shouldBe 1
        }

        ROW_MUTATIONS.forEach { mutation ->
            "the grant refuses ${mutation.name} on audit_event" {
                val failure =
                    shouldThrow<SQLException> {
                        SchemaFixture.asApp(context) { it.runSql(mutation.sql, seeded.auditRequestId) }
                    }
                failure.sqlState shouldBe INSUFFICIENT_PRIVILEGE
            }

            "the trigger refuses ${mutation.name} on audit_event even once the grant is added" {
                val failure =
                    shouldThrow<SQLException> {
                        SchemaFixture.withGuardsDisabled(listOf(mutation.grantPrivilege), context) {
                            it.runSql(mutation.sql, seeded.auditRequestId)
                        }
                    }
                failure.sqlState shouldBe INSUFFICIENT_PRIVILEGE
                failure.message.orEmpty() shouldContain "audit_event is append-only"
            }

            "${mutation.name} on audit_event succeeds only once the grant and the trigger are both gone" {
                SchemaFixture.withGuardsDisabled(
                    listOf(mutation.grantPrivilege, mutation.disableTrigger),
                    context,
                ) { it.runSql(mutation.sql, seeded.auditRequestId) } shouldBe 1
            }
        }

        "the grant refuses truncate on audit_event" {
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asApp(context) { it.runSql("truncate audit_event") }
                }
            failure.sqlState shouldBe INSUFFICIENT_PRIVILEGE
        }

        "the trigger refuses truncate on audit_event even once the grant is added" {
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.withGuardsDisabled(
                        listOf("grant truncate on audit_event to nodera_app"),
                        context,
                    ) { it.runSql("truncate audit_event") }
                }
            failure.sqlState shouldBe INSUFFICIENT_PRIVILEGE
            failure.message.orEmpty() shouldContain "audit_event is append-only"
        }

        "truncate on audit_event succeeds only once the grant and the trigger are both gone" {
            SchemaFixture.withGuardsDisabled(
                listOf(
                    "grant truncate on audit_event to nodera_app",
                    "alter table audit_event disable trigger audit_event_no_truncate",
                ),
                context,
            ) { connection ->
                connection.countBy(COUNT_BY_REQUEST, seeded.auditRequestId) shouldBe 1L
                connection.runSql("truncate audit_event")
                connection.countBy(COUNT_BY_REQUEST, seeded.auditRequestId)
            } shouldBe 0L
        }

        // Last, deliberately: the cases above granted privileges and disabled triggers inside a
        // transaction and relied on the rollback. If any of those did not roll back, the trail is
        // rewritable now — and the row the truncate removed would be gone.
        "both layers are intact, and the trail unharmed, after every probe has run" {
            SchemaFixture.asApp(context) { connection ->
                connection.countBy(COUNT_BY_REQUEST, seeded.auditRequestId) shouldBe 1L
            }

            shouldThrow<SQLException> {
                SchemaFixture.asApp(context) { it.runSql(ROW_MUTATIONS.first().sql, seeded.auditRequestId) }
            }.sqlState shouldBe INSUFFICIENT_PRIVILEGE
        }
    }
}
