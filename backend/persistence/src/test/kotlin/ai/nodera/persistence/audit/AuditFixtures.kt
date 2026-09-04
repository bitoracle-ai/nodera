package ai.nodera.persistence.audit

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.countBy
import java.sql.Connection
import java.util.UUID
import kotlin.uuid.toKotlinUuid

internal const val COUNT_BY_REQUEST = "select count(*) from audit_event where request_id = ?"

private const val TICKET_TITLE = "select title from ticket where id = ?"

internal fun context(
    actorId: UUID,
    requestId: UUID,
    kind: ActorKind = ActorKind.AGENT,
    surface: Surface = Surface.MCP,
    onBehalfOf: UUID? = null,
): ActorContext =
    ActorContext(
        actorId = ActorId(actorId.toKotlinUuid()),
        kind = kind,
        surface = surface,
        onBehalfOf = onBehalfOf?.let { ActorId(it.toKotlinUuid()) },
        requestId = RequestId(requestId.toKotlinUuid()),
    )

/** Read as the application role, so a row the boundary hides reads as absent rather than as present. */
internal fun auditRows(
    requestId: UUID,
    projectIds: List<UUID>,
): Long = SchemaFixture.asApp(projectIds) { it.countBy(COUNT_BY_REQUEST, requestId) }

internal fun titleOf(
    ticketId: UUID,
    projectIds: List<UUID>,
): String? = SchemaFixture.asApp(projectIds) { it.ticketTitle(ticketId) }

/** The same read through a connection the caller already holds — a transaction's own, in particular. */
internal fun Connection.ticketTitle(ticketId: UUID): String? =
    prepareStatement(TICKET_TITLE).use { statement ->
        statement.setObject(1, ticketId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
