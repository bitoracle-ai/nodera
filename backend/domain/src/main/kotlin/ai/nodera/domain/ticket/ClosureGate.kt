package ai.nodera.domain.ticket

import kotlin.uuid.Uuid

public data class AcceptanceCriterion(
    public val ordinal: Int,
    public val text: String,
    public val met: Boolean,
)

public enum class FindingSeverity {
    BLOCKING,
    NON_BLOCKING,
}

public data class ReviewFinding(
    public val id: Uuid,
    public val title: String,
    public val severity: FindingSeverity,
    public val resolved: Boolean,
)

/**
 * Everything the gate reads, as rows rather than as an answer.
 *
 * Obtained only for a ticket that was actually read: an adapter returns `null` for a ticket it
 * cannot see rather than an empty [ClosureFacts], because every field here is a project-scoped read
 * and row-level security answers an unscoped one with zero rows (invariant #5). Zero unmet criteria
 * and zero unresolved findings would then read as *satisfied* — a gate that fails open.
 */
public data class ClosureFacts(
    public val criteria: List<AcceptanceCriterion>,
    public val findings: List<ReviewFinding>,
    public val reviewCount: Int,
) {
    init {
        require(reviewCount >= 0) { "review count cannot be negative" }
    }
}

/** Whether the ticket has been reviewed at all. A word, because that is what the MCP shape renders. */
public enum class ReviewRequirement {
    PRESENT,
    ABSENT,
}

/**
 * What is missing, itemised — the shape `docs/MCP.md` § 4 returns.
 *
 * It cannot be constructed with nothing unmet: a refusal that names nothing is the boolean this
 * type exists to replace, and it is the one thing the consumer cannot render.
 */
public data class UnmetClosureRequirements(
    public val acceptanceCriteria: List<AcceptanceCriterion>,
    public val unresolvedBlockingFindings: List<ReviewFinding>,
    public val reviews: ReviewRequirement,
) {
    init {
        require(acceptanceCriteria.none { it.met }) { "a met criterion is not an unmet requirement" }
        require(unresolvedBlockingFindings.all { it.severity == FindingSeverity.BLOCKING && !it.resolved }) {
            "only unresolved blocking findings hold closure"
        }
        require(
            acceptanceCriteria.isNotEmpty() ||
                unresolvedBlockingFindings.isNotEmpty() ||
                reviews == ReviewRequirement.ABSENT,
        ) { "a refusal that names nothing is a boolean; the gate returns Satisfied instead" }
    }
}

public sealed interface ClosureVerdict {
    public data object Satisfied : ClosureVerdict

    public data class Unmet(
        public val requirements: UnmetClosureRequirements,
    ) : ClosureVerdict
}

/**
 * Invariant #8: closure is gated, not clicked, and the refusal is itemised.
 *
 * All three conditions are evaluated before the verdict is formed. Short-circuiting would name the
 * first missing item and hide the rest, which is what makes an agent retry instead of finish.
 */
public object ClosureGate {
    public fun evaluate(facts: ClosureFacts): ClosureVerdict {
        val unmetCriteria = facts.criteria.filterNot { it.met }
        val unresolvedBlocking =
            facts.findings.filter { it.severity == FindingSeverity.BLOCKING && !it.resolved }
        val reviews =
            if (facts.reviewCount > 0) ReviewRequirement.PRESENT else ReviewRequirement.ABSENT

        return if (unmetCriteria.isEmpty() && unresolvedBlocking.isEmpty() && reviews == ReviewRequirement.PRESENT) {
            ClosureVerdict.Satisfied
        } else {
            ClosureVerdict.Unmet(UnmetClosureRequirements(unmetCriteria, unresolvedBlocking, reviews))
        }
    }
}
