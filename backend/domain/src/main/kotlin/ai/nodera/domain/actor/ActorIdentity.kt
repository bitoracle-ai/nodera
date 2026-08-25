package ai.nodera.domain.actor

import kotlin.uuid.Uuid

// The schema's own rule (V1: `check (length(trim(display_name)) between 1 and 200)`), restated
// here so the domain refuses exactly what the database would refuse rather than one insert later.
private const val DISPLAY_NAME_MIN = 1
private const val DISPLAY_NAME_MAX = 200

/**
 * Identity of one actor — human or agent, without distinction.
 *
 * `kotlin.uuid.Uuid`, never `java.util.UUID`: `:domain` is framework-free so its rules stay
 * shareable with a future non-JVM client, and the JVM UUID is precisely the detail that would close
 * that door. Adapters convert at the boundary like any other platform type.
 */
@JvmInline
public value class ActorId(
    public val value: Uuid,
)

/**
 * The mention target. One namespace for both kinds (invariant A3), so `@deploy-bot` and `@anna` come
 * from the same pool and an agent can never be created with a handle that shadows a person's.
 *
 * Non-blank is the only rule here. The schema constrains the column no further, and a format invented
 * in the domain would be a divergence rather than an invariant — the mention grammar belongs to the
 * package that parses mentions.
 */
@JvmInline
public value class Handle(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "handle must not be blank" }
    }
}

/** How an actor is shown to a reader. Carries the schema's length rule. */
@JvmInline
public value class DisplayName(
    public val value: String,
) {
    init {
        require(value.trim().length in DISPLAY_NAME_MIN..DISPLAY_NAME_MAX) {
            "display name must be $DISPLAY_NAME_MIN..$DISPLAY_NAME_MAX characters after trimming"
        }
    }
}

/**
 * A human actor's address. Non-blank only: the column is `citext unique` with no format check, and
 * an address is proved by delivery rather than by a regular expression.
 */
@JvmInline
public value class Email(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "email must not be blank" }
    }
}
