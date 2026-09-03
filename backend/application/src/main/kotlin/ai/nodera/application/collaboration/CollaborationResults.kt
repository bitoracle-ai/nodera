package ai.nodera.application.collaboration

import ai.nodera.domain.collaboration.Comment
import ai.nodera.domain.collaboration.Finding
import ai.nodera.domain.collaboration.Review
import ai.nodera.domain.collaboration.ReviewRefusal
import ai.nodera.domain.permission.Capability

/**
 * Expected outcomes as values, not exceptions (`skills/backend-kotlin.md` § Errors are values).
 *
 * Two distinctions run through all of these and are the reason there are so many cases.
 * [CommentResult.NotFound] and its siblings mean **nothing visible** — never "nothing there", which
 * an empty answer would also mean. And `AlreadyDeleted` / `AlreadyResolved` mean the row moved to
 * the target state under the caller: a different answer from not finding it, and the one a surface
 * must not turn into "no such comment".
 */
public sealed interface CommentResult {
    public data class Written(
        public val comment: Comment,
    ) : CommentResult

    /** The caller is not the author, and authorship is never rewritten (invariant CM1). */
    public data object NotAuthor : CommentResult

    public data object AlreadyDeleted : CommentResult

    /** V3's reply-scope trigger, as a value: a reply stays inside its own ticket's thread. */
    public data object ReplyToAnotherTicket : CommentResult

    public data object NotFound : CommentResult

    public data class Denied(
        public val capability: Capability,
    ) : CommentResult
}

public sealed interface CommentThreadResult {
    public data class Thread(
        public val comments: List<Comment>,
    ) : CommentThreadResult

    public data object NotFound : CommentThreadResult

    public data class Denied(
        public val capability: Capability,
    ) : CommentThreadResult
}

public sealed interface SubmitReviewResult {
    public data class Submitted(
        public val review: Review,
    ) : SubmitReviewResult

    public data class NotIndependent(
        public val refusal: ReviewRefusal,
    ) : SubmitReviewResult

    public data object NotFound : SubmitReviewResult

    public data class Denied(
        public val capability: Capability,
    ) : SubmitReviewResult
}

public sealed interface ResolveFindingResult {
    public data class Resolved(
        public val finding: Finding,
    ) : ResolveFindingResult

    public data object AlreadyResolved : ResolveFindingResult

    public data object NotFound : ResolveFindingResult

    public data class Denied(
        public val capability: Capability,
    ) : ResolveFindingResult
}

public sealed interface ReviewRecordResult {
    /** Every round, ascending. Nothing is collapsed — a contradicting round 2 leaves round 1 readable. */
    public data class Rounds(
        public val reviews: List<Review>,
    ) : ReviewRecordResult

    public data object NotFound : ReviewRecordResult

    public data class Denied(
        public val capability: Capability,
    ) : ReviewRecordResult
}
