package ai.nodera.persistence.ticket

import ai.nodera.application.ticket.TicketKeyAllocator
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketNumber
import ai.nodera.domain.ticket.TicketPrefix
import ai.nodera.persistence.Binding
import ai.nodera.persistence.currentConnection
import java.sql.Connection

// citext is compared explicitly rather than relying on how a bound varchar resolves against it.
private const val LOCK_ROW =
    "select next_number from ticket_sequence where project_id = ? and prefix = ?::citext for update"

private const val CREATE_ROW =
    "insert into ticket_sequence (project_id, prefix, next_number) values (?, ?::citext, 1) " +
        "on conflict (project_id, prefix) do nothing"

private const val BUMP_ROW =
    "update ticket_sequence set next_number = next_number + 1 where project_id = ? and prefix = ?::citext"

private const val NO_TRANSACTION =
    "a ticket key may only be allocated inside the mutation's own transaction; " +
        "no transaction is open on this coroutine"

private const val NO_SEQUENCE_ROW =
    "the ticket_sequence row is not readable immediately after being created. Refusing rather than " +
        "starting at 1: that would reissue keys that already belong to existing tickets (invariant #10)."

/**
 * The per-project, per-prefix counter, allocated under `select … for update`.
 *
 * The lock is what makes two concurrent creates produce two keys rather than one key twice, so it is
 * the **first** statement on the path an existing prefix takes. Creating the row happens only when
 * there is none; putting that insert first would make every concurrent create contend on the unique
 * index instead, which serialises them for a different reason and leaves the lock untested.
 *
 * Nothing here reads the tickets. A number derived from `max(number)` or from the open tickets is
 * reissued the moment a ticket closes, and a reused key silently retargets every commit, chat
 * message and external reference that quoted it (invariant #10).
 */
public class JdbcTicketSequence : TicketKeyAllocator {
    override suspend fun allocate(
        projectId: ProjectId,
        prefix: TicketPrefix,
    ): TicketNumber {
        val connection = currentConnection() ?: error(NO_TRANSACTION)

        val next = connection.lockedNextNumber(projectId, prefix) ?: connection.startSequence(projectId, prefix)
        connection.update(BUMP_ROW, projectId, prefix)

        return TicketNumber(next)
    }
}

/**
 * A prefix used for the first time.
 *
 * What actually stops an unscoped caller is `V4`'s policy: `ticket_sequence` is `for all … using`
 * with no separate `with check`, so one predicate governs the read and the write and the insert is
 * refused a statement before this. The refusal below is therefore **not** the guard and has never
 * been seen to fire; it is kept because the only other answer is 1, and a policy later widened on
 * the write side alone would turn that into a silently restarted sequence.
 */
private fun Connection.startSequence(
    projectId: ProjectId,
    prefix: TicketPrefix,
): Int {
    update(CREATE_ROW, projectId, prefix)
    return lockedNextNumber(projectId, prefix) ?: error(NO_SEQUENCE_ROW)
}

private fun Connection.update(
    sql: String,
    projectId: ProjectId,
    prefix: TicketPrefix,
) {
    prepareStatement(sql).use { statement ->
        val bind = Binding(statement)
        bind.uuid(projectId.value)
        bind.text(prefix.value)
        statement.executeUpdate()
    }
}

private fun Connection.lockedNextNumber(
    projectId: ProjectId,
    prefix: TicketPrefix,
): Int? =
    prepareStatement(LOCK_ROW).use { statement ->
        val bind = Binding(statement)
        bind.uuid(projectId.value)
        bind.text(prefix.value)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
    }
