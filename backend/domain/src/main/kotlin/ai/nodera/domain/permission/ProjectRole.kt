package ai.nodera.domain.permission

/**
 * The ergonomic layer over capabilities. Declared in the order of the `project_role` enum in `V1`,
 * so the mapping to and from the column reads as one list — the ordinal carries no ranking, and
 * nothing here compares roles by it.
 */
public enum class ProjectRole {
    OWNER,
    MAINTAINER,
    CONTRIBUTOR,
    OBSERVER,
}

/**
 * Role to capabilities, as a pure function — no repository, no clock, no fixture, so the permission
 * engine can be exhaustive over it and every one of these sets is testable on its own.
 *
 * The sets are **monotone**: observer ⊂ contributor ⊂ maintainer ⊂ owner, asserted by a test. That is
 * not tidiness. Attenuation intersects a grantee's defaults with its grantor's effective set, so
 * non-monotone roles would let a higher role silently attenuate a lower one.
 *
 * Three deliberate departures from the table in `docs/DOMAIN_MODEL.md` § 4, which names the verbs
 * that *distinguish* the roles rather than the floor every member stands on:
 *
 *  * `project.read` and `actor.read` are on every role. An observer holding `ticket.read` but not
 *    `project.read` cannot reach the project the ticket is in, and cannot resolve a mention target.
 *  * `ticket.assign_self` is a contributor default — `docs/MCP.md` § 3.3: "Assigning to oneself needs
 *    `ticket.assign_self` only." A contributor who cannot pick up a ticket cannot do the thing the
 *    role exists for. Assigning someone else stays at maintainer, through `ticket.assign`.
 *  * `audit.read` is a maintainer default. § 4 does not place it and `audit_query` needs it;
 *    maintainer rather than observer keeps the trail from being a general read surface.
 */
public fun ProjectRole.defaultCapabilities(): Set<Capability> =
    when (this) {
        ProjectRole.OBSERVER -> OBSERVER_DEFAULTS
        ProjectRole.CONTRIBUTOR -> CONTRIBUTOR_DEFAULTS
        ProjectRole.MAINTAINER -> MAINTAINER_DEFAULTS
        ProjectRole.OWNER -> OWNER_DEFAULTS
    }

private val OBSERVER_DEFAULTS: Set<Capability> =
    setOf(
        Capability.PROJECT_READ,
        Capability.ACTOR_READ,
        Capability.TICKET_READ,
        Capability.COMMENT_READ,
    )

private val CONTRIBUTOR_DEFAULTS: Set<Capability> =
    OBSERVER_DEFAULTS +
        setOf(
            Capability.TICKET_CREATE,
            Capability.TICKET_UPDATE,
            Capability.TICKET_TRANSITION,
            Capability.TICKET_ASSIGN_SELF,
            Capability.COMMENT_CREATE,
        )

private val MAINTAINER_DEFAULTS: Set<Capability> =
    CONTRIBUTOR_DEFAULTS +
        setOf(
            Capability.TICKET_CLOSE,
            Capability.TICKET_ASSIGN,
            Capability.COMMENT_MODERATE,
            Capability.REVIEW_SUBMIT,
            Capability.AUDIT_READ,
        )

// "everything, including project.admin and member.grant" — expressed as the whole set rather than a
// list, so a capability added to the enum is an owner capability without a second edit that could be
// forgotten.
private val OWNER_DEFAULTS: Set<Capability> = Capability.entries.toSet()
