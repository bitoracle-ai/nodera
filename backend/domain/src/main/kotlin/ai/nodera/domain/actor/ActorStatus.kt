package ai.nodera.domain.actor

/**
 * Lifecycle state of an actor. An actor is never deleted (invariant A2) — deletion would orphan audit
 * rows, and an audit trail with holes is not an audit trail.
 *
 * Only [ACTIVE] carries capabilities. That is deliberate and it is part of attenuation, not merely of
 * authentication: suspending a person is the operational form of "Anna leaves the team", and every
 * agent holding permissions through her has to lose them in the same instant (invariant #4).
 */
public enum class ActorStatus {
    ACTIVE,
    SUSPENDED,
    RETIRED,
}
