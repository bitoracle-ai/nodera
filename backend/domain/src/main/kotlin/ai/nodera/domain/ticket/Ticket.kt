package ai.nodera.domain.ticket

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.project.ProjectId

/** One ticket, as the use cases hand it back. */
public data class Ticket(
    public val id: TicketId,
    public val projectId: ProjectId,
    public val key: TicketKey,
    public val title: String,
    public val priority: TicketPriority,
    public val state: TicketState,
    public val reporter: ActorId,
    public val assignee: ActorId?,
)
