package ai.nodera.domain.permission

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.project.ProjectId
import kotlin.time.Instant

/**
 * One verb added to, or taken away from, an actor's role defaults in one project.
 *
 * @param granted `true` adds the verb, and only if [grantedBy] still holds it at the moment of use.
 *   `false` is an explicit denial that overrides the role default — applied unconditionally, because
 *   requiring its grantor to still hold the verb would mean revoking a person's access silently
 *   *widens* what their agent may do.
 */
public data class CapabilityGrant(
    public val projectId: ProjectId,
    public val actorId: ActorId,
    public val capability: Capability,
    public val granted: Boolean,
    public val grantedBy: ActorId,
    public val expiresAt: Instant?,
) {
    /** Expiry is inclusive: a grant that expires exactly now is expired. */
    public fun isExpiredAt(instant: Instant): Boolean = expiresAt?.let { it <= instant } == true
}
