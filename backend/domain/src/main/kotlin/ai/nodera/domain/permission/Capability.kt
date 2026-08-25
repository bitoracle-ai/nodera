package ai.nodera.domain.permission

/**
 * A single verb the permission engine checks. **The only question any code path may ask before
 * deciding what is allowed** — never who the actor is, never what kind it is (invariant #1).
 *
 * An enum with a verb string rather than a sealed hierarchy: `capability_grant.capability` is `text`
 * with `check (capability ~ '^[a-z_]+\.[a-z_]+$')`, deliberately not a Postgres enum so adding a verb
 * never needs a type migration that locks the table. The authoritative list is therefore this file,
 * and the mapping to and from the column has to be trivial in both directions.
 *
 * The verbs come from `docs/DOMAIN_MODEL.md` § 4 and the tool table in `docs/MCP.md` § 3, which is
 * normative: it states the capability each tool passes to the check.
 */
public enum class Capability(
    public val verb: String,
) {
    PROJECT_READ("project.read"),
    PROJECT_ADMIN("project.admin"),
    MEMBER_GRANT("member.grant"),
    ACTOR_READ("actor.read"),
    AUDIT_READ("audit.read"),

    TICKET_READ("ticket.read"),
    TICKET_CREATE("ticket.create"),
    TICKET_UPDATE("ticket.update"),
    TICKET_TRANSITION("ticket.transition"),
    TICKET_CLOSE("ticket.close"),
    TICKET_ASSIGN("ticket.assign"),
    TICKET_ASSIGN_SELF("ticket.assign_self"),

    COMMENT_READ("comment.read"),
    COMMENT_CREATE("comment.create"),
    COMMENT_MODERATE("comment.moderate"),

    REVIEW_SUBMIT("review.submit"),
    ;

    public companion object {
        private val BY_VERB: Map<String, Capability> = entries.associateBy { it.verb }

        /**
         * The stored verb back to the capability, or `null` for a verb this build does not know.
         *
         * `null` rather than a throw, and never a permissive fallback: a row written by a newer
         * version simply grants nothing here, which is the fail-closed direction.
         */
        public fun fromVerb(verb: String): Capability? = BY_VERB[verb]
    }
}
