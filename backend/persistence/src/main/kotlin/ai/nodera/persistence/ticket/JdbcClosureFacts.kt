package ai.nodera.persistence.ticket

import ai.nodera.application.ticket.ClosureFactsReader
import ai.nodera.domain.ticket.AcceptanceCriterion
import ai.nodera.domain.ticket.ClosureFacts
import ai.nodera.domain.ticket.FindingSeverity
import ai.nodera.domain.ticket.ReviewFinding
import ai.nodera.domain.ticket.TicketId
import ai.nodera.persistence.Binding
import ai.nodera.persistence.currentConnection
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

// Two jobs in one statement, and the second arrived with CORE-04. It answers whether the ticket is
// visible, and it holds the row while the gate reads — so a review submitted concurrently cannot
// land its blocking finding between these reads and the transition's write. CORE-04 is the first
// package that can write one; before it, the table was written only by tests.
private const val LOCK_TICKET = "select 1 from ticket where id = ? for update"

private const val CRITERIA =
    "select ac.ordinal, ac.text, ac.met from acceptance_criterion ac where ac.ticket_id = ? order by ac.ordinal"

private const val FINDINGS =
    "select f.id, f.title, f.severity, (f.resolved_at is not null) as resolved " +
        "from review_finding f join review r on r.id = f.review_id where r.ticket_id = ? order by f.created_at"

private const val REVIEW_COUNT = "select count(*) from review where ticket_id = ?"

private const val NO_TRANSACTION =
    "closure facts may only be read inside the transition's own transaction; " +
        "no transaction is open on this coroutine"

/**
 * The gate's three reads, behind one visibility check that is also a lock.
 *
 * The check is the point. Every query here is project-scoped, and row-level security answers an
 * unscoped one with zero rows — which, fed to the gate, reads as a ticket with nothing outstanding.
 * A ticket that cannot be seen therefore produces `null` rather than empty facts.
 *
 * The lock is the second point, and it serialises this evaluation against a concurrent review
 * submission on the same ticket — see [ai.nodera.persistence.collaboration.JdbcReviewRepository].
 * Without it a blocking finding can commit after these reads and before the transition's write, and
 * the ticket closes as done carrying it.
 *
 * Nothing here reads findings by round: an unresolved blocking finding from round 1 holds closure
 * however many clean rounds follow it.
 */
public class JdbcClosureFacts : ClosureFactsReader {
    override suspend fun facts(ticketId: TicketId): ClosureFacts? {
        val connection = currentConnection() ?: error(NO_TRANSACTION)
        if (!connection.lockTicket(ticketId)) return null

        return connection.closureFacts(ticketId)
    }
}

/**
 * The three reads, with no visibility check of their own.
 *
 * Shared with the test stand-in that models this adapter **without** the lock, so the two can differ
 * in exactly one statement. A stand-in that reimplements the reads is a stand-in that can drift from
 * what it claims to model.
 */
internal fun Connection.closureFacts(ticketId: TicketId): ClosureFacts =
    ClosureFacts(
        criteria = rows(CRITERIA, ticketId.value) { it.toCriterion() },
        findings = rows(FINDINGS, ticketId.value) { it.toFinding() },
        reviewCount = reviewCount(ticketId),
    )

private fun Connection.lockTicket(ticketId: TicketId): Boolean = rows(LOCK_TICKET, ticketId.value) { true }.isNotEmpty()

private fun Connection.reviewCount(ticketId: TicketId): Int =
    rows(REVIEW_COUNT, ticketId.value) { it.getInt(1) }.single()

internal fun <T> Connection.rows(
    sql: String,
    key: Uuid,
    read: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        Binding(statement).uuid(key)
        statement.executeQuery().use { results ->
            buildList { while (results.next()) add(read(results)) }
        }
    }

private fun ResultSet.toCriterion(): AcceptanceCriterion =
    AcceptanceCriterion(ordinal = getInt("ordinal"), text = getString("text"), met = getBoolean("met"))

private fun ResultSet.toFinding(): ReviewFinding =
    ReviewFinding(
        id = getObject("id", UUID::class.java).toKotlinUuid(),
        title = getString("title"),
        severity = FindingSeverity.valueOf(getString("severity").uppercase()),
        resolved = getBoolean("resolved"),
    )
