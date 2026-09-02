package ai.nodera.application.ticket.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.permission.PermissionDecision
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.ticket.CreateTicketResult
import ai.nodera.application.ticket.TicketKeyAllocator
import ai.nodera.application.ticket.TicketWriter
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketDraft
import ai.nodera.domain.ticket.TicketKey

internal const val TICKET_ENTITY: String = "ticket"

private val CREATED = AuditAction("ticket.created")

/**
 * Files a ticket, allocating its permanent key inside the same transaction as the row and the audit
 * event. The key is allocated by [TicketKeyAllocator] and by nothing else — a number derived from
 * the tickets would be reissued the moment one closed.
 */
public class CreateTicket(
    private val permissions: PermissionService,
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val allocator: TicketKeyAllocator,
    private val tickets: TicketWriter,
) {
    public suspend fun create(
        ctx: ActorContext,
        projectId: ProjectId,
        draft: TicketDraft,
    ): CreateTicketResult =
        unitOfWork.inTransaction {
            when (permissions.require(ctx, projectId, Capability.TICKET_CREATE)) {
                is PermissionDecision.Denied -> {
                    recorder.recordDenied(ctx, attempted(projectId, draft), Capability.TICKET_CREATE)
                    CreateTicketResult.Denied(Capability.TICKET_CREATE)
                }

                PermissionDecision.Permitted -> {
                    val number = allocator.allocate(projectId, draft.prefix)
                    val ticket = tickets.create(projectId, TicketKey(draft.prefix, number), draft, ctx.actorId)
                    recorder.record(ctx, created(ticket))
                    CreateTicketResult.Created(ticket)
                }
            }
        }
}

/** No entity id: the refusal happened before a ticket existed, and inventing one would be a lie. */
private fun attempted(
    projectId: ProjectId,
    draft: TicketDraft,
): AuditEntry =
    AuditEntry(
        action = CREATED,
        entityType = TICKET_ENTITY,
        projectId = projectId,
        diff = AuditDiff(before = mapOf("prefix" to draft.prefix.value, "title" to draft.title)),
    )

private fun created(ticket: Ticket): AuditEntry =
    AuditEntry(
        action = CREATED,
        entityType = TICKET_ENTITY,
        entityId = ticket.id.value,
        projectId = ticket.projectId,
        diff =
            AuditDiff(
                after =
                    mapOf(
                        "key" to ticket.key.rendered,
                        "title" to ticket.title,
                        "priority" to ticket.priority.name.lowercase(),
                        "status" to
                            ticket.state.status.name
                                .lowercase(),
                    ),
            ),
    )
