package ai.nodera.domain.ticket

import ai.nodera.domain.actor.ActorId
import kotlin.time.Instant

/** One ticket of a project as the working order sees it, with the edges it depends on. */
public data class TicketCandidate(
    public val id: TicketId,
    public val key: TicketKey,
    public val priority: TicketPriority,
    public val status: TicketStatus,
    public val assignee: ActorId?,
    public val createdAt: Instant,
    public val dependsOn: Set<TicketId>,
)

/** Why this ticket came first. `docs/MCP.md` § 3.2: `ticket_next` returns the reason with the ticket. */
public enum class SelectionReason {
    ALREADY_ASSIGNED_TO_YOU,
    NEXT_BY_PRIORITY,
}

public data class ReadyTicket(
    public val candidate: TicketCandidate,
    public val reason: SelectionReason,
)

/**
 * The working order, as a pure function over one project's tickets.
 *
 * It reads actor **identity** to honour an existing assignment, and never actor kind: what an actor
 * is has no bearing on what it may pick up (invariant #1).
 *
 * A dependency counts as satisfied when the ticket it points at is `closed`, whatever the
 * resolution. Requiring `done` would leave a ticket unstartable for good the moment a dependency is
 * abandoned as `wont_do`, and the graph is a record rather than something to edit around. A
 * dependency on an id that is not among the candidates counts as **unsatisfied**: an id nobody
 * returned is unknown, and unknown is not the same as done.
 */
public object WorkingOrder {
    public fun next(
        candidates: List<TicketCandidate>,
        forActor: ActorId,
    ): ReadyTicket? = order(candidates, forActor).firstOrNull()

    public fun order(
        candidates: List<TicketCandidate>,
        forActor: ActorId,
    ): List<ReadyTicket> {
        val closed = candidates.filter { it.status == TicketStatus.CLOSED }.mapTo(mutableSetOf()) { it.id }

        return candidates
            .filter { it.status == TicketStatus.OPEN }
            .filter { it.assignee == null || it.assignee == forActor }
            .filter { candidate -> candidate.dependsOn.all { it in closed } }
            .sortedWith(
                compareBy<TicketCandidate> { if (it.assignee == forActor) 0 else 1 }
                    .thenBy { it.priority.ordinal }
                    .thenBy { it.createdAt }
                    .thenBy { it.key.prefix.value }
                    .thenBy { it.key.number.value },
            ).map { ReadyTicket(it, reasonFor(it, forActor)) }
    }

    private fun reasonFor(
        candidate: TicketCandidate,
        forActor: ActorId,
    ): SelectionReason =
        if (candidate.assignee == forActor) {
            SelectionReason.ALREADY_ASSIGNED_TO_YOU
        } else {
            SelectionReason.NEXT_BY_PRIORITY
        }
}
