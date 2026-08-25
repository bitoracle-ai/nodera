package ai.nodera.application.permission

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.permission.CapabilityGrant
import ai.nodera.domain.permission.ProjectMembership
import ai.nodera.domain.project.ProjectId

/**
 * The three reads [PermissionService] makes, and nothing else.
 *
 * A port is defined by the use case that needs it, not by what the adapter could offer — a single fat
 * repository is the interface every test has to stub and every change touches. Splitting a fat port
 * later is a refactor across every implementation; declaring a narrow one costs nothing now.
 *
 * **It returns rows as they are stored, not "active" ones.** Expiry is evaluated in the service
 * against an injected clock, so every temporal rule is testable without a database — which is the
 * property that makes the permission algebra provable at all. A port that pre-filtered by `now()`
 * would move an invariant into the adapter and take it out of reach of the tests that guard it.
 */
public interface PermissionDirectory {
    /** The actor's membership in this project, or `null` if it has none. */
    public suspend fun membership(
        projectId: ProjectId,
        actorId: ActorId,
    ): ProjectMembership?

    /** Every grant and denial recorded for this actor in this project, expired ones included. */
    public suspend fun capabilityGrants(
        projectId: ProjectId,
        actorId: ActorId,
    ): List<CapabilityGrant>

    /** The actor's lifecycle state, or `null` if no such actor exists. */
    public suspend fun actorStatus(actorId: ActorId): ActorStatus?
}
