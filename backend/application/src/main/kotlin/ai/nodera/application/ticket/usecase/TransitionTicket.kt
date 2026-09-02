package ai.nodera.application.ticket.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.ticket.ClosureFactsReader
import ai.nodera.application.ticket.TicketReader
import ai.nodera.application.ticket.TicketWriter
import ai.nodera.application.ticket.TransitionResult
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.ClosureGate
import ai.nodera.domain.ticket.ClosureVerdict
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.domain.ticket.TicketResolution
import ai.nodera.domain.ticket.TicketState
import ai.nodera.domain.ticket.TicketStatus
import ai.nodera.domain.ticket.TransitionOutcome
import ai.nodera.domain.ticket.TransitionRefusal
import ai.nodera.domain.ticket.UnmetClosureRequirements
import ai.nodera.domain.ticket.transition
import kotlin.uuid.Uuid

private val TRANSITIONED = AuditAction("ticket.transitioned")

private const val NOT_VISIBLE = "no such ticket is visible"

private const val MOVED_UNDER_US = "the ticket left the status this transition was decided against"

/**
 * The one path a status changes by, and therefore the one place the closure gate runs (invariant #8).
 *
 * The state machine answers with [TransitionOutcome.PermittedIfClosureGatePasses] rather than with a
 * flag, so the branch that runs the gate is one the compiler insists on.
 */
public class TransitionTicket(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val tickets: TicketReader,
    private val writer: TicketWriter,
    private val closureFacts: ClosureFactsReader,
) {
    public suspend fun transition(
        ctx: ActorContext,
        projectId: ProjectId,
        key: TicketKey,
        to: TicketStatus,
        resolution: TicketResolution?,
    ): TransitionResult =
        unitOfWork.inTransaction {
            val missing = missingCapability(ctx, projectId, to)
            if (missing == null) {
                resolveTicket(ctx, projectId, key, to, resolution)
            } else {
                recorder.recordDenied(ctx, attempted(projectId, key, to), missing)
                TransitionResult.Denied(missing)
            }
        }

    /** Closing needs both verbs (`docs/MCP.md` § 3.3); the first one missing is the one reported. */
    private suspend fun missingCapability(
        ctx: ActorContext,
        projectId: ProjectId,
        to: TicketStatus,
    ): Capability? {
        val required =
            if (to == TicketStatus.CLOSED) {
                listOf(Capability.TICKET_TRANSITION, Capability.TICKET_CLOSE)
            } else {
                listOf(Capability.TICKET_TRANSITION)
            }
        return required.firstOrNull { permissions.require(ctx, projectId, it) is PermissionDecision.Denied }
    }

    private suspend fun resolveTicket(
        ctx: ActorContext,
        projectId: ProjectId,
        key: TicketKey,
        to: TicketStatus,
        resolution: TicketResolution?,
    ): TransitionResult {
        val ticket = tickets.byKey(projectId, key)
        if (ticket == null) {
            recorder.record(ctx, refused(projectId, key, to, NOT_VISIBLE))
            return TransitionResult.NotFound
        }

        return when (val outcome = transition(ticket.state.status, to, resolution)) {
            is TransitionOutcome.Refused -> {
                recorder.record(
                    ctx,
                    refused(projectId, key, to, outcome.refusal.toString(), ticket.id.value),
                )
                TransitionResult.Refused(outcome.refusal)
            }

            TransitionOutcome.Permitted -> apply(ctx, ticket, TicketState(to, resolution))
            TransitionOutcome.PermittedIfClosureGatePasses -> gated(ctx, ticket, TicketState(to, resolution))
        }
    }

    private suspend fun gated(
        ctx: ActorContext,
        ticket: Ticket,
        state: TicketState,
    ): TransitionResult {
        // Null is "the ticket is not visible", which is not the answer "nothing is outstanding".
        // The second would close it.
        val facts = closureFacts.facts(ticket.id)
        if (facts == null) {
            recorder.record(
                ctx,
                refused(ticket.projectId, ticket.key, state.status, NOT_VISIBLE, ticket.id.value),
            )
            return TransitionResult.NotFound
        }

        return when (val verdict = ClosureGate.evaluate(facts)) {
            ClosureVerdict.Satisfied -> apply(ctx, ticket, state)
            is ClosureVerdict.Unmet -> {
                recorder.record(ctx, gateRefused(ticket, verdict.requirements))
                TransitionResult.GateFailed(verdict.requirements)
            }
        }
    }

    private suspend fun apply(
        ctx: ActorContext,
        ticket: Ticket,
        state: TicketState,
    ): TransitionResult {
        val from = ticket.state.status
        val updated = writer.applyTransition(ticket.id, from, state)
        if (updated == null) {
            recorder.record(
                ctx,
                refused(ticket.projectId, ticket.key, state.status, MOVED_UNDER_US, ticket.id.value),
            )
            return TransitionResult.Refused(TransitionRefusal.ConcurrentlyChanged(from))
        }

        recorder.record(ctx, applied(ticket, updated))
        return TransitionResult.Transitioned(updated)
    }
}

private fun attempted(
    projectId: ProjectId,
    key: TicketKey,
    to: TicketStatus,
): AuditEntry =
    AuditEntry(
        action = TRANSITIONED,
        entityType = TICKET_ENTITY,
        projectId = projectId,
        diff = AuditDiff(before = mapOf("key" to key.rendered, "attempted_status" to to.name.lowercase())),
    )

/**
 * A refusal that is a value: nothing was mutated, and the transaction commits carrying this row
 * alone. A trail of successes cannot answer what an actor tried to do.
 */
private fun refused(
    projectId: ProjectId,
    key: TicketKey,
    to: TicketStatus,
    reason: String,
    entityId: Uuid? = null,
): AuditEntry =
    attempted(projectId, key, to).copy(
        entityId = entityId,
        outcome = AuditOutcome.FAILED,
        diff =
            AuditDiff(
                before = mapOf("key" to key.rendered, "attempted_status" to to.name.lowercase()),
                after = mapOf("refusal" to reason),
            ),
    )

private fun gateRefused(
    ticket: Ticket,
    unmet: UnmetClosureRequirements,
): AuditEntry =
    AuditEntry(
        action = TRANSITIONED,
        entityType = TICKET_ENTITY,
        entityId = ticket.id.value,
        projectId = ticket.projectId,
        outcome = AuditOutcome.FAILED,
        diff =
            AuditDiff(
                before =
                    mapOf(
                        "key" to ticket.key.rendered,
                        "status" to
                            ticket.state.status.name
                                .lowercase(),
                    ),
                after =
                    mapOf(
                        "refusal" to "closure_gate_failed",
                        "unmet_acceptance_criteria" to unmet.acceptanceCriteria.size.toString(),
                        "unresolved_blocking_findings" to unmet.unresolvedBlockingFindings.size.toString(),
                        "reviews" to unmet.reviews.name.lowercase(),
                    ),
            ),
    )

private fun applied(
    before: Ticket,
    after: Ticket,
): AuditEntry =
    AuditEntry(
        action = TRANSITIONED,
        entityType = TICKET_ENTITY,
        entityId = after.id.value,
        projectId = after.projectId,
        diff =
            AuditDiff(
                before =
                    mapOf(
                        "status" to
                            before.state.status.name
                                .lowercase(),
                    ),
                after =
                    mapOf(
                        "status" to
                            after.state.status.name
                                .lowercase(),
                        "resolution" to
                            after.state.resolution
                                ?.name
                                ?.lowercase(),
                    ),
            ),
    )
