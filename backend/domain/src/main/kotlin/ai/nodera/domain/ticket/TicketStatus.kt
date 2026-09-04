package ai.nodera.domain.ticket

public enum class TicketStatus {
    OPEN,
    IN_PROGRESS,
    IN_REVIEW,
    BLOCKED,
    CLOSED,
}

public enum class TicketResolution {
    DONE,
    WONT_DO,
    DUPLICATE,
    SUPERSEDED,
}

/**
 * Status and resolution as one value, so V2's `resolution_iff_closed` cannot be broken in memory and
 * discovered at the insert.
 */
public data class TicketState(
    public val status: TicketStatus,
    public val resolution: TicketResolution? = null,
) {
    init {
        require((status == TicketStatus.CLOSED) == (resolution != null)) {
            "a resolution exists exactly when the ticket is closed"
        }
    }
}

/** Why a transition was refused. Named cases, because a surface has to explain the refusal. */
public sealed interface TransitionRefusal {
    public data class UnknownEdge(
        public val from: TicketStatus,
        public val to: TicketStatus,
    ) : TransitionRefusal

    public data object ResolutionRequired : TransitionRefusal

    public data class ResolutionNotAllowed(
        public val to: TicketStatus,
    ) : TransitionRefusal

    public data class ResolutionNotPermittedFrom(
        public val from: TicketStatus,
        public val resolution: TicketResolution,
    ) : TransitionRefusal

    /** The ticket left [expected] while this transition was being decided. Retrying is the answer. */
    public data class ConcurrentlyChanged(
        public val expected: TicketStatus,
    ) : TransitionRefusal
}

/**
 * The three answers the state machine can give.
 *
 * [PermittedIfClosureGatePasses] is a separate case rather than a flag so that a caller's `when` has
 * to handle it: running the gate becomes a compile obligation instead of something review has to
 * notice was skipped (invariant #8).
 */
public sealed interface TransitionOutcome {
    public data object Permitted : TransitionOutcome

    public data object PermittedIfClosureGatePasses : TransitionOutcome

    public data class Refused(
        public val refusal: TransitionRefusal,
    ) : TransitionOutcome
}

// docs/DOMAIN_MODEL.md § 5.1. `in_review -> in_progress` is absent because the specified machine does
// not carry it; the path runs through `open`.
private val EDGES: Map<TicketStatus, Set<TicketStatus>> =
    mapOf(
        TicketStatus.OPEN to setOf(TicketStatus.IN_PROGRESS, TicketStatus.CLOSED),
        TicketStatus.IN_PROGRESS to setOf(TicketStatus.IN_REVIEW, TicketStatus.OPEN, TicketStatus.BLOCKED),
        TicketStatus.IN_REVIEW to setOf(TicketStatus.CLOSED, TicketStatus.OPEN, TicketStatus.BLOCKED),
        TicketStatus.BLOCKED to setOf(TicketStatus.OPEN, TicketStatus.CLOSED),
        TicketStatus.CLOSED to emptySet(),
    )

// Which resolutions each closing edge accepts (docs/DOMAIN_MODEL.md § 5.1). No row: no resolution.
private val CLOSING_RESOLUTIONS: Map<TicketStatus, Set<TicketResolution>> =
    mapOf(
        TicketStatus.IN_REVIEW to TicketResolution.entries.toSet(),
        TicketStatus.BLOCKED to setOf(TicketResolution.WONT_DO),
        TicketStatus.OPEN to TicketResolution.entries.toSet() - TicketResolution.DONE,
    )

/** The pure transition function. It decides nothing about permissions and reads no actor. */
public fun transition(
    from: TicketStatus,
    to: TicketStatus,
    resolution: TicketResolution?,
): TransitionOutcome =
    when {
        to !in EDGES.getValue(from) -> TransitionOutcome.Refused(TransitionRefusal.UnknownEdge(from, to))
        to == TicketStatus.CLOSED -> closing(from, resolution)
        resolution != null -> TransitionOutcome.Refused(TransitionRefusal.ResolutionNotAllowed(to))
        else -> TransitionOutcome.Permitted
    }

private fun closing(
    from: TicketStatus,
    resolution: TicketResolution?,
): TransitionOutcome =
    when {
        resolution == null -> TransitionOutcome.Refused(TransitionRefusal.ResolutionRequired)
        resolution !in CLOSING_RESOLUTIONS[from].orEmpty() ->
            TransitionOutcome.Refused(TransitionRefusal.ResolutionNotPermittedFrom(from, resolution))

        resolution == TicketResolution.DONE -> TransitionOutcome.PermittedIfClosureGatePasses
        else -> TransitionOutcome.Permitted
    }
