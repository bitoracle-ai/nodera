package ai.nodera.application.collaboration.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.collaboration.CommentReader
import ai.nodera.application.collaboration.CommentResult
import ai.nodera.application.collaboration.CommentWriter
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.collaboration.Comment
import ai.nodera.domain.collaboration.CommentId
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId

private const val ALREADY_DELETED = "the comment was already deleted"

/**
 * Deletion is a tombstone (invariant CM1): the row and its position in the thread survive, the body
 * goes, and the trail names who removed it.
 *
 * Which capability applies depends on whose comment it is — one's own needs `comment.create`,
 * another actor's needs `comment.moderate` — so the row is read before the check. That ordering is
 * safe because the read is project-scoped and answers nothing a project member could not already ask
 * with `comment.read`, and because no mutation happens before the check.
 */
public class DeleteComment(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val comments: CommentReader,
    private val writer: CommentWriter,
) {
    public suspend fun delete(
        ctx: ActorContext,
        projectId: ProjectId,
        id: CommentId,
    ): CommentResult =
        unitOfWork.inTransaction {
            val attempt = attempted(COMMENT_DELETED, COMMENT_ENTITY, projectId).copy(entityId = id.value)

            // Scoped to the project the permission is about, so membership of a second project
            // cannot reach a comment the caller has no capability over (invariant #5).
            val existing = comments.byId(projectId, id)
            if (existing == null) {
                recorder.record(ctx, attempt.refused(COMMENT_NOT_VISIBLE))
                return@inTransaction CommentResult.NotFound
            }

            val required =
                if (existing.author.id == ctx.actorId) Capability.COMMENT_CREATE else Capability.COMMENT_MODERATE

            when (permissions.require(ctx, projectId, required)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(ctx, attempt, required)
                    CommentResult.Denied(required)
                }

                PermissionDecision.Permitted -> tombstone(ctx, projectId, id, attempt)
            }
        }

    private suspend fun tombstone(
        ctx: ActorContext,
        projectId: ProjectId,
        id: CommentId,
        attempt: AuditEntry,
    ): CommentResult {
        // Conditional on the comment still being live, so a second deleter cannot overwrite the
        // first one's identity and timestamp and leave the trail naming the wrong actor.
        val deleted = writer.tombstone(id, ctx.actorId)
        if (deleted == null) {
            recorder.record(ctx, attempt.refused(ALREADY_DELETED))
            return CommentResult.AlreadyDeleted
        }

        recorder.record(ctx, deleted(projectId, deleted))
        return CommentResult.Written(deleted)
    }
}

private fun deleted(
    projectId: ProjectId,
    comment: Comment,
): AuditEntry =
    AuditEntry(
        action = COMMENT_DELETED,
        entityType = COMMENT_ENTITY,
        entityId = comment.id.value,
        projectId = projectId,
        diff =
            AuditDiff(
                before = mapOf("content" to "visible"),
                after = mapOf("content" to "tombstone"),
            ),
    )
