package ai.nodera.application.collaboration.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.collaboration.ResolveFindingResult
import ai.nodera.application.collaboration.ReviewReader
import ai.nodera.application.collaboration.ReviewWriter
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.collaboration.Finding
import ai.nodera.domain.collaboration.FindingId
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId

private const val ALREADY_RESOLVED = "the finding was already resolved"

/**
 * Marks one finding resolved, which is the only direction a finding row moves — and the move the
 * closure gate is waiting for (invariant #8).
 *
 * Two answers that must not merge, and this is where the merge would happen: a finding nobody can
 * see and a finding somebody else has just resolved both produce zero updated rows. So the row is
 * read first, project-scoped, and only then written conditionally — one read too many, deliberately,
 * because telling a caller their finding does not exist when a colleague resolved it a moment ago is
 * the wrong sentence.
 *
 * No ticket lock here, unlike a submission: resolving *removes* a blocker, so the worst a race does
 * is leave a closure refusing something that has just been unblocked, which is the direction the
 * gate is allowed to be wrong in.
 */
public class ResolveFinding(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val reviews: ReviewReader,
    private val writer: ReviewWriter,
) {
    public suspend fun resolve(
        ctx: ActorContext,
        projectId: ProjectId,
        id: FindingId,
        note: String = "",
    ): ResolveFindingResult =
        unitOfWork.inTransaction {
            val attempt = attempted(FINDING_RESOLVED, FINDING_ENTITY, projectId).copy(entityId = id.value)
            when (permissions.require(ctx, projectId, Capability.REVIEW_SUBMIT)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(ctx, attempt, Capability.REVIEW_SUBMIT)
                    ResolveFindingResult.Denied(Capability.REVIEW_SUBMIT)
                }

                PermissionDecision.Permitted -> mark(ctx, projectId, id, note, attempt)
            }
        }

    private suspend fun mark(
        ctx: ActorContext,
        projectId: ProjectId,
        id: FindingId,
        note: String,
        attempt: AuditEntry,
    ): ResolveFindingResult {
        // Scoped to the project the permission was checked against: row-level security admits every
        // project in the session, and the check was about one of them (invariant #5).
        if (reviews.findingById(projectId, id) == null) {
            recorder.record(ctx, attempt.refused(FINDING_NOT_VISIBLE))
            return ResolveFindingResult.NotFound
        }

        val resolved = writer.resolve(id, ctx.actorId, note)
        if (resolved == null) {
            recorder.record(ctx, attempt.refused(ALREADY_RESOLVED))
            return ResolveFindingResult.AlreadyResolved
        }

        recorder.record(ctx, resolved(projectId, resolved))
        return ResolveFindingResult.Resolved(resolved)
    }
}

private fun resolved(
    projectId: ProjectId,
    finding: Finding,
): AuditEntry =
    AuditEntry(
        action = FINDING_RESOLVED,
        entityType = FINDING_ENTITY,
        entityId = finding.id.value,
        projectId = projectId,
        diff =
            AuditDiff(
                before = mapOf("resolved" to "false"),
                after = mapOf("resolved" to "true", "severity" to finding.severity.name.lowercase()),
            ),
    )
