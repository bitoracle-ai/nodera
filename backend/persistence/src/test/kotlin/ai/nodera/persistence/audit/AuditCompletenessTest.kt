package ai.nodera.persistence.audit

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.currentConnection
import ai.nodera.persistence.runSql
import ai.nodera.persistence.seedProject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.UUID
import kotlin.uuid.toKotlinUuid

private const val RETITLE = "update ticket set title = ? where id = ?"

/** No parameters, because a plain `Statement` takes none; row-level security scopes it. */
private const val RETITLE_ALL = "update ticket set title = 'via a plain statement'"

private suspend fun retitle(
    ticketId: UUID,
    title: String,
) {
    requireNotNull(currentConnection()) { "no transaction is open" }.runSql(RETITLE, title, ticketId)
}

/** `execute`, not `executeUpdate`: some of these statements return rows. */
private suspend fun execute(
    sql: String,
    vararg params: Any?,
) {
    requireNotNull(currentConnection()) { "no transaction is open" }.prepareStatement(sql).use { statement ->
        params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.execute()
    }
}

/** Mutations an anchored, first-match-only scan of the statement text does not see. */
private val EVASIONS =
    listOf(
        "a common table expression" to
            "with bumped as (update ticket set title = ? where id = ? returning id) " +
            "select count(*) from bumped",
        "a leading comment" to "-- rename it quietly\nupdate ticket set title = ? where id = ?",
        // Locks and mutates in one statement: stripping the lock must not take the mutation with it.
        "a row lock in the same statement" to
            "with locked as (select id from ticket where title is not null for update) " +
            "update ticket set title = ? where id = ? and id in (select id from locked)",
    )

/**
 * The completeness half of invariant #3, and the proof that the check itself is alive.
 *
 * Several cases here commit a mutation the harness must refuse, one per door it watches. They are
 * committed on purpose and kept: a completeness check that has never caught anything is not a
 * completeness check, and a guard ships with a test demonstrably red without it.
 */
class AuditCompletenessTest :
    StringSpec({

        val seeded = SchemaFixture.asOwner { it.seedProject() }
        val scope = listOf(seeded.projectId)
        val unitOfWork = auditedUnitOfWork(scope)
        val recorder = AuditRecorder(AuditEventRepository())

        fun closing(outcome: String) =
            AuditEntry(
                action = AuditAction("ticket.closed"),
                entityType = "ticket",
                entityId = seeded.ticketId.toKotlinUuid(),
                projectId = ProjectId(seeded.projectId.toKotlinUuid()),
                diff = AuditDiff(after = mapOf("title" to outcome)),
            )

        "a mutation and its audit event commit together, in one transaction" {
            val request = UUID.randomUUID()

            unitOfWork.inTransaction {
                retitle(seeded.ticketId, "audited")
                recorder.record(context(seeded.agentActorId, request), closing("audited"))
            }

            auditRows(request, scope) shouldBe 1L
            titleOf(seeded.ticketId, scope) shouldBe "audited"
        }

        // Criterion: the enforcement mechanism fails when a deliberately un-audited mutation is
        // added. This is that mutation, kept rather than run once by hand.
        "a mutation committed without an audit event is refused, and nothing it did survives" {
            val before = titleOf(seeded.ticketId, scope)

            val failure =
                shouldThrow<AssertionError> {
                    unitOfWork.inTransaction { retitle(seeded.ticketId, "never audited") }
                }

            failure.message.orEmpty() shouldContain "update ticket"
            titleOf(seeded.ticketId, scope) shouldBe before
        }

        "a mutation audited twice is refused too — the rule is exactly one, not at least one" {
            val request = UUID.randomUUID()

            shouldThrow<AssertionError> {
                unitOfWork.inTransaction {
                    retitle(seeded.ticketId, "audited twice")
                    recorder.record(context(seeded.agentActorId, request), closing("audited twice"))
                    recorder.record(context(seeded.agentActorId, request), closing("audited twice"))
                }
            }

            auditRows(request, scope) shouldBe 0L
        }

        // A denial mutates nothing, so its transaction carries the audit row alone. Refusing that
        // shape would make the trail unable to answer what an agent tried to do.
        "a denial writes its event with no mutation, and is accepted" {
            val request = UUID.randomUUID()

            unitOfWork.inTransaction {
                recorder.recordDenied(
                    context(seeded.agentActorId, request),
                    closing("refused"),
                    Capability.TICKET_CLOSE,
                )
            }

            auditRows(request, scope) shouldBe 1L
        }

        "a transaction that only reads is accepted" {
            val current = titleOf(seeded.ticketId, scope)

            unitOfWork.inTransaction {
                requireNotNull(currentConnection()).ticketTitle(seeded.ticketId)
            } shouldBe current
        }

        // THE acceptance criterion: the audit row rolls back WITH the mutation. Proved by failing a
        // transaction, not by reasoning about it. Red if the sink ever opens its own transaction.
        "a failing transaction leaves neither the mutation nor its audit event behind" {
            val request = UUID.randomUUID()
            val before = titleOf(seeded.ticketId, scope)

            shouldThrow<IllegalStateException> {
                unitOfWork.inTransaction {
                    retitle(seeded.ticketId, "rolled back")
                    recorder.record(context(seeded.agentActorId, request), closing("rolled back"))
                    error("the use case failed after both writes")
                }
            }

            auditRows(request, scope) shouldBe 0L
            titleOf(seeded.ticketId, scope) shouldBe before
        }

        EVASIONS.forEach { (shape, sql) ->
            "an un-audited mutation behind $shape is refused too" {
                val before = titleOf(seeded.ticketId, scope)

                shouldThrow<AssertionError> {
                    unitOfWork.inTransaction { execute(sql, "smuggled behind $shape", seeded.ticketId) }
                }

                titleOf(seeded.ticketId, scope) shouldBe before
            }

            "the same mutation behind $shape is accepted once it is audited" {
                val request = UUID.randomUUID()

                unitOfWork.inTransaction {
                    execute(sql, "audited behind $shape", seeded.ticketId)
                    recorder.record(context(seeded.agentActorId, request), closing("audited behind $shape"))
                }

                auditRows(request, scope) shouldBe 1L
                titleOf(seeded.ticketId, scope) shouldBe "audited behind $shape"
            }
        }

        // The harness intercepts three statement doors and two commit doors. Each needs its own
        // case, or a door that stops working takes an un-audited mutation through it in silence.
        "an un-audited mutation issued through a plain Statement is refused" {
            val before = titleOf(seeded.ticketId, scope)

            shouldThrow<AssertionError> {
                unitOfWork.inTransaction {
                    requireNotNull(currentConnection()).createStatement().use { it.executeUpdate(RETITLE_ALL) }
                }
            }

            titleOf(seeded.ticketId, scope) shouldBe before
        }

        // pgjdbc accepts plain SQL here, and CallableStatement extends PreparedStatement, so this
        // is a real door rather than a hypothetical one.
        "an un-audited mutation issued through a CallableStatement is refused" {
            val before = titleOf(seeded.ticketId, scope)

            shouldThrow<AssertionError> {
                unitOfWork.inTransaction {
                    requireNotNull(currentConnection()).prepareCall(RETITLE).use { call ->
                        call.setObject(1, "via prepareCall")
                        call.setObject(2, seeded.ticketId)
                        call.execute()
                    }
                }
            }

            titleOf(seeded.ticketId, scope) shouldBe before
        }

        // setAutoCommit(true) commits an open transaction without ever reaching commit().
        "an un-audited mutation ended by turning auto-commit back on is refused" {
            val before = titleOf(seeded.ticketId, scope)

            shouldThrow<AssertionError> {
                unitOfWork.inTransaction {
                    val connection = requireNotNull(currentConnection())
                    retitle(seeded.ticketId, "committed sideways")
                    connection.autoCommit = true
                }
            }

            titleOf(seeded.ticketId, scope) shouldBe before
        }

        // Two routes out of the harness and back to the raw connection, both refused.
        "the watched connection cannot be unwrapped back to an unwatched one" {
            shouldThrow<SQLFeatureNotSupportedException> {
                unitOfWork.inTransaction {
                    requireNotNull(currentConnection()).unwrap(Connection::class.java)
                }
            }
        }

        "the watched connection cannot be reached through its own metadata" {
            shouldThrow<SQLFeatureNotSupportedException> {
                unitOfWork.inTransaction { requireNotNull(currentConnection()).metaData }
            }
        }

        "a statement hands back the watched connection, not the one underneath it" {
            unitOfWork.inTransaction {
                val connection = requireNotNull(currentConnection())
                connection.createStatement().use { it.connection shouldBe connection }
            }
        }

        // The other polarity: a verb inside a comment or a string literal is not a mutation, or a
        // read-only transaction would be refused for describing one.
        // `select … for update` is the lock key allocation takes, and it mutates nothing. In either
        // case, because SQL is case-insensitive and the scan that reads it must be too.
        listOf("for update of ticket_sequence", "FOR UPDATE OF ticket_sequence").forEach { lock ->
            "a row lock written '$lock' is not a mutation" {
                val query = "select next_number from ticket_sequence where project_id = ? $lock"

                unitOfWork.inTransaction { execute(query, seeded.projectId) }
            }
        }

        "a read that merely mentions a mutation in its text is accepted" {
            unitOfWork.inTransaction {
                execute("select count(*) from ticket where title = 'update ticket set title'")
                execute("-- update ticket, one day\nselect count(*) from ticket")
            }
        }
    })
