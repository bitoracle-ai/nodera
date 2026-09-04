package ai.nodera.application.collaboration

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.Handle
import ai.nodera.domain.collaboration.Comment
import ai.nodera.domain.collaboration.CommentBody
import ai.nodera.domain.collaboration.CommentId
import ai.nodera.domain.collaboration.Finding
import ai.nodera.domain.collaboration.FindingDraft
import ai.nodera.domain.collaboration.FindingId
import ai.nodera.domain.collaboration.Review
import ai.nodera.domain.collaboration.ReviewRound
import ai.nodera.domain.collaboration.ReviewVerdict
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketKey

/**
 * Handles to the actors they name, **inside one project**.
 *
 * Scoped because a mention drives notification: naming an actor who cannot open the ticket notifies
 * a stranger and leaves a row no reader of that comment can resolve. A handle that matches nobody
 * simply does not appear in the answer — there is no row type for a mention without an actor, and
 * text that looks like a handle is not an error.
 */
public interface MentionDirectory {
    public suspend fun resolve(
        projectId: ProjectId,
        handles: List<Handle>,
    ): List<ActorId>
}

public interface CommentReader {
    /**
     * One comment, **scoped to [projectId]**, or `null` when none is visible there.
     *
     * The scope is the guard rather than an optimisation: row-level security admits every project in
     * the caller's session while the permission check is against one of them, so an unscoped read by
     * id would let a caller permitted in one project reach a comment in another (invariant #5).
     */
    public suspend fun byId(
        projectId: ProjectId,
        id: CommentId,
    ): Comment?

    /**
     * The thread, oldest first, or `null` when **the ticket itself is not visible**.
     *
     * Nullable rather than empty, and for the reason CORE-03 made `ClosureFacts` nullable: a ticket
     * nobody can read answers with zero rows, and zero comments is exactly what a ticket with no
     * discussion looks like.
     */
    public suspend fun thread(
        projectId: ProjectId,
        key: TicketKey,
    ): List<Comment>?
}

public interface CommentWriter {
    public suspend fun create(
        ticketId: TicketId,
        author: ActorId,
        body: CommentBody,
        inReplyTo: CommentId?,
    ): Comment

    public suspend fun mention(
        commentId: CommentId,
        actors: List<ActorId>,
    )

    /** `null` when the comment has already been deleted — a tombstone is not edited back into a comment. */
    public suspend fun edit(
        id: CommentId,
        body: CommentBody,
    ): Comment?

    /**
     * Blanks the body and stamps the deletion, and returns `null` when it was already deleted.
     *
     * Conditional so a second deleter cannot overwrite the first's identity and timestamp, which
     * would leave the trail naming the wrong actor.
     */
    public suspend fun tombstone(
        id: CommentId,
        deletedBy: ActorId,
    ): Comment?
}

/**
 * The round this submission gets.
 *
 * `review` is `unique (ticket_id, round)`, so two submissions that both compute `max + 1` collide.
 * The implementation locks the ticket row before it reads, which is also what serialises a
 * submission against a closure gate evaluating the same ticket (`docs/plan/CORE-04.md` § 4.4).
 */
public interface ReviewRoundAllocator {
    public suspend fun nextRound(ticketId: TicketId): ReviewRound
}

public interface ReviewWriter {
    public suspend fun submit(
        ticketId: TicketId,
        reviewer: ActorId,
        round: ReviewRound,
        verdict: ReviewVerdict,
        summary: String,
        findings: List<FindingDraft>,
    ): Review

    /** `null` when the finding was already resolved, so the first resolver's note and identity stand. */
    public suspend fun resolve(
        id: FindingId,
        resolvedBy: ActorId,
        note: String,
    ): Finding?
}

public interface ReviewReader {
    /** Every round in ascending order, or `null` when the ticket is not visible. Never a latest-only view. */
    public suspend fun rounds(
        projectId: ProjectId,
        key: TicketKey,
    ): List<Review>?

    /** One finding, scoped to [projectId] for the reason [CommentReader.byId] is. */
    public suspend fun findingById(
        projectId: ProjectId,
        id: FindingId,
    ): Finding?
}
