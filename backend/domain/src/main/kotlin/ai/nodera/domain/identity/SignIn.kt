package ai.nodera.domain.identity

import ai.nodera.domain.actor.ActorId
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val CODE_LENGTH = 8
private val CODE_PATTERN = Regex("[0-9]{$CODE_LENGTH}")

/**
 * How many wrong guesses a code survives.
 *
 * Eight digits is what a person will retype from an e-mail, and eight digits is guessable. The
 * counter is what makes the short code safe rather than the length — a limit on the credential
 * itself, not a policy layered above it.
 */
public const val SIGN_IN_CODE_MAX_ATTEMPTS: Int = 5

/** How many digits a code has, so a generator and a test agree on it without repeating the number. */
public const val SIGN_IN_CODE_LENGTH: Int = CODE_LENGTH

@JvmInline
public value class SignInCodeId(
    public val value: Uuid,
)

/** The plaintext a person receives. Stored only as an Argon2id hash, like every other secret. */
@JvmInline
public value class SignInCode(
    public val value: String,
) : Secret {
    init {
        require(CODE_PATTERN.matches(value)) { "a sign-in code is $CODE_LENGTH digits" }
    }

    override val exposed: String get() = value

    override fun toString(): String = REDACTED
}

public data class SignInCodeRecord(
    public val id: SignInCodeId,
    public val actorId: ActorId,
    public val codeHash: SecretHash,
    public val attempts: Int,
    public val expiresAt: Instant,
    public val consumedAt: Instant?,
)

public enum class SignInCodeState {
    LIVE,
    CONSUMED,
    EXPIRED,
    EXHAUSTED,
}

/** Consumption and exhaustion are checked before expiry: both reject regardless of the clock. */
public fun SignInCodeRecord.stateAt(now: Instant): SignInCodeState =
    when {
        consumedAt != null -> SignInCodeState.CONSUMED
        attempts >= SIGN_IN_CODE_MAX_ATTEMPTS -> SignInCodeState.EXHAUSTED
        expiresAt <= now -> SignInCodeState.EXPIRED
        else -> SignInCodeState.LIVE
    }
