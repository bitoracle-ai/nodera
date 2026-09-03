package ai.nodera.domain.actor

/**
 * Who wrote or reviewed something, as a reader is shown it.
 *
 * [kind] is carried so the reader is **told** whether an author is a person or an agent rather than
 * left to infer it from the text (invariant CM2). It is display, never a decision: nothing may branch
 * on it to choose how a comment or a review is stored, threaded or returned (invariant #1).
 */
public data class ActorSummary(
    public val id: ActorId,
    public val handle: Handle,
    public val kind: ActorKind,
    public val displayName: DisplayName,
)
