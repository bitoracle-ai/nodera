package ai.nodera.application.ticket.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.ticket.NextTicketResult
import ai.nodera.application.ticket.TicketCandidateReader
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.WorkingOrder

private val READ = AuditAction("ticket.read")

/**
 * The working order behind one use case, so every surface answers "what should I start?" the same
 * way. The alternative, per `docs/MCP.md` § 3.2, is every agent re-deriving the rule from prose.
 */
public class NextTicket(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val candidates: TicketCandidateReader,
) {
    public suspend fun next(
        ctx: ActorContext,
        projectId: ProjectId,
    ): NextTicketResult =
        unitOfWork.inTransaction {
            when (permissions.require(ctx, projectId, Capability.TICKET_READ)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(ctx, attempted(projectId), Capability.TICKET_READ)
                    NextTicketResult.Denied(Capability.TICKET_READ)
                }

                PermissionDecision.Permitted ->
                    WorkingOrder
                        .next(candidates.candidates(projectId), ctx.actorId)
                        ?.let { NextTicketResult.Selected(it) }
                        ?: NextTicketResult.NothingReady
            }
        }
}

private fun attempted(projectId: ProjectId): AuditEntry =
    AuditEntry(action = READ, entityType = TICKET_ENTITY, projectId = projectId)
