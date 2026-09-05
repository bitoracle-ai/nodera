package ai.nodera.domain.identity

private const val SELECTOR_BYTES = 12
private const val SECRET_BYTES = 32
private const val SEPARATOR = "_"
private const val PART_COUNT = 2

/** Characters, not bytes: a generator sizes its randomness from the byte counts these come from. */
public const val SELECTOR_LENGTH: Int = SELECTOR_BYTES * 2
public const val SECRET_LENGTH: Int = SECRET_BYTES * 2

private val SELECTOR_PATTERN = Regex("[0-9a-f]{$SELECTOR_LENGTH}")
private val SECRET_PATTERN = Regex("[0-9a-f]{$SECRET_LENGTH}")

/**
 * The public half of a token: it addresses a credential row and authenticates nothing.
 *
 * Random rather than derived, so a selector says nothing about whose credential it is — a
 * credential id in the token would be a stable identifier an attacker could correlate across
 * revocations.
 */
@JvmInline
public value class CredentialSelector(
    public val value: String,
) {
    init {
        require(SELECTOR_PATTERN.matches(value)) {
            "selector must be $SELECTOR_LENGTH lowercase hexadecimal characters"
        }
    }
}

/** The secret half. Verified against [SecretHash]; stored nowhere. */
@JvmInline
public value class TokenSecret(
    public val value: String,
) : Secret {
    init {
        require(SECRET_PATTERN.matches(value)) {
            "token secret must be $SECRET_LENGTH lowercase hexadecimal characters"
        }
    }

    override val exposed: String get() = value

    override fun toString(): String = REDACTED
}

/** A whole token as the owner receives it, once. */
@JvmInline
public value class TokenPlaintext(
    public val value: String,
) {
    override fun toString(): String = REDACTED
}

/** A token that arrived over the wire and survived parsing. Not yet verified against anything. */
public data class PresentedToken(
    public val kind: CredentialKind,
    public val selector: CredentialSelector,
    public val secret: TokenSecret,
)

/**
 * The token grammar: `<prefix><selector>_<secret>`, lowercase hexadecimal throughout. The alphabet
 * is load-bearing — invariant CR3 says how.
 */
public object CredentialToken {
    public fun render(
        kind: CredentialKind,
        selector: CredentialSelector,
        secret: TokenSecret,
    ): TokenPlaintext {
        val prefix =
            requireNotNull(kind.tokenPrefix) { "$kind mints no token of its own" }
        return TokenPlaintext(prefix + selector.value + SEPARATOR + secret.value)
    }

    /** `null` for anything that is not a well-formed Nodera token — never a partial acceptance. */
    public fun parse(presented: String): PresentedToken? {
        val kind = CredentialKind.byPrefix(presented) ?: return null
        val parts = presented.removePrefix(kind.tokenPrefix.orEmpty()).split(SEPARATOR)
        val selector = parts.getOrNull(0)?.takeIf(SELECTOR_PATTERN::matches)
        val secret = parts.getOrNull(1)?.takeIf(SECRET_PATTERN::matches)

        return if (parts.size == PART_COUNT && selector != null && secret != null) {
            PresentedToken(kind, CredentialSelector(selector), TokenSecret(secret))
        } else {
            null
        }
    }
}
