package ai.nodera.domain.collaboration

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorSummary
import ai.nodera.domain.ticket.FindingSeverity
import ai.nodera.domain.ticket.TicketId
import kotlin.uuid.Uuid

@JvmInline
public value class ReviewId(
    public val value: Uuid,
)

@JvmInline
public value class FindingId(
    public val value: Uuid,
)

/** V3: `check (round > 0)`. Rounds are dense and per ticket, and a round is never withdrawn. */
@JvmInline
public value class ReviewRound(
    public val value: Int,
) {
    init {
        require(value > 0) { "a review round is numbered from 1" }
    }
}

public enum class ReviewVerdict {
    APPROVED,
    CHANGES_REQUIRED,
}

/** A finding as submitted. V3: `check (length(trim(title)) > 0)`. */
public data class FindingDraft(
    public val severity: FindingSeverity,
    public val title: String,
    public val detail: String = "",
) {
    init {
        require(title.isNotBlank()) { "a finding needs a title" }
    }
}

/**
 * A stored finding.
 *
 * `resolved` is the only thing about it that changes, in one direction, and it is what the closure
 * gate reads across every round (invariant #8).
 */
public data class Finding(
    public val id: FindingId,
    public val severity: FindingSeverity,
    public val title: String,
    public val detail: String,
    public val resolved: Boolean,
)

/**
 * One round of review.
 *
 * Rounds are append-only and are never collapsed to a current verdict (invariant #9 / R2): a round-2
 * verdict that contradicts round 1 leaves both readable, because the contradiction is the most
 * informative thing in the record.
 */
public data class Review(
    public val id: ReviewId,
    public val ticketId: TicketId,
    public val reviewer: ActorSummary,
    public val round: ReviewRound,
    public val verdict: ReviewVerdict,
    public val summary: String,
    public val findings: List<Finding>,
)

/** Why a review submission was refused. Named, because a surface has to explain the refusal. */
public sealed interface ReviewRefusal {
    public data object ReviewerIsAssignee : ReviewRefusal

    public data object ReviewerIsReporterOfUnassigned : ReviewRefusal
}

public sealed interface ReviewerCheck {
    public data object Independent : ReviewerCheck

    public data class Refused(
        public val refusal: ReviewRefusal,
    ) : ReviewerCheck
}

/**
 * Invariant #9 / R1, exactly as V3's `review_reviewer_is_independent` trigger states it.
 *
 * The asymmetry is deliberate and DB-01 pins it: with an assignee the reporter is not the author of
 * the work and is a legitimate reviewer; without one, the reporter is all there is. Restated here so
 * a refusal is a value a surface can render rather than a trigger's exception arriving two layers
 * down.
 *
 * It compares **identity**, never kind. An agent may review a person's work and the reverse; what is
 * refused is reviewing one's own (invariant #1).
 */
public fun reviewerIndependence(
    reviewer: ActorId,
    reporter: ActorId,
    assignee: ActorId?,
): ReviewerCheck =
    when {
        assignee != null && reviewer == assignee -> ReviewerCheck.Refused(ReviewRefusal.ReviewerIsAssignee)
        assignee == null && reviewer == reporter ->
            ReviewerCheck.Refused(ReviewRefusal.ReviewerIsReporterOfUnassigned)

        else -> ReviewerCheck.Independent
    }
