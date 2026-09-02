package ai.nodera.persistence.audit

import ai.nodera.application.audit.AuditEventSink
import ai.nodera.domain.audit.AuditEvent
import ai.nodera.persistence.currentConnection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.PreparedStatement
import java.sql.Types
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

private const val APPEND_EVENT =
    "insert into audit_event (" +
        "project_id, actor_id, actor_kind, on_behalf_of_actor_id, surface, tool_name, " +
        "action, entity_type, entity_id, before, after, outcome, request_id" +
        ") values (?, ?, ?::actor_kind, ?, ?::audit_surface, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)"

private const val NO_TRANSACTION =
    "an audit event may only be appended inside the mutation's own transaction (invariant #3); " +
        "no transaction is open on this coroutine"

/**
 * The trail's one writer. It uses the transaction already in progress and **refuses when there is
 * none** — opening its own would let the mutation and its audit row commit independently.
 *
 * Enum labels are the lowercased Kotlin names, and a test inserts every value of all three rather
 * than leaving that coupling assumed.
 */
public class AuditEventRepository : AuditEventSink {
    override suspend fun append(event: AuditEvent) {
        // Already on Dispatchers.IO: the only thing that opens a transaction is JdbcUnitOfWork,
        // and it establishes the dispatcher around the whole use case.
        val connection = currentConnection() ?: error(NO_TRANSACTION)
        val ctx = event.context
        val entry = event.entry

        connection.prepareStatement(APPEND_EVENT).use { statement ->
            val bind = Binding(statement)
            bind.uuid(entry.projectId?.value)
            bind.uuid(ctx.actorId.value)
            bind.text(ctx.kind.label)
            bind.uuid(ctx.onBehalfOf?.value)
            bind.text(ctx.surface.label)
            bind.text(entry.toolName)
            bind.text(entry.action.value)
            bind.text(entry.entityType)
            bind.uuid(entry.entityId)
            bind.text(entry.diff.before.asJson())
            bind.text(entry.diff.after.asJson())
            bind.text(entry.outcome.label)
            bind.uuid(ctx.requestId.value)
            statement.executeUpdate()
        }
    }
}

/** Binds in declaration order, so the thirteen placeholders never have to be counted by hand. */
private class Binding(
    private val statement: PreparedStatement,
) {
    private var index = 0

    fun uuid(value: Uuid?) {
        index += 1
        if (value == null) {
            statement.setNull(index, Types.OTHER)
        } else {
            statement.setObject(index, value.toJavaUuid())
        }
    }

    fun text(value: String?) {
        index += 1
        statement.setString(index, value)
    }
}

private val Enum<*>.label: String get() = name.lowercase()

/** An empty map is `null`, not `{}` — "nothing recorded" and "recorded as empty" must not look alike. */
private fun Map<String, String?>.asJson(): String? =
    if (isEmpty()) null else JsonObject(mapValues { (_, value) -> JsonPrimitive(value) }).toString()
