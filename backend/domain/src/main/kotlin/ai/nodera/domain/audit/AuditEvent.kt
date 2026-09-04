package ai.nodera.domain.audit

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.project.ProjectId
import kotlin.uuid.Uuid

// The column's own rule (V4: `check (action ~ '^[a-z_]+\.[a-z_]+$')`), restated so the domain
// refuses what the database would refuse rather than one insert later.
private val ACTION_PATTERN = Regex("^[a-z_]+\\.[a-z_]+$")

/** What happened, as `entity.verb` — the grammar `Capability.verb` and the MCP tool table share. */
@JvmInline
public value class AuditAction(
    public val value: String,
) {
    init {
        require(ACTION_PATTERN.matches(value)) {
            "audit action must match ${ACTION_PATTERN.pattern}"
        }
    }
}

/**
 * How the attempt ended.
 *
 * [FAILED] is for a failure that is a **value** — a rule refused, the use case returns a sealed
 * result, the transaction still commits. A failure that aborts the transaction takes its audit row
 * with it, and that is correct: the mutation left no trace either.
 */
public enum class AuditOutcome {
    SUCCESS,
    DENIED,
    FAILED,
}

/** The `after` key a refusal records the missing verb under. Read back by name, so it is shared. */
public const val DENIED_CAPABILITY_KEY: String = "denied_capability"

/** The entity's state on either side of the mutation, as field name to rendered value. */
public data class AuditDiff(
    public val before: Map<String, String?> = emptyMap(),
    public val after: Map<String, String?> = emptyMap(),
)

/** What the use case knows about what it did. Everything about *who did it* comes from the context. */
public data class AuditEntry(
    public val action: AuditAction,
    public val entityType: String,
    public val entityId: Uuid? = null,
    public val projectId: ProjectId? = null,
    public val diff: AuditDiff = AuditDiff(),
    public val outcome: AuditOutcome = AuditOutcome.SUCCESS,
    public val toolName: String? = null,
) {
    init {
        require(entityType.isNotBlank()) { "entity type must not be blank" }
    }
}

/**
 * One row of the trail.
 *
 * Composed of the context and the entry rather than flattened, so `actor_kind` (invariant AU2) and
 * `on_behalf_of_actor_id` (AU4) can only come from the authenticated context — never from a caller
 * that passed the wrong one or left them out.
 *
 * `occurred_at` is absent on purpose: the column's default is the clock for the trail, so two rows
 * written in one transaction cannot disagree about when it happened.
 */
public data class AuditEvent(
    public val context: ActorContext,
    public val entry: AuditEntry,
)
