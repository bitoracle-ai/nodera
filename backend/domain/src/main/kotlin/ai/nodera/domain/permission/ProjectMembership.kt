package ai.nodera.domain.permission

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.project.ProjectId
import kotlin.time.Instant

/**
 * One actor's role in one project, and who put it there.
 *
 * [grantedBy] is the edge the attenuation chain walks. Because the row is keyed
 * `(project_id, actor_id)` an actor has exactly one grantor per project, so the graph is functional
 * and every chain ends in one of three ways: a self-grant, a cycle, or an actor with no membership.
 * Only the first is a root — see `PermissionService`.
 */
public data class ProjectMembership(
    public val projectId: ProjectId,
    public val actorId: ActorId,
    public val role: ProjectRole,
    public val grantedBy: ActorId,
    public val expiresAt: Instant?,
) {
    /** Expiry is inclusive: a membership that expires exactly now is expired. */
    public fun isExpiredAt(instant: Instant): Boolean = expiresAt?.let { it <= instant } == true
}
