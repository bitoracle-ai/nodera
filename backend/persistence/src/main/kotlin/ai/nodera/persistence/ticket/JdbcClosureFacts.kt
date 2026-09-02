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

private const val TICKET_EXISTS = "select 1 from ticket where id = ?"

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
 * The gate's three reads, behind one visibility check.
 *
 * The check is the point. Every query here is project-scoped, and row-level security answers an
 * unscoped one with zero rows — which, fed to the gate, reads as a ticket with nothing outstanding.
 * A ticket that cannot be seen therefore produces `null` rather than empty facts.
 */
public class JdbcClosureFacts : ClosureFactsReader {
    override suspend fun facts(ticketId: TicketId): ClosureFacts? {
        val connection = currentConnection() ?: error(NO_TRANSACTION)
        if (!connection.ticketIsVisible(ticketId)) return null

        return ClosureFacts(
            criteria = connection.rows(CRITERIA, ticketId.value) { it.toCriterion() },
            findings = connection.rows(FINDINGS, ticketId.value) { it.toFinding() },
            reviewCount = connection.reviewCount(ticketId),
        )
    }
}

private fun Connection.ticketIsVisible(ticketId: TicketId): Boolean =
    rows(TICKET_EXISTS, ticketId.value) { true }.isNotEmpty()

private fun Connection.reviewCount(ticketId: TicketId): Int =
    rows(REVIEW_COUNT, ticketId.value) { it.getInt(1) }.single()

private fun <T> Connection.rows(
    sql: String,
    key: Uuid,
    read: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        Binding(statement).uuid(key)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(read(rows)) }
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
