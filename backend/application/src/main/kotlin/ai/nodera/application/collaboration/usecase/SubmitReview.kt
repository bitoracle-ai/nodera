package ai.nodera.application.collaboration.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.collaboration.ReviewRoundAllocator
import ai.nodera.application.collaboration.ReviewWriter
import ai.nodera.application.collaboration.SubmitReviewResult
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.ticket.TicketReader
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.collaboration.FindingDraft
import ai.nodera.domain.collaboration.Review
import ai.nodera.domain.collaboration.ReviewRefusal
import ai.nodera.domain.collaboration.ReviewVerdict
import ai.nodera.domain.collaboration.ReviewerCheck
import ai.nodera.domain.collaboration.reviewerIndependence
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.FindingSeverity
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketKey

/**
 * One round of review, appended (invariant #9 / R2).
 *
 * Nothing here reads or rewrites an earlier round: a verdict that contradicts round 1 becomes round
 * 2 and both stay readable. The reviewer-independence rule runs in `:domain` so a refusal is a value
 * rather than V3's trigger arriving as an exception, and it compares identity — never kind.
 *
 * The round is allocated under a lock on the ticket row, which is also what stops a submission
 * landing between a closure gate's read and its write (`docs/plan/CORE-04.md` §§ 4.3–4.4).
 */
public class SubmitReview(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val tickets: TicketReader,
    private val rounds: ReviewRoundAllocator,
    private val writer: ReviewWriter,
) {
    public suspend fun submit(
        ctx: ActorContext,
        projectId: ProjectId,
        key: TicketKey,
        verdict: ReviewVerdict,
        summary: String = "",
        findings: List<FindingDraft> = emptyList(),
    ): SubmitReviewResult =
        unitOfWork.inTransaction {
            when (permissions.require(ctx, projectId, Capability.REVIEW_SUBMIT)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(ctx, attempt(projectId, key), Capability.REVIEW_SUBMIT)
                    SubmitReviewResult.Denied(Capability.REVIEW_SUBMIT)
                }

                PermissionDecision.Permitted -> append(ctx, projectId, key, verdict, summary, findings)
            }
        }

    private suspend fun append(
        ctx: ActorContext,
        projectId: ProjectId,
        key: TicketKey,
        verdict: ReviewVerdict,
        summary: String,
        findings: List<FindingDraft>,
    ): SubmitReviewResult {
        val attempt = attempt(projectId, key)
        val ticket = tickets.byKey(projectId, key)
        if (ticket == null) {
            recorder.record(ctx, attempt.refused(TICKET_NOT_VISIBLE))
            return SubmitReviewResult.NotFound
        }

        // No entity id on either refusal: no review row exists, and the ticket's id under
        // entity_type 'review' would file the row into a history it does not belong to. `V4` indexes
        // the trail on (entity_type, entity_id), so the key travels in the diff instead.
        val independence = reviewerIndependence(ctx.actorId, ticket.reporter, ticket.assignee)
        if (independence is ReviewerCheck.Refused) {
            recorder.record(ctx, attempt.refused(independence.refusal.reason()))
            return SubmitReviewResult.NotIndependent(independence.refusal)
        }

        val round = rounds.nextRound(ticket.id)
        val review = writer.submit(ticket.id, ctx.actorId, round, verdict, summary, findings)
        recorder.record(ctx, submitted(ticket, review))
        return SubmitReviewResult.Submitted(review)
    }
}

private fun attempt(
    projectId: ProjectId,
    key: TicketKey,
): AuditEntry = attempted(REVIEW_SUBMITTED, REVIEW_ENTITY, projectId, mapOf("ticket" to key.rendered))

/**
 * The sentence the trail records, not the type's name.
 *
 * `audit_event` is append-only, so a rename of the data object would otherwise rewrite the trail's
 * vocabulary from a refactor — old rows saying one thing and new rows another, with nothing to say
 * they mean the same.
 */
private fun ReviewRefusal.reason(): String =
    when (this) {
        ReviewRefusal.ReviewerIsAssignee -> "the reviewer is the assignee of this ticket"
        ReviewRefusal.ReviewerIsReporterOfUnassigned -> "the reviewer reported this unassigned ticket"
    }

private fun submitted(
    ticket: Ticket,
    review: Review,
): AuditEntry =
    AuditEntry(
        action = REVIEW_SUBMITTED,
        entityType = REVIEW_ENTITY,
        entityId = review.id.value,
        projectId = ticket.projectId,
        diff =
            AuditDiff(
                after =
                    mapOf(
                        "ticket" to ticket.key.rendered,
                        "round" to review.round.value.toString(),
                        "verdict" to review.verdict.name.lowercase(),
                        "findings" to review.findings.size.toString(),
                        "blocking_findings" to
                            review.findings
                                .count { it.severity == FindingSeverity.BLOCKING }
                                .toString(),
                    ),
            ),
    )
