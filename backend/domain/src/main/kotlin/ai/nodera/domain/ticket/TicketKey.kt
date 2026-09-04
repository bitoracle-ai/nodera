package ai.nodera.domain.ticket

import kotlin.uuid.Uuid

// V2: `check (prefix ~ '^[a-z][a-z0-9_]{0,15}$')`, restated and deliberately stricter — the column
// is citext, so its own `~` is case-insensitive and `CORE` passes there while it is refused here.
private val PREFIX_PATTERN = Regex("^[a-z][a-z0-9_]{0,15}$")

private const val MAX_TITLE_LENGTH = 300

@JvmInline
public value class TicketId(
    public val value: Uuid,
)

@JvmInline
public value class TicketPrefix(
    public val value: String,
) {
    init {
        require(PREFIX_PATTERN.matches(value)) { "ticket prefix must match ${PREFIX_PATTERN.pattern}" }
    }
}

@JvmInline
public value class TicketNumber(
    public val value: Int,
) {
    init {
        require(value > 0) { "ticket number must be positive" }
    }
}

/**
 * Permanent, and never reused — including after a ticket closes as `wont_do` or `duplicate`
 * (invariant #10). Nothing derives a key from the tickets; `ticket_sequence` is the only source.
 */
public data class TicketKey(
    public val prefix: TicketPrefix,
    public val number: TicketNumber,
) {
    /** The `key` column's own form: V2 checks `key = prefix || '-' || number::text`. */
    public val rendered: String get() = "${prefix.value}-${number.value}"

    override fun toString(): String = rendered
}

public enum class TicketPriority {
    P1,
    P2,
    P3,
    P4,
}

/** What a ticket is before it has a key. Title bounds are V2's `length(trim(title)) between 1 and 300`. */
public data class TicketDraft(
    public val prefix: TicketPrefix,
    public val title: String,
    public val body: String = "",
    public val priority: TicketPriority = TicketPriority.P3,
) {
    init {
        require(title.trim().length in 1..MAX_TITLE_LENGTH) {
            "ticket title must be between 1 and $MAX_TITLE_LENGTH characters once trimmed"
        }
    }
}
