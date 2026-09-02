package ai.nodera.persistence.ticket

import ai.nodera.application.ticket.TicketCandidateReader
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketCandidate
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.domain.ticket.TicketNumber
import ai.nodera.domain.ticket.TicketPrefix
import ai.nodera.domain.ticket.TicketPriority
import ai.nodera.domain.ticket.TicketStatus
import ai.nodera.persistence.Binding
import ai.nodera.persistence.currentConnection
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

private const val CANDIDATES =
    "select t.id, t.prefix, t.number, t.priority, t.status, t.assignee_actor_id, t.created_at " +
        "from ticket t where t.project_id = ?"

// Joined through ticket so the edge list is scoped by the same boundary the tickets are.
private const val DEPENDENCIES =
    "select d.ticket_id, d.depends_on_ticket_id from ticket_dependency d " +
        "join ticket t on t.id = d.ticket_id where t.project_id = ?"

private const val NO_TRANSACTION =
    "the working order may only be read inside a transaction; none is open on this coroutine"

/**
 * Every ticket of one project with its dependency edges — closed ones included, because the rule
 * that decides readiness needs to see what a dependency resolved to.
 */
public class JdbcTicketCandidates : TicketCandidateReader {
    override suspend fun candidates(projectId: ProjectId): List<TicketCandidate> {
        val connection = currentConnection() ?: error(NO_TRANSACTION)
        val edges = connection.dependencyEdges(projectId)

        return connection.query(CANDIDATES, projectId.value) { rows ->
            val id = TicketId(rows.getObject("id", UUID::class.java).toKotlinUuid())
            TicketCandidate(
                id = id,
                key = TicketKey(TicketPrefix(rows.getString("prefix")), TicketNumber(rows.getInt("number"))),
                priority = TicketPriority.valueOf(rows.getString("priority").uppercase()),
                status = TicketStatus.valueOf(rows.getString("status").uppercase()),
                assignee =
                    rows.getObject("assignee_actor_id", UUID::class.java)?.let { ActorId(it.toKotlinUuid()) },
                createdAt = rows.getObject("created_at", OffsetDateTime::class.java).toKotlinInstant(),
                dependsOn = edges[id].orEmpty(),
            )
        }
    }
}

private fun Connection.dependencyEdges(projectId: ProjectId): Map<TicketId, Set<TicketId>> =
    query(DEPENDENCIES, projectId.value) { rows ->
        TicketId(rows.getObject("ticket_id", UUID::class.java).toKotlinUuid()) to
            TicketId(rows.getObject("depends_on_ticket_id", UUID::class.java).toKotlinUuid())
    }.groupBy({ it.first }, { it.second })
        .mapValues { (_, targets) -> targets.toSet() }

private fun <T> Connection.query(
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

private fun OffsetDateTime.toKotlinInstant(): Instant =
    toInstant().let { Instant.fromEpochSeconds(it.epochSecond, it.nano) }
