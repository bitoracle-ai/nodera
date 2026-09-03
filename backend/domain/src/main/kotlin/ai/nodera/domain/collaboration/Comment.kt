package ai.nodera.domain.collaboration

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorSummary
import ai.nodera.domain.ticket.TicketId
import kotlin.time.Instant
import kotlin.uuid.Uuid

@JvmInline
public value class CommentId(
    public val value: Uuid,
)

/**
 * A body as it will be stored: sanitised, and non-blank.
 *
 * The constructor is private and [of] is the only way in, so "the stored body has been through the
 * sanitiser" is a property of the type rather than of every caller's care. V3's
 * `body_present_unless_deleted` is restated on the sanitised text, because that is the text the
 * insert will carry.
 */
@JvmInline
public value class CommentBody private constructor(
    public val value: String,
) {
    public companion object {
        public fun of(raw: String): CommentBody {
            val sanitised = sanitiseMarkdown(raw)
            require(sanitised.isNotBlank()) { "a comment body must not be blank" }
            return CommentBody(sanitised)
        }
    }
}

/**
 * What a comment holds now.
 *
 * A tombstone carries no body and always names its deleter, which is V3's `deletion_carries_actor`
 * expressed as a type: the pair cannot be half-set in memory and refused at the statement, and a
 * caller rendering a thread cannot read a body that deletion was supposed to remove.
 */
public sealed interface CommentContent {
    public data class Visible(
        public val body: CommentBody,
        public val editedAt: Instant?,
    ) : CommentContent

    /** Invariant CM1: the row and its position in the thread survive; only the body goes. */
    public data class Tombstone(
        public val deletedAt: Instant,
        public val deletedBy: ActorId,
    ) : CommentContent
}

/**
 * One comment, human-authored or agent-authored without distinction (invariant CM2).
 *
 * [author] carries the kind so a reader is told it; nothing on the write, thread or read path asks
 * what it is.
 */
public data class Comment(
    public val id: CommentId,
    public val ticketId: TicketId,
    public val author: ActorSummary,
    public val content: CommentContent,
    public val inReplyTo: CommentId?,
    public val createdAt: Instant,
)
