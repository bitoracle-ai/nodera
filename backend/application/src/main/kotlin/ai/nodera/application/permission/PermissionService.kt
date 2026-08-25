package ai.nodera.application.permission

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.permission.CapabilityGrant
import ai.nodera.domain.permission.defaultCapabilities
import ai.nodera.domain.project.ProjectId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The bound on chain *depth*, matching the one `V1`'s `agent_owner_chain_is_valid` trigger applies to
 * agent ownership. A seventeen-deep delegation chain is a mistake, and beyond it an actor contributes
 * nothing — the fail-closed direction.
 */
private const val MAX_CHAIN_HOPS = 16

private val NO_CAPABILITIES: Set<Capability> = emptySet()

/**
 * The one permission engine (invariant #2).
 *
 * REST and MCP call **this object**, not the same logic implemented twice. There is no MCP-specific
 * shortcut, no trusted-internal caller and no bypass flag: two engines drift, and the one that drifts
 * is always the one with fewer readers — which here is the surface agents use.
 *
 * Nothing in this file reads an actor's kind. The question is always about a capability.
 *
 * Constructed once, in the composition root; `scripts/lint_invariants.py` fails the build on a
 * `PermissionService(` anywhere else.
 */
public class PermissionService(
    private val directory: PermissionDirectory,
    private val clock: Clock,
) {
    /**
     * Every capability [actorId] actually holds in [projectId], resolved **now**.
     *
     * Attenuation is re-checked here rather than trusted from grant time (invariant #4). That timing
     * is the whole invariant: if a person's grants are revoked, the agents they configured lose the
     * same access in the same instant. Checking only at grant time leaves those agents running with
     * permissions their grantor no longer has — the "agent borrowed a human's identity" failure this
     * product exists to fix, reintroduced one layer down.
     *
     * Takes an [ActorId] rather than an [ActorContext] because it is not a use case: it is the
     * engine's own query, and `whoami` asks it about the caller's own id. Use cases take an
     * [ActorContext] first, always.
     */
    public suspend fun effectiveCapabilities(
        actorId: ActorId,
        projectId: ProjectId,
    ): Set<Capability> = solve(grantorClosure(actorId, projectId, clock.now()))[actorId] ?: NO_CAPABILITIES

    /** Whether the acting actor may do one specific thing in one specific project. */
    public suspend fun require(
        ctx: ActorContext,
        projectId: ProjectId,
        capability: Capability,
    ): PermissionDecision =
        if (capability in effectiveCapabilities(ctx.actorId, projectId)) {
            PermissionDecision.Permitted
        } else {
            PermissionDecision.Denied(ctx.actorId, projectId, capability)
        }

    /**
     * Reads every actor whose authority the subject's answer can depend on, breadth-first.
     *
     * Each actor is read **exactly once**, so the cost is the size of the grantor closure rather than
     * the number of paths through it. That distinction is why this is not a recursive walk: an actor
     * may hold one grant per capability, so a naive walk branches up to seventeen ways per hop, and
     * sixteen hops of that does not return.
     *
     * There is deliberately **no cap on the number of actors**. A cap has to choose which actors to
     * drop, which makes the answer depend on rows unrelated to the question — and then removing
     * authority from one grantor frees room for another, so a *break* can hand an actor a capability
     * it did not have before. Cost belongs to the adapter instead: this closure is one recursive
     * query in SQL, and DB-01 owns that shape.
     *
     * Actors beyond [MAX_CHAIN_HOPS] are never read, so they contribute the empty set.
     */
    private suspend fun grantorClosure(
        subject: ActorId,
        projectId: ProjectId,
        now: Instant,
    ): Map<ActorId, Grantee> {
        val closure = mutableMapOf<ActorId, Grantee>()
        var frontier = setOf(subject)
        var depth = 0

        while (frontier.isNotEmpty() && depth <= MAX_CHAIN_HOPS) {
            val next = mutableSetOf<ActorId>()
            for (actorId in frontier) {
                val grantee = read(actorId, projectId, now)
                closure[actorId] = grantee
                next += grantee.grantors()
            }
            frontier = next - closure.keys
            depth += 1
        }
        return closure
    }

    /** One actor's rows, as the engine needs them. Anything unusable collapses to [Grantee.NONE]. */
    private suspend fun read(
        actorId: ActorId,
        projectId: ProjectId,
        now: Instant,
    ): Grantee {
        val membership = directory.membership(projectId, actorId)
        if (membership == null ||
            membership.isExpiredAt(now) ||
            directory.actorStatus(actorId) != ActorStatus.ACTIVE
        ) {
            return Grantee.NONE
        }

        val (additions, denials) =
            directory
                .capabilityGrants(projectId, actorId)
                .filterNot { it.isExpiredAt(now) }
                .partition { it.granted }

        return Grantee(
            base = membership.role.defaultCapabilities(),
            isRoot = membership.grantedBy == actorId,
            // Null means self-granted, and a self-granted MEMBERSHIP is the root of the chain:
            // founding a project is not delegated authority, and there is nothing above it in the
            // project to attenuate against. A cycle of length two or more is the opposite — two
            // actors each drawing authority from the other, with no independent root — and it gets
            // nothing, because the solver starts everyone at the empty set and a cycle can never
            // lift itself off it. The asymmetry is deliberate: one is the founding act, the other is
            // laundering.
            //
            // The obligation this creates lives on the WRITE side and is stated here because there
            // is nowhere else it could be inherited from: one row —
            // `project_membership(role='owner', granted_by_actor_id = <self>)` — is total,
            // unattenuated authority in a project. Only project creation may write a self-granted
            // membership. Every other path must refuse `grantedBy == actorId`, and CORE-03, which
            // writes memberships and grants, owns that refusal.
            grantedBy = membership.grantedBy,
            additions = additions,
            denials = denials.map { it.capability }.toSet(),
        )
    }
}

/**
 * The **least** fixed point of [step] over the closure, starting from nobody holding anything.
 *
 * Least, and computed rather than walked, is what buys the two properties this engine has to have.
 *
 *  * **Nothing is granted that cannot be traced back to a root.** Every capability enters through a
 *    self-granted membership and reaches an actor only by surviving an intersection at every hop. A
 *    cycle starts at the empty set and stays there, so no group of actors can vouch for each other
 *    into existence.
 *  * **Removing authority can only remove capability.** [step] is monotone in its second argument, so
 *    a directory that returns fewer rows of *authority* — a deleted membership, a deleted positive
 *    grant, a demoted role, a suspended actor — yields a pointwise smaller fixed point. This is the
 *    property a traversal order or a work budget destroys, and the reason there is neither here: with
 *    either, whether a grantor is reached depends on unrelated rows, and a break elsewhere in the
 *    graph can *add* a capability.
 *
 *    Deleting a **denial** row is the one removal that widens the result, and it is not a
 *    counterexample: a denial is a restriction, not authority. Note that revoking a verb in this
 *    model means *adding* a denial row, so a revocation is never a removal.
 *
 * The iterations are pure: the directory has already been read, so this is arithmetic on sets.
 */
private fun solve(closure: Map<ActorId, Grantee>): Map<ActorId, Set<Capability>> {
    var current: Map<ActorId, Set<Capability>> = closure.keys.associateWith { NO_CAPABILITIES }

    // Each round that is not already stable adds at least one (actor, capability) pair, and the
    // sequence never shrinks — `step` is monotone and this starts from the bottom. So the pairs
    // themselves bound the round count, and that bound is a fact about the lattice rather than a
    // tuning constant somebody has to guess.
    repeat(closure.size * Capability.entries.size + 1) {
        val next = closure.mapValues { (_, grantee) -> step(grantee, current) }
        if (next == current) return current
        current = next
    }
    return current
}

/**
 * One actor's capabilities, given what every actor holds in the current approximation.
 *
 * A **self-granted capability** needs no special case. Round one has the actor at the empty set, so
 * the verb is not added; from round two the actor's own set is whatever it already legitimately
 * holds, so a self-grant can only re-state something and never introduce it. That is invariant C3's
 * first half — an agent cannot grant itself more than it holds — by construction rather than by an
 * `if`. C3's second half, that an agent may not grant `member.grant` at all, is a rule about *making*
 * a grant and belongs to the use case that makes them (CORE-03).
 *
 * Denials are applied last and unconditionally. Requiring a denial's grantor to still hold the verb
 * would mean revoking a person's access silently *widens* what their agent may do. `capability_grant`
 * is unique on `(project_id, actor_id, capability)`, so a verb has one row and the two orders coincide
 * in every state the schema permits; last is the fail-closed choice for a directory that returns both
 * anyway, which the port's type allows and a test exercises.
 */
private fun step(
    grantee: Grantee,
    current: Map<ActorId, Set<Capability>>,
): Set<Capability> {
    // The `?: NO_CAPABILITIES` has no observable effect today and is not claimed to: the only
    // Grantee with no grantor is NONE, whose base is empty, so every ceiling yields the same answer.
    // It is written fail-closed for the case that stops being true — a Grantee that keeps a role for
    // an actor with no usable membership would otherwise become an unattenuated root.
    val ceiling =
        if (grantee.isRoot) {
            grantee.base
        } else {
            grantee.grantedBy?.let { current[it].orEmpty() } ?: NO_CAPABILITIES
        }
    var capabilities = grantee.base intersect ceiling

    for (grant in grantee.additions) {
        if (grant.capability in current[grant.grantedBy].orEmpty()) {
            capabilities = capabilities + grant.capability
        }
    }
    return capabilities - grantee.denials
}

/**
 * One actor's authority, as rows rather than as an answer.
 *
 * @param isRoot the membership is self-granted, so there is nothing above it to attenuate against.
 *   A separate flag rather than a null [grantedBy], because "the founding act" and "no usable
 *   membership" must not share a representation: [NONE] is safe only through its empty [base], and a
 *   later change that kept a role for an unusable membership would silently turn it into an
 *   unattenuated root.
 * @param grantedBy the membership's grantor. Equal to the actor itself exactly when [isRoot].
 */
private class Grantee(
    val base: Set<Capability>,
    val isRoot: Boolean,
    val grantedBy: ActorId?,
    val additions: List<CapabilityGrant>,
    val denials: Set<Capability>,
) {
    /** Whose answers this actor's own answer depends on. Denials need none: they apply regardless. */
    fun grantors(): Set<ActorId> = setOfNotNull(grantedBy) + additions.map { it.grantedBy }

    companion object {
        /** No membership, an expired one, or an actor that is not active. Holds nothing, ever. */
        val NONE =
            Grantee(
                base = NO_CAPABILITIES,
                isRoot = false,
                grantedBy = null,
                additions = emptyList(),
                denials = emptySet(),
            )
    }
}
