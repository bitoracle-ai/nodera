package ai.nodera.application.error

/**
 * The failure vocabulary both surfaces answer from, here rather than in an adapter because
 * `:api-mcp` is a sibling of `:api-rest` and cannot see it (ADR-0005).
 *
 * The strings are contract — `docs/API_CONTRACT.md` § 4 and `docs/MCP.md` § 9. What each becomes on
 * a wire is the adapter's.
 */
public enum class ErrorCode(
    public val wire: String,
) {
    /** Missing, malformed, expired or revoked credential. Never says which selector exists. */
    UNAUTHENTICATED("unauthenticated"),

    /** Authenticated, capability absent. Names the capability required. */
    FORBIDDEN("forbidden"),

    /** Absent, **or** invisible to this caller — indistinguishable on purpose. */
    NOT_FOUND("not_found"),

    VALIDATION_FAILED("validation_failed"),

    /** Carries the itemised list of what is unmet, so the caller can finish the work. */
    CLOSURE_GATE_FAILED("closure_gate_failed"),

    DEPENDENCY_CYCLE("dependency_cycle"),

    /** A key reused with different arguments: two intents, and only one of them happened. */
    IDEMPOTENCY_CONFLICT("idempotency_conflict"),

    RATE_LIMITED("rate_limited"),
}
