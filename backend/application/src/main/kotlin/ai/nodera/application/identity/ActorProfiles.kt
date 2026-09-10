package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorSummary

/**
 * One actor as a surface shows it, with the owner an agent is accountable to.
 *
 * @param owner present exactly when the actor has an `agent_actor` row — a property of which table
 *   holds it, never a branch on [ActorSummary.kind]. One level only: the full chain is a read of its
 *   own, and a recursive envelope would make every response's size depend on a delegation depth.
 */
public data class ActorProfile(
    public val actor: ActorSummary,
    public val owner: ActorSummary?,
)

/**
 * The display read [ActorDirectory] deliberately does not make.
 *
 * `ActorPrincipal` carries id, kind and status because that is everything *authentication* needs,
 * and a port that also returned a handle would invite a caller to authenticate in order to read one.
 * This is the other question, asked separately.
 */
public interface ActorProfiles {
    /** `null` when no such actor exists — including one that vanished after it authenticated. */
    public suspend fun profile(actorId: ActorId): ActorProfile?
}
