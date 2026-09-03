package ai.nodera.application.collaboration.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.collaboration.ReviewReader
import ai.nodera.application.collaboration.ReviewRecordResult
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketKey

/**
 * The whole review record: every round, ascending, with its findings.
 *
 * There is no "current verdict" here and there must never be one. A round-2 verdict that contradicts
 * round 1 is the most informative thing in the record, and collapsing to the latest destroys exactly
 * what makes the record worth keeping (invariant #9 / R2).
 */
public class ListReviews(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val reviews: ReviewReader,
) {
    public suspend fun rounds(
        ctx: ActorContext,
        projectId: ProjectId,
        key: TicketKey,
    ): ReviewRecordResult =
        unitOfWork.inTransaction {
            when (permissions.require(ctx, projectId, Capability.TICKET_READ)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(
                        ctx,
                        attempted(REVIEW_READ, REVIEW_ENTITY, projectId, mapOf("ticket" to key.rendered)),
                        Capability.TICKET_READ,
                    )
                    ReviewRecordResult.Denied(Capability.TICKET_READ)
                }

                PermissionDecision.Permitted ->
                    reviews
                        .rounds(projectId, key)
                        ?.let { ReviewRecordResult.Rounds(it) }
                        ?: ReviewRecordResult.NotFound
            }
        }
}
