package ai.nodera.domain.actor

import kotlin.time.Instant

/**
 * The only participant type. There is no user.
 *
 * The two subtypes exist because the two tables hold genuinely different columns — an address on one
 * side, an accountable owner on the other — not because the two are treated differently. **Nothing
 * outside display and audit may branch on which subtype it has** (invariant #1);
 * `scripts/lint_invariants.py` scans for both the `kind` comparison and the `is HumanActor` /
 * `is AgentActor` form of the same branch.
 */
public sealed interface Actor {
    public val id: ActorId

    public val kind: ActorKind

    public val handle: Handle

    public val displayName: DisplayName

    public val status: ActorStatus
}

/** A person. Authenticates through OIDC, or local email plus a one-time code. */
public data class HumanActor(
    override val id: ActorId,
    override val handle: Handle,
    override val displayName: DisplayName,
    override val status: ActorStatus,
    public val email: Email,
    public val locale: String,
    public val timezone: String,
) : Actor {
    override val kind: ActorKind get() = ActorKind.HUMAN
}

/**
 * An agent. Authenticates as itself, with its own credential and its own grants.
 *
 * @param ownerActorId the accountable owner (invariant A4). An agent may own another agent, but the
 *   chain terminates at a human within a bounded number of hops — enforced by a trigger in `V1`.
 *   "The agent did it" is not an answer to an incident; "the agent Anna runs did it, with the grants
 *   Anna gave it" is.
 * @param runtimeHint descriptive only. Invariant A5: no code path may branch on it. The moment one
 *   does, Nodera has a model-provider integration, which the scope fence forbids.
 */
public data class AgentActor(
    override val id: ActorId,
    override val handle: Handle,
    override val displayName: DisplayName,
    override val status: ActorStatus,
    public val ownerActorId: ActorId,
    public val runtimeHint: String?,
    public val contactUrl: String?,
    public val retiredAt: Instant?,
) : Actor {
    override val kind: ActorKind get() = ActorKind.AGENT
}
