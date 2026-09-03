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
import ai.nodera.domain.collaboration.CommentBody
import ai.nodera.domain.collaboration.CommentContent
import ai.nodera.domain.collaboration.CommentId
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId

private const val NOT_THE_AUTHOR = "only the author edits their own comment"

private const val ALREADY_DELETED = "the comment was already deleted"

/**
 * An edit rewrites the body and nothing else.
 *
 * `author_actor_id` is never in the statement, and only the author may call this: editing another
 * actor's words under that actor's name is the rewrite invariant CM1 forbids, in everything but the
 * column. Editing is authoring, so the capability is `comment.create` — `docs/plan/CORE-04.md` § 4.7
 * says why no new verb was invented.
 *
 * Two concurrent edits by the same author are last-write-wins on the body; the compare-and-set is on
 * the tombstone, not on the text, and an optimistic version column is a schema change this package
 * does not need.
 *
 * **Mentions are fixed at creation and an edit does not touch them**, which is a decision rather than
 * an omission. A `comment_mention` row records that an actor was named on this comment; reconciling
 * on every edit would delete rows, and a naming that happened cannot be made not to have happened —
 * the same reason `review` is append-only. The cost is that a handle *added* by an edit is never
 * recorded, which matters once something notifies; `docs/plan/CORE-04.md` § 8 raises it.
 */
public class EditComment(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val comments: CommentReader,
    private val writer: CommentWriter,
) {
    public suspend fun edit(
        ctx: ActorContext,
        projectId: ProjectId,
        id: CommentId,
        body: CommentBody,
    ): CommentResult =
        unitOfWork.inTransaction {
            val attempt = attempted(COMMENT_EDITED, COMMENT_ENTITY, projectId).copy(entityId = id.value)
            when (permissions.require(ctx, projectId, Capability.COMMENT_CREATE)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(ctx, attempt, Capability.COMMENT_CREATE)
                    CommentResult.Denied(Capability.COMMENT_CREATE)
                }

                PermissionDecision.Permitted -> rewrite(ctx, projectId, id, body, attempt)
            }
        }

    private suspend fun rewrite(
        ctx: ActorContext,
        projectId: ProjectId,
        id: CommentId,
        body: CommentBody,
        attempt: AuditEntry,
    ): CommentResult {
        // Scoped to the project the permission was checked against, so membership of a second
        // project cannot reach a comment the caller has no capability over (invariant #5).
        val existing = comments.byId(projectId, id)
        if (existing == null) {
            recorder.record(ctx, attempt.refused(COMMENT_NOT_VISIBLE))
            return CommentResult.NotFound
        }
        if (existing.author.id != ctx.actorId) {
            recorder.record(ctx, attempt.refused(NOT_THE_AUTHOR))
            return CommentResult.NotAuthor
        }

        val updated = writer.edit(id, body)
        if (updated == null) {
            recorder.record(ctx, attempt.refused(ALREADY_DELETED))
            return CommentResult.AlreadyDeleted
        }

        recorder.record(ctx, edited(projectId, existing, updated))
        return CommentResult.Written(updated)
    }
}

private fun edited(
    projectId: ProjectId,
    before: Comment,
    after: Comment,
): AuditEntry =
    AuditEntry(
        action = COMMENT_EDITED,
        entityType = COMMENT_ENTITY,
        entityId = after.id.value,
        projectId = projectId,
        diff =
            AuditDiff(
                before = mapOf("body_characters" to before.bodyCharacters()),
                after = mapOf("body_characters" to after.bodyCharacters()),
            ),
    )

/** The trail records that the text changed and by how much, never the text itself. */
private fun Comment.bodyCharacters(): String? =
    (content as? CommentContent.Visible)
        ?.body
        ?.value
        ?.length
        ?.toString()
