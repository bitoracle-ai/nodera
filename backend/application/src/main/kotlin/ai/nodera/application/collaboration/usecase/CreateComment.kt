package ai.nodera.application.collaboration.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.collaboration.CommentReader
import ai.nodera.application.collaboration.CommentResult
import ai.nodera.application.collaboration.CommentWriter
import ai.nodera.application.collaboration.MentionDirectory
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.ticket.TicketReader
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.collaboration.Comment
import ai.nodera.domain.collaboration.CommentBody
import ai.nodera.domain.collaboration.CommentId
import ai.nodera.domain.collaboration.mentionedHandles
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketKey

/**
 * One comment, from a person or from an agent, stored the same way (invariant CM2).
 *
 * The body arrives as a [CommentBody], which can only be built through its sanitising factory — so
 * raw HTML has been neutralised before this use case can be called, rather than by a step somebody
 * has to remember. Mentions are extracted from that same sanitised text, because the rows have to
 * describe what was actually stored.
 */
public class CreateComment(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val tickets: TicketReader,
    private val comments: CommentReader,
    private val writer: CommentWriter,
    private val mentions: MentionDirectory,
) {
    public suspend fun create(
        ctx: ActorContext,
        projectId: ProjectId,
        key: TicketKey,
        body: CommentBody,
        inReplyTo: CommentId? = null,
    ): CommentResult =
        unitOfWork.inTransaction {
            val attempt = attempted(COMMENT_CREATED, COMMENT_ENTITY, projectId, mapOf("ticket" to key.rendered))
            when (permissions.require(ctx, projectId, Capability.COMMENT_CREATE)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(ctx, attempt, Capability.COMMENT_CREATE)
                    CommentResult.Denied(Capability.COMMENT_CREATE)
                }

                PermissionDecision.Permitted -> write(ctx, projectId, key, body, inReplyTo, attempt)
            }
        }

    private suspend fun write(
        ctx: ActorContext,
        projectId: ProjectId,
        key: TicketKey,
        body: CommentBody,
        inReplyTo: CommentId?,
        attempt: AuditEntry,
    ): CommentResult {
        val ticket = tickets.byKey(projectId, key)
        if (ticket == null) {
            recorder.record(ctx, attempt.refused(TICKET_NOT_VISIBLE))
            return CommentResult.NotFound
        }
        // No entity id: no comment was written, and the ticket's id under entity_type 'comment'
        // would put a row into a history it does not belong to. The key is in the diff instead.
        if (misplacedReply(projectId, ticket, inReplyTo)) {
            recorder.record(ctx, attempt.refused(REPLY_OFF_TICKET))
            return CommentResult.ReplyToAnotherTicket
        }

        val comment = writer.create(ticket.id, ctx.actorId, body, inReplyTo)
        val mentioned = mention(projectId, comment, body)
        recorder.record(ctx, created(projectId, key, comment, mentioned))
        return CommentResult.Written(comment)
    }

    /**
     * The reply-scope rule V3's trigger enforces, read here so it is a value rather than an
     * exception two layers down.
     *
     * A parent that is not visible in this project falls into the same case on purpose: from the
     * caller's side it is not a comment on this ticket, and saying which of the two it is would
     * answer a question about another project.
     */
    private suspend fun misplacedReply(
        projectId: ProjectId,
        ticket: Ticket,
        inReplyTo: CommentId?,
    ): Boolean {
        if (inReplyTo == null) return false
        val parent = comments.byId(projectId, inReplyTo)
        return parent == null || parent.ticketId != ticket.id
    }

    /** An unresolved handle is text that names nobody, not an error; the comment stands either way. */
    private suspend fun mention(
        projectId: ProjectId,
        comment: Comment,
        body: CommentBody,
    ): Int {
        val handles = mentionedHandles(body.value)
        if (handles.isEmpty()) return 0

        val actors = mentions.resolve(projectId, handles)
        if (actors.isNotEmpty()) writer.mention(comment.id, actors)
        return actors.size
    }
}

private fun created(
    projectId: ProjectId,
    key: TicketKey,
    comment: Comment,
    mentioned: Int,
): AuditEntry =
    AuditEntry(
        action = COMMENT_CREATED,
        entityType = COMMENT_ENTITY,
        entityId = comment.id.value,
        projectId = projectId,
        diff =
            AuditDiff(
                after =
                    mapOf(
                        "ticket" to key.rendered,
                        "in_reply_to" to comment.inReplyTo?.value?.toString(),
                        "mentions" to mentioned.toString(),
                    ),
            ),
    )
