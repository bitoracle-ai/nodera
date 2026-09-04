package ai.nodera.persistence.collaboration

import ai.nodera.application.collaboration.CommentReader
import ai.nodera.application.collaboration.CommentWriter
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.collaboration.Comment
import ai.nodera.domain.collaboration.CommentBody
import ai.nodera.domain.collaboration.CommentId
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.persistence.Binding
import ai.nodera.persistence.currentConnection
import java.sql.Connection

private const val ROW =
    "id, ticket_id, author_actor_id, body, in_reply_to_comment_id, created_at, edited_at, " +
        "deleted_at, deleted_by_actor_id"

private const val PROJECTED =
    "w.id, w.ticket_id, w.author_actor_id, w.body, w.in_reply_to_comment_id, w.created_at, " +
        "w.edited_at, w.deleted_at, w.deleted_by_actor_id, a.handle, a.kind, a.display_name"

private const val JOINED =
    "c.id, c.ticket_id, c.author_actor_id, c.body, c.in_reply_to_comment_id, c.created_at, " +
        "c.edited_at, c.deleted_at, c.deleted_by_actor_id, a.handle, a.kind, a.display_name"

private const val INSERT =
    "with written as (" +
        "insert into comment (ticket_id, author_actor_id, body, in_reply_to_comment_id) " +
        "values (?, ?, ?, ?) returning $ROW" +
        ") select $PROJECTED from written w join actor a on a.id = w.author_actor_id"

private const val INSERT_MENTION = "insert into comment_mention (comment_id, actor_id) values (?, ?)"

// Scoped through the ticket, not merely by id: row-level security admits every project in the
// session, and the capability was checked against one of them.
private const val BY_ID =
    "select $JOINED from comment c " +
        "join ticket t on t.id = c.ticket_id join actor a on a.id = c.author_actor_id " +
        "where c.id = ? and t.project_id = ?"

private const val THREAD =
    "select $JOINED from comment c " +
        "join ticket t on t.id = c.ticket_id join actor a on a.id = c.author_actor_id " +
        "where t.project_id = ? and t.key = ?::citext order by c.created_at, c.id"

// Conditional on the tombstone, so an edit cannot bring a deleted comment back. author_actor_id is
// not in the statement at all: authorship is never rewritten (invariant CM1).
private const val EDIT =
    "with written as (" +
        "update comment set body = ?, edited_at = now() where id = ? and deleted_at is null returning $ROW" +
        ") select $PROJECTED from written w join actor a on a.id = w.author_actor_id"

// Conditional for a different reason: a second deleter must not overwrite the first one's identity
// and timestamp, which would leave the trail naming the wrong actor.
private const val TOMBSTONE =
    "with written as (" +
        "update comment set body = '', deleted_at = now(), deleted_by_actor_id = ? " +
        "where id = ? and deleted_at is null returning $ROW" +
        ") select $PROJECTED from written w join actor a on a.id = w.author_actor_id"

private const val NO_TRANSACTION =
    "a comment may only be read or written inside the use case's own transaction; " +
        "no transaction is open on this coroutine"

private const val INSERT_RETURNED_NOTHING = "the comment insert returned no row"

/** Reads and writes `comment` and `comment_mention`. It decides nothing. */
public class JdbcCommentRepository :
    CommentReader,
    CommentWriter {
    override suspend fun byId(
        projectId: ProjectId,
        id: CommentId,
    ): Comment? =
        connection()
            .query(BY_ID) {
                it.uuid(id.value)
                it.uuid(projectId.value)
            }.singleOrNull()

    /**
     * `null` when the **ticket** is not visible, and that is the whole reason the visibility probe
     * exists: an unscoped read answers with zero rows, and zero comments is exactly what a ticket
     * with no discussion looks like.
     */
    override suspend fun thread(
        projectId: ProjectId,
        key: TicketKey,
    ): List<Comment>? {
        val connection = connection()
        if (!connection.ticketIsVisible(projectId, key)) return null

        return connection.query(THREAD) {
            it.uuid(projectId.value)
            it.text(key.rendered)
        }
    }

    override suspend fun create(
        ticketId: TicketId,
        author: ActorId,
        body: CommentBody,
        inReplyTo: CommentId?,
    ): Comment =
        connection()
            .query(INSERT) {
                it.uuid(ticketId.value)
                it.uuid(author.value)
                it.text(body.value)
                it.uuid(inReplyTo?.value)
            }.singleOrNull() ?: error(INSERT_RETURNED_NOTHING)

    override suspend fun mention(
        commentId: CommentId,
        actors: List<ActorId>,
    ) {
        val connection = connection()
        connection.prepareStatement(INSERT_MENTION).use { statement ->
            actors.forEach { actor ->
                val bind = Binding(statement)
                bind.uuid(commentId.value)
                bind.uuid(actor.value)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun edit(
        id: CommentId,
        body: CommentBody,
    ): Comment? =
        connection()
            .query(EDIT) {
                it.text(body.value)
                it.uuid(id.value)
            }.singleOrNull()

    override suspend fun tombstone(
        id: CommentId,
        deletedBy: ActorId,
    ): Comment? =
        connection()
            .query(TOMBSTONE) {
                it.uuid(deletedBy.value)
                it.uuid(id.value)
            }.singleOrNull()

    private suspend fun connection(): Connection = currentConnection() ?: error(NO_TRANSACTION)
}

private fun Connection.query(
    sql: String,
    bind: (Binding) -> Unit,
): List<Comment> = rows(sql, bind) { it.toComment() }
