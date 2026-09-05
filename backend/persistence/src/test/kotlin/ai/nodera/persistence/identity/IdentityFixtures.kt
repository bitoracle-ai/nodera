package ai.nodera.persistence.identity

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.identity.AccessToken
import ai.nodera.application.identity.AccessTokens
import ai.nodera.application.identity.CredentialAuthenticator
import ai.nodera.application.identity.CredentialVerifier
import ai.nodera.application.identity.RedeemSignInCode
import ai.nodera.application.identity.RefreshSession
import ai.nodera.application.identity.RequestSignInCode
import ai.nodera.application.identity.SecretGenerator
import ai.nodera.application.identity.SecretHasher
import ai.nodera.application.identity.Secrets
import ai.nodera.application.identity.SessionIssuer
import ai.nodera.application.identity.SignInCodeDelivery
import ai.nodera.application.identity.SignInCodes
import ai.nodera.application.identity.usecase.IssuePersonalAccessToken
import ai.nodera.application.identity.usecase.RevokeCredential
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.Email
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.identity.CredentialSelector
import ai.nodera.domain.identity.SECRET_LENGTH
import ai.nodera.domain.identity.SELECTOR_LENGTH
import ai.nodera.domain.identity.SIGN_IN_CODE_LENGTH
import ai.nodera.domain.identity.Secret
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCode
import ai.nodera.domain.identity.TokenSecret
import ai.nodera.persistence.audit.AuditEventRepository
import ai.nodera.persistence.audit.auditedUnitOfWork
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.toKotlinUuid

internal val START: Instant = Instant.parse("2026-09-05T12:00:00Z")
internal val CODE_TTL = 10.minutes

internal class MovingClock(
    private var instant: Instant = START,
) : Clock {
    override fun now(): Instant = instant

    fun advance(by: Duration) {
        instant += by
    }
}

/** Not Argon2id: the real hasher is proved in `:app`, and 64 MiB per insert would make this suite crawl. */
internal object TestHasher : SecretHasher {
    override fun hash(secret: Secret): SecretHash = SecretHash("argon2id-fake:" + secret.exposed.hashCode())

    override fun verify(
        hash: SecretHash,
        secret: Secret,
    ): Boolean = hash == this.hash(secret)
}

/**
 * A counter shared across every instance in the JVM, and that is not tidiness.
 *
 * `V1` puts a **unique** index on `credential.token_hash`. Real Argon2id salts, so two credentials
 * never collide there; a per-instance counter made two specs mint the same secret, and the database
 * refused the second insert. Worth keeping in mind rather than only working around: that index is a
 * live constraint, and a hasher that stopped salting would be caught by it at the first reissue.
 */
internal class CountingGenerator : SecretGenerator {
    override fun selector(): CredentialSelector = CredentialSelector(hex(SELECTOR_LENGTH))

    override fun tokenSecret(): TokenSecret = TokenSecret(hex(SECRET_LENGTH))

    override fun signInCode(): SignInCode = SignInCode(next().toString().padStart(SIGN_IN_CODE_LENGTH, '0'))

    private fun hex(length: Int): String = next().toString(HEX_RADIX).padStart(length, '0')

    private fun next(): Int = MINTED.incrementAndGet()

    private companion object {
        const val HEX_RADIX = 16
        val MINTED = AtomicInteger()
    }
}

internal class StubAccessTokens : AccessTokens {
    private val issued = mutableMapOf<String, ActorId>()

    override fun issue(
        actorId: ActorId,
        issuedAt: Instant,
    ): AccessToken {
        val value = "eyJmYWtlIn0.${issued.size}.c2lnbmF0dXJl"
        issued[value] = actorId
        return AccessToken(value, issuedAt + 15.minutes)
    }

    override fun verify(presented: String): ActorId? = issued[presented]
}

internal class Mailbox : SignInCodeDelivery {
    val delivered = mutableListOf<SignInCode>()

    override suspend fun deliver(
        email: Email,
        code: SignInCode,
    ) {
        delivered += code
    }
}

/**
 * The identity graph over the real adapters, in transactions the audit harness watches.
 *
 * Only the clock, the hasher and the token signer are substitutes: everything this suite exists to
 * prove — the statements, the constraints, the grants and the one-audit-row-per-mutation rule — runs
 * against a real Postgres.
 */
internal class Identity {
    val clock = MovingClock()
    val mailbox = Mailbox()

    private val unitOfWork = auditedUnitOfWork(emptyList())
    private val recorder = AuditRecorder(AuditEventRepository())
    private val credentials = JdbcCredentialStore()
    private val actors = JdbcActorDirectory()
    private val secrets = Secrets(CountingGenerator(), TestHasher)
    private val accessTokens = StubAccessTokens()
    private val verifier = CredentialVerifier(credentials, TestHasher, clock)
    private val sessions = SessionIssuer(credentials, accessTokens, secrets, 30.days, clock)
    private val codes = SignInCodes(JdbcSignInCodeStore(), secrets, CODE_TTL, clock)

    val authenticator = CredentialAuthenticator(unitOfWork, verifier, actors, accessTokens)
    val issueToken = IssuePersonalAccessToken(unitOfWork, recorder, credentials, secrets)
    val revokeCredential = RevokeCredential(unitOfWork, recorder, credentials, clock)
    val requestSignInCode = RequestSignInCode(unitOfWork, recorder, actors, codes, mailbox)
    val redeemSignInCode = RedeemSignInCode(unitOfWork, recorder, actors, codes, sessions)
    val refreshSession = RefreshSession(unitOfWork, recorder, verifier, credentials, sessions, clock)
}

internal fun contextFor(
    actorId: UUID,
    requestId: UUID,
    kind: ActorKind = ActorKind.AGENT,
): ActorContext =
    ActorContext(
        actorId = ActorId(actorId.toKotlinUuid()),
        kind = kind,
        surface = Surface.REST,
        onBehalfOf = null,
        requestId = RequestId(requestId.toKotlinUuid()),
    )
