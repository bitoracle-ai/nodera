package ai.nodera.application.ticket

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.ClosureFacts
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketCandidate
import ai.nodera.domain.ticket.TicketDraft
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.domain.ticket.TicketNumber
import ai.nodera.domain.ticket.TicketPrefix
import ai.nodera.domain.ticket.TicketState
import ai.nodera.domain.ticket.TicketStatus

/**
 * The next number in one project's prefix sequence.
 *
 * The implementation locks the sequence row, and it never derives the number from the tickets:
 * a key is permanent and is never reused, including after a ticket closes as `wont_do` or
 * `duplicate` (invariant #10).
 */
public interface TicketKeyAllocator {
    public suspend fun allocate(
        projectId: ProjectId,
        prefix: TicketPrefix,
    ): TicketNumber
}

public interface TicketReader {
    /** `null` when no such ticket is visible to the caller — never an empty stand-in. */
    public suspend fun byKey(
        projectId: ProjectId,
        key: TicketKey,
    ): Ticket?
}

public interface TicketWriter {
    public suspend fun create(
        projectId: ProjectId,
        key: TicketKey,
        draft: TicketDraft,
        reporter: ActorId,
    ): Ticket

    /**
     * Moves the ticket from [from] to [to], and returns `null` when it is no longer in [from].
     *
     * The expected status is part of the write because the decision to allow this transition was
     * made against a row that was read earlier: two callers that both read `in_review` would
     * otherwise both be permitted, and the later write would land a status the state machine never
     * allows from the status the row actually had.
     */
    public suspend fun applyTransition(
        id: TicketId,
        from: TicketStatus,
        to: TicketState,
    ): Ticket?
}

/**
 * What the closure gate reads.
 *
 * `null` means **the ticket was not visible**, and it exists so that an unscoped read cannot be
 * mistaken for a ticket with nothing outstanding. Every field of [ClosureFacts] is project-scoped,
 * row-level security answers an unscoped query with zero rows, and zero unmet requirements is
 * exactly what "satisfied" looks like.
 */
public interface ClosureFactsReader {
    public suspend fun facts(ticketId: TicketId): ClosureFacts?
}

/** Every ticket of one project, with its dependency edges — the input to the working order. */
public interface TicketCandidateReader {
    public suspend fun candidates(projectId: ProjectId): List<TicketCandidate>
}
