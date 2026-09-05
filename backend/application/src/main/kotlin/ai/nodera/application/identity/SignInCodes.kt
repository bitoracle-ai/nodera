package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.identity.SIGN_IN_CODE_MAX_ATTEMPTS
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCode
import ai.nodera.domain.identity.SignInCodeId
import ai.nodera.domain.identity.SignInCodeRecord
import ai.nodera.domain.identity.SignInCodeState
import ai.nodera.domain.identity.stateAt
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Why a redemption did not happen, in the trail's vocabulary rather than the caller's.
 *
 * The caller is told [RejectionReason.UNKNOWN] whichever of these it was — telling them apart is
 * the enumeration oracle. An incident review needs them apart: a burned-through attempt limit and a
 * single wrong guess are the two ends of what this record exists to distinguish.
 */
public enum class RedemptionFailure {
    NO_CODE,
    CONSUMED,
    EXHAUSTED,
    EXPIRED,
    ATTEMPTS_SPENT,
    WRONG_CODE,
    LOST_CONSUME_RACE,
}

public sealed interface CodeRedemption {
    public data object Redeemed : CodeRedemption

    public data class Refused(
        public val failure: RedemptionFailure,
    ) : CodeRedemption
}

/** A code that has been written but not yet sent. The plaintext leaves the transaction with it. */
public data class MintedSignInCode(
    public val id: SignInCodeId,
    public val code: SignInCode,
)

/**
 * The one-time code's own rules: single use, one live code per actor, a bounded number of guesses.
 *
 * Kept apart from the audited use cases around it so those rules are provable without a
 * transaction, a recorder or a database. Delivery is not here either — it is a network call, and
 * `RequestSignInCode` makes it after its transaction has committed.
 *
 * **Redeeming costs one Argon2id evaluation on every path**, including the ones with no stored hash
 * to reach — `docs/plan/SEC-01.md` § 9.4.
 */
public class SignInCodes(
    private val store: SignInCodeStore,
    private val secrets: Secrets,
    private val ttl: Duration,
    private val clock: Clock,
) {
    // Hashed once, at construction, and matched by nothing. It exists so that [absorb] costs what a
    // real verification costs; deriving it from a minted code keeps the parameters identical.
    private val decoy: SecretHash = secrets.hasher.hash(secrets.generator.signInCode())

    public suspend fun mint(actorId: ActorId): MintedSignInCode {
        val code = secrets.generator.signInCode()
        val record = store.issue(actorId, secrets.hasher.hash(code), clock.now() + ttl)
        return MintedSignInCode(record.id, code)
    }

    /**
     * A guess is claimed before it is spent — [SignInCodeStore.claimAttempt] states why. Nothing
     * here bounds *reissue*: asking for a new code buys five fresh guesses, and a per-actor budget
     * is a separate mechanism (`docs/plan/SEC-01.md` § 9.3).
     */
    public suspend fun redeem(
        actorId: ActorId,
        code: SignInCode,
    ): CodeRedemption {
        val record = store.mostRecent(actorId) ?: return absorbAndRefuse(code, RedemptionFailure.NO_CODE)
        val refusal = refusalFor(record) ?: unclaimable(record)

        return if (refusal != null) absorbAndRefuse(code, refusal) else spend(record, code)
    }

    /** `null` once a guess has been claimed. A dead code costs none — [refusalFor] answers first. */
    private suspend fun unclaimable(record: SignInCodeRecord): RedemptionFailure? =
        if (store.claimAttempt(record.id, SIGN_IN_CODE_MAX_ATTEMPTS)) null else RedemptionFailure.ATTEMPTS_SPENT

    private suspend fun spend(
        record: SignInCodeRecord,
        code: SignInCode,
    ): CodeRedemption =
        when {
            !secrets.hasher.verify(record.codeHash, code) -> refuse(RedemptionFailure.WRONG_CODE)
            store.consume(record.id, clock.now()) -> CodeRedemption.Redeemed
            else -> refuse(RedemptionFailure.LOST_CONSUME_RACE)
        }

    /** One verification against a hash nothing matches, for a caller with no stored hash to reach. */
    public fun absorb(code: SignInCode) {
        secrets.hasher.verify(decoy, code)
    }

    private fun refusalFor(record: SignInCodeRecord): RedemptionFailure? =
        when (record.stateAt(clock.now())) {
            SignInCodeState.LIVE -> null
            SignInCodeState.EXPIRED -> RedemptionFailure.EXPIRED
            SignInCodeState.CONSUMED -> RedemptionFailure.CONSUMED
            SignInCodeState.EXHAUSTED -> RedemptionFailure.EXHAUSTED
        }

    private fun absorbAndRefuse(
        code: SignInCode,
        failure: RedemptionFailure,
    ): CodeRedemption {
        absorb(code)
        return refuse(failure)
    }

    private fun refuse(failure: RedemptionFailure): CodeRedemption = CodeRedemption.Refused(failure)
}
