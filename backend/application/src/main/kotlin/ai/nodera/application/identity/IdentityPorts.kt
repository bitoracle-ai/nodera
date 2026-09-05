package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.actor.Email
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.CredentialId
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialLabel
import ai.nodera.domain.identity.CredentialSelector
import ai.nodera.domain.identity.Secret
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCode
import ai.nodera.domain.identity.SignInCodeId
import ai.nodera.domain.identity.SignInCodeRecord
import ai.nodera.domain.identity.TokenSecret
import kotlin.time.Instant

/**
 * Who a credential or an address resolves to. [kind] is carried for the audit trail (invariant AU2)
 * and decides nothing; [status] is read on **every** authentication, never trusted from a claim.
 */
public data class ActorPrincipal(
    public val id: ActorId,
    public val kind: ActorKind,
    public val status: ActorStatus,
)

/** A credential row together with the actor it belongs to. Never returned with the secret. */
public data class StoredCredential(
    public val credential: Credential,
    public val owner: ActorPrincipal,
)

/**
 * The single construction site of an [ActorContext] in this package, which is what makes "both
 * shapes produce an equivalent context" a property of the code rather than an assertion about it:
 * the surface and the request id are the caller's, every other field is the actor's own row.
 */
internal fun ActorPrincipal.contextOn(
    surface: Surface,
    requestId: RequestId,
): ActorContext =
    ActorContext(
        actorId = id,
        kind = kind,
        surface = surface,
        // A credential says who is acting, never on whose instruction; the surface that knows
        // records delegation (invariant AU4).
        onBehalfOf = null,
        requestId = requestId,
    )

/** Fail-closed on the actor rather than on the credential: a suspension takes effect at once. */
internal fun ActorPrincipal.rejectionIfUnusable(): RejectionReason? =
    if (status == ActorStatus.ACTIVE) null else RejectionReason.INACTIVE_ACTOR

public interface ActorDirectory {
    public suspend fun byId(actorId: ActorId): ActorPrincipal?

    /** Only a human actor has an address — a property of the columns, not a branch on actor kind. */
    public suspend fun byEmail(email: Email): ActorPrincipal?
}

public interface CredentialStore {
    /** By the public half of the token. The secret is verified afterwards, against the hash. */
    public suspend fun bySelector(selector: CredentialSelector): StoredCredential?

    public suspend fun insert(
        actorId: ActorId,
        kind: CredentialKind,
        selector: CredentialSelector,
        secretHash: SecretHash,
        terms: CredentialTerms,
    ): Credential

    /** `null` when the credential is not [actorId]'s, so a caller can never revoke another's. */
    public suspend fun revoke(
        actorId: ActorId,
        id: CredentialId,
        at: Instant,
    ): Credential?
}

/** The parts of a new credential that are not secret. */
public data class CredentialTerms(
    public val label: CredentialLabel,
    public val expiresAt: Instant?,
)

public interface SignInCodeStore {
    /** Consumes any live code before recording the new one: two live codes double the guess surface. */
    public suspend fun issue(
        actorId: ActorId,
        codeHash: SecretHash,
        expiresAt: Instant,
    ): SignInCodeRecord

    /**
     * The newest row as stored — consumed or expired included, so the temporal rule stays testable.
     * Returning exactly one row is also what bounds redeemability to one code.
     */
    public suspend fun mostRecent(actorId: ActorId): SignInCodeRecord?

    /**
     * Spends one of [limit] guesses, or `false` when there is none left. Conditional on the count in
     * the row rather than the count a caller read, because the read happens one Argon2id evaluation
     * earlier: a limit decided by the read is one an attacker widens by asking concurrently
     * (invariant CR5).
     */
    public suspend fun claimAttempt(
        id: SignInCodeId,
        limit: Int,
    ): Boolean

    /** `false` when the row was already consumed, so a concurrent redemption cannot win twice. */
    public suspend fun consume(
        id: SignInCodeId,
        at: Instant,
    ): Boolean
}

/** Where a one-time code goes. SEC-01 ships no implementation — `docs/plan/SEC-01.md` § 7. */
public fun interface SignInCodeDelivery {
    public suspend fun deliver(
        email: Email,
        code: SignInCode,
    )
}

/** Argon2id, per invariant CR1. The implementation is an adapter; nothing here knows its cost. */
public interface SecretHasher {
    public fun hash(secret: Secret): SecretHash

    /** Constant-time in the implementation. A mismatch and an unparseable hash are both `false`. */
    public fun verify(
        hash: SecretHash,
        secret: Secret,
    ): Boolean
}

/** Cryptographically random material. Separate from the hasher so a test can fake either one. */
public interface SecretGenerator {
    public fun selector(): CredentialSelector

    public fun tokenSecret(): TokenSecret

    public fun signInCode(): SignInCode
}

/** A signed, short-lived bearer token carrying the actor's id and nothing a reader could act on. */
public data class AccessToken(
    public val value: String,
    public val expiresAt: Instant,
) {
    override fun toString(): String = "AccessToken(expiresAt=$expiresAt)"
}

public interface AccessTokens {
    public fun issue(
        actorId: ActorId,
        issuedAt: Instant,
    ): AccessToken

    /** `null` for anything that is not a token this deployment signed and that is still valid. */
    public fun verify(presented: String): ActorId?
}

/** The generator and the hasher, grouped: every caller that needs one needs the other. */
public class Secrets(
    public val generator: SecretGenerator,
    public val hasher: SecretHasher,
)
