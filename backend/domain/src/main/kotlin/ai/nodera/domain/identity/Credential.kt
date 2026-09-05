package ai.nodera.domain.identity

import ai.nodera.domain.actor.ActorId
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val LABEL_MAX = 200

@JvmInline
public value class CredentialId(
    public val value: Uuid,
)

/** The first layer against a leaked secret, at the type; `SecretRedaction` is the second, at the log. */
internal const val REDACTED: String = "***"

/**
 * A plaintext secret this repository mints. [exposed] is the only way to read one, and is named so
 * that a reader of the call site sees a secret leaving its wrapper.
 */
public sealed interface Secret {
    public val exposed: String
}

/**
 * How a credential authenticates. The wire prefix belongs to the kind so that recognising a
 * presented token never becomes a branch — [byPrefix] reads the table rather than deciding. The
 * names are the `credential_kind` enum's labels lowercased, which is how they round-trip.
 */
public enum class CredentialKind(
    public val tokenPrefix: String?,
) {
    /** A signed-in human's rotating refresh token. The access token beside it is a JWT and is not stored. */
    SESSION("nod_ref_"),

    PERSONAL_ACCESS_TOKEN("nod_pat_"),

    /** A link to an external provider's subject. Mints no token of its own. */
    OIDC_LINK(null),

    ;

    public companion object {
        public fun byPrefix(presented: String): CredentialKind? =
            entries.firstOrNull { entry -> entry.tokenPrefix?.let(presented::startsWith) == true }
    }
}

/** What the owner called it. Shown back to them; it is the only part of a credential they can read. */
@JvmInline
public value class CredentialLabel(
    public val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= LABEL_MAX) {
            "credential label must be 1..$LABEL_MAX characters"
        }
    }
}

/** An Argon2id encoded hash. Not a secret, and still never logged — `skills/secure-coding.md`. */
@JvmInline
public value class SecretHash(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "secret hash must not be blank" }
    }

    override fun toString(): String = REDACTED
}

/** One row of `credential`, without the secret it was minted from — invariant CR1. */
public data class Credential(
    public val id: CredentialId,
    public val actorId: ActorId,
    public val kind: CredentialKind,
    public val selector: CredentialSelector,
    public val secretHash: SecretHash,
    public val label: CredentialLabel,
    public val expiresAt: Instant?,
    public val revokedAt: Instant?,
)

/** Whether a credential may still be used, and when it may not, why. */
public enum class CredentialState {
    LIVE,
    REVOKED,
    EXPIRED,
}

/**
 * Revocation is checked before expiry and without consulting [now]: a revocation marker rejects
 * whatever its timestamp says, because the fail-closed direction is the one that does less.
 */
public fun Credential.stateAt(now: Instant): CredentialState =
    when {
        revokedAt != null -> CredentialState.REVOKED
        expiresAt != null && expiresAt <= now -> CredentialState.EXPIRED
        else -> CredentialState.LIVE
    }
