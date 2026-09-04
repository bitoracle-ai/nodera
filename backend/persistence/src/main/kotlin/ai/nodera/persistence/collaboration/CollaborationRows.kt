package ai.nodera.persistence.collaboration

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorSummary
import ai.nodera.domain.actor.DisplayName
import ai.nodera.domain.actor.Handle
import ai.nodera.domain.collaboration.Comment
import ai.nodera.domain.collaboration.CommentBody
import ai.nodera.domain.collaboration.CommentContent
import ai.nodera.domain.collaboration.CommentId
import ai.nodera.domain.collaboration.Finding
import ai.nodera.domain.collaboration.FindingId
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.FindingSeverity
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.persistence.Binding
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlin.time.Instant
import kotlin.uuid.toKotlinUuid

private const val DELETION_WITHOUT_ACTOR =
    "a deleted comment carries no deleter, which V3's deletion_carries_actor check forbids"

private const val TICKET_VISIBLE = "select 1 from ticket where project_id = ? and key = ?::citext"

/**
 * Whether the ticket a thread or a review record hangs off is visible at all.
 *
 * The reason both readers ask before they read: row-level security answers an unscoped query with
 * zero rows, and zero comments — or zero reviews — is exactly what a ticket that simply has none
 * looks like. Without this the two collapse into one answer, and the caller cannot tell them apart.
 */
internal fun Connection.ticketIsVisible(
    projectId: ProjectId,
    key: TicketKey,
): Boolean =
    rows(TICKET_VISIBLE, {
        it.uuid(projectId.value)
        it.text(key.rendered)
    }) { true }.isNotEmpty()

internal fun <T> Connection.rows(
    sql: String,
    bind: (Binding) -> Unit,
    read: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        bind(Binding(statement))
        statement.executeQuery().use { results ->
            buildList { while (results.next()) add(read(results)) }
        }
    }

internal fun ResultSet.toComment(): Comment =
    Comment(
        id = CommentId(uuidAt("id")),
        ticketId = TicketId(uuidAt("ticket_id")),
        author = toAuthor(),
        content = toContent(),
        inReplyTo = optionalUuidAt("in_reply_to_comment_id")?.let { CommentId(it) },
        createdAt = instantAt("created_at"),
    )

internal fun ResultSet.toFinding(): Finding =
    Finding(
        id = FindingId(uuidAt("id")),
        severity = FindingSeverity.valueOf(getString("severity").uppercase()),
        title = getString("title"),
        detail = getString("detail"),
        resolved = getBoolean("resolved"),
    )

internal fun ResultSet.toAuthor(): ActorSummary =
    ActorSummary(
        id = ActorId(uuidAt("author_actor_id")),
        handle = Handle(getString("handle")),
        kind = ActorKind.valueOf(getString("kind").uppercase()),
        displayName = DisplayName(getString("display_name")),
    )

/**
 * A tombstone or a body, never both and never neither.
 *
 * The stored text goes back through the sanitiser on the way out, which costs nothing because it is
 * idempotent. Since the write path removes every `<` there is normally nothing left to do, so this is
 * a second line rather than a load-bearing one — it would catch a `<` that reached the column by some
 * route other than a use case, and no such route exists today.
 */
private fun ResultSet.toContent(): CommentContent {
    val deletedAt = optionalInstantAt("deleted_at")
    if (deletedAt == null) {
        return CommentContent.Visible(CommentBody.of(getString("body")), optionalInstantAt("edited_at"))
    }
    // Unreachable while V3's check constraint stands; the type needs a value and 'nobody' is not one.
    val deletedBy = checkNotNull(optionalUuidAt("deleted_by_actor_id")) { DELETION_WITHOUT_ACTOR }
    return CommentContent.Tombstone(deletedAt, ActorId(deletedBy))
}

internal fun ResultSet.uuidAt(column: String): kotlin.uuid.Uuid = getObject(column, UUID::class.java).toKotlinUuid()

internal fun ResultSet.optionalUuidAt(column: String): kotlin.uuid.Uuid? =
    getObject(column, UUID::class.java)?.toKotlinUuid()

internal fun ResultSet.instantAt(column: String): Instant =
    checkNotNull(optionalInstantAt(column)) { "$column is not null in the schema but came back null" }

internal fun ResultSet.optionalInstantAt(column: String): Instant? =
    getTimestamp(column)?.toInstant()?.let { Instant.fromEpochSeconds(it.epochSecond, it.nano) }
