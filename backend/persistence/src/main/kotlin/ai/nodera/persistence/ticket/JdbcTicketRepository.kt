package ai.nodera.persistence.ticket

import ai.nodera.application.ticket.TicketReader
import ai.nodera.application.ticket.TicketWriter
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketDraft
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.domain.ticket.TicketNumber
import ai.nodera.domain.ticket.TicketPrefix
import ai.nodera.domain.ticket.TicketPriority
import ai.nodera.domain.ticket.TicketResolution
import ai.nodera.domain.ticket.TicketState
import ai.nodera.domain.ticket.TicketStatus
import ai.nodera.persistence.Binding
import ai.nodera.persistence.currentConnection
import ai.nodera.persistence.label
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlin.uuid.toKotlinUuid

private const val COLUMNS =
    "id, project_id, prefix, number, title, priority, status, resolution, " +
        "reporter_actor_id, assignee_actor_id"

private const val INSERT_TICKET =
    "insert into ticket (project_id, key, prefix, number, title, body, priority, reporter_actor_id) " +
        "values (?, ?::citext, ?::citext, ?, ?, ?, ?::ticket_priority, ?) returning $COLUMNS"

private const val BY_KEY = "select $COLUMNS from ticket where project_id = ? and key = ?::citext"

// closed_at follows the status rather than the caller's clock: the database is the clock for a
// record, exactly as it is for occurred_at on the audit trail.
private const val APPLY_TRANSITION =
    "update ticket set status = ?::ticket_status, resolution = ?::ticket_resolution, " +
        "closed_at = case when ?::ticket_status = 'closed' then now() end, updated_at = now() " +
        "where id = ? and status = ?::ticket_status returning $COLUMNS"

private const val INSERT_RETURNED_NOTHING = "the ticket insert returned no row"

private const val NO_TRANSACTION =
    "a ticket may only be written inside the mutation's own transaction; " +
        "no transaction is open on this coroutine"

/** Reads and writes the `ticket` row. It decides nothing: the state machine and the gate do that. */
public class JdbcTicketRepository :
    TicketReader,
    TicketWriter {
    override suspend fun byKey(
        projectId: ProjectId,
        key: TicketKey,
    ): Ticket? =
        connection().prepareStatement(BY_KEY).use { statement ->
            val bind = Binding(statement)
            bind.uuid(projectId.value)
            bind.text(key.rendered)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toTicket() else null }
        }

    override suspend fun create(
        projectId: ProjectId,
        key: TicketKey,
        draft: TicketDraft,
        reporter: ActorId,
    ): Ticket =
        connection().prepareStatement(INSERT_TICKET).use { statement ->
            val bind = Binding(statement)
            bind.uuid(projectId.value)
            bind.text(key.rendered)
            bind.text(key.prefix.value)
            bind.int(key.number.value)
            bind.text(draft.title)
            bind.text(draft.body)
            bind.text(draft.priority.label)
            bind.uuid(reporter.value)
            statement.executeQuery().use { rows ->
                check(rows.next()) { INSERT_RETURNED_NOTHING }
                rows.toTicket()
            }
        }

    override suspend fun applyTransition(
        id: TicketId,
        from: TicketStatus,
        to: TicketState,
    ): Ticket? =
        connection().prepareStatement(APPLY_TRANSITION).use { statement ->
            val bind = Binding(statement)
            bind.text(to.status.label)
            bind.text(to.resolution?.label)
            bind.text(to.status.label)
            bind.uuid(id.value)
            bind.text(from.label)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toTicket() else null }
        }

    private suspend fun connection(): Connection = currentConnection() ?: error(NO_TRANSACTION)
}

private fun ResultSet.toTicket(): Ticket =
    Ticket(
        id = TicketId(getObject("id", UUID::class.java).toKotlinUuid()),
        projectId = ProjectId(getObject("project_id", UUID::class.java).toKotlinUuid()),
        key = TicketKey(TicketPrefix(getString("prefix")), TicketNumber(getInt("number"))),
        title = getString("title"),
        priority = TicketPriority.valueOf(getString("priority").uppercase()),
        state =
            TicketState(
                status = TicketStatus.valueOf(getString("status").uppercase()),
                resolution = getString("resolution")?.let { TicketResolution.valueOf(it.uppercase()) },
            ),
        reporter = ActorId(getObject("reporter_actor_id", UUID::class.java).toKotlinUuid()),
        assignee = getObject("assignee_actor_id", UUID::class.java)?.let { ActorId(it.toKotlinUuid()) },
    )
