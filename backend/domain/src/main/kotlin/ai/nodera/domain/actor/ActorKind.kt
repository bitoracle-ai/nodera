package ai.nodera.domain.actor

/**
 * Whether an actor is a person or an agent.
 *
 * **This value is for display and audit. It never decides what is permitted** — invariant #1, the one
 * the product rests on. Ask [ai.nodera.domain.permission.Capability] instead; if an agent genuinely
 * must not do something in a project, that is a grant, and it is expressed as one.
 *
 * Immutable after insert (invariant A1, enforced by a trigger in `V1`): a mutable kind would silently
 * rewrite the meaning of every historical audit row that references the actor.
 */
public enum class ActorKind {
    HUMAN,
    AGENT,
}
