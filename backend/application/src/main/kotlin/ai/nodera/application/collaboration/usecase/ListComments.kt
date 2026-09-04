package ai.nodera.application.collaboration.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.collaboration.CommentReader
import ai.nodera.application.collaboration.CommentThreadResult
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketKey

/**
 * The thread, oldest first, every author carrying its kind so a reader is told rather than left to
 * infer (invariant CM2).
 *
 * A ticket the caller cannot see answers [CommentThreadResult.NotFound], never an empty thread — the
 * distinction CORE-03 built `ClosureFacts?` for, and the one an unscoped read would otherwise erase.
 */
public class ListComments(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val comments: CommentReader,
) {
    public suspend fun thread(
        ctx: ActorContext,
        projectId: ProjectId,
        key: TicketKey,
    ): CommentThreadResult =
        unitOfWork.inTransaction {
            when (permissions.require(ctx, projectId, Capability.COMMENT_READ)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(
                        ctx,
                        attempted(COMMENT_READ, COMMENT_ENTITY, projectId, mapOf("ticket" to key.rendered)),
                        Capability.COMMENT_READ,
                    )
                    CommentThreadResult.Denied(Capability.COMMENT_READ)
                }

                PermissionDecision.Permitted ->
                    comments
                        .thread(projectId, key)
                        ?.let { CommentThreadResult.Thread(it) }
                        ?: CommentThreadResult.NotFound
            }
        }
}
