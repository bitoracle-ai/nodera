package ai.nodera.app

import ai.nodera.application.identity.AccessToken
import ai.nodera.application.identity.AccessTokens
import ai.nodera.application.identity.SecretGenerator
import ai.nodera.application.identity.SecretHasher
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.identity.CredentialSelector
import ai.nodera.domain.identity.SECRET_LENGTH
import ai.nodera.domain.identity.SELECTOR_LENGTH
import ai.nodera.domain.identity.SIGN_IN_CODE_LENGTH
import ai.nodera.domain.identity.Secret
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCode
import ai.nodera.domain.identity.TokenSecret
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import de.mkammerer.argon2.Argon2Factory
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val HEX_PER_BYTE = 2
private const val DECIMAL_DIGITS = 10

private val logger = LoggerFactory.getLogger("ai.nodera.app.identity")

/**
 * Argon2id, the only hash a credential is stored as (invariant CR1). The cost comes from
 * configuration with a floor beneath it, so a deployment can raise it as hardware allows and cannot
 * lower it into uselessness.
 */
internal class Argon2idSecretHasher(
    private val cost: HashCost,
) : SecretHasher {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    override fun hash(secret: Secret): SecretHash =
        SecretHash(argon2.hash(cost.iterations, cost.memoryKib, cost.parallelism, secret.exposed.toCharArray()))

    /**
     * Constant-time inside the library. A hash the library cannot read is `false` rather than an
     * exception: a corrupted row must refuse the credential, not take the process down with it.
     */
    override fun verify(
        hash: SecretHash,
        secret: Secret,
    ): Boolean =
        try {
            argon2.verify(hash.value, secret.exposed.toCharArray())
        } catch (unreadable: IllegalArgumentException) {
            logger.warn("A stored credential hash could not be read ({})", unreadable.javaClass.simpleName)
            false
        }
}

/** Every secret this deployment mints comes from here, and from one [SecureRandom]. */
internal class SecureRandomSecrets(
    private val random: SecureRandom = SecureRandom(),
) : SecretGenerator {
    override fun selector(): CredentialSelector = CredentialSelector(hex(SELECTOR_LENGTH))

    override fun tokenSecret(): TokenSecret = TokenSecret(hex(SECRET_LENGTH))

    /**
     * Digits rather than the token alphabet, because a person retypes this one from an e-mail.
     * `nextInt(bound)` is rejection-sampled by the JDK, so no digit is more likely than another.
     */
    override fun signInCode(): SignInCode =
        SignInCode((1..SIGN_IN_CODE_LENGTH).joinToString("") { random.nextInt(DECIMAL_DIGITS).toString() })

    private fun hex(characters: Int): String {
        val bytes = ByteArray(characters / HEX_PER_BYTE)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * The access token: a signed JWT carrying the actor's id, an issuer and an expiry, and nothing else.
 *
 * No capability, no project and no actor kind travel in it. Everything a request is allowed to do is
 * resolved from the database at use time — a claim baked in at sign-in would still be honoured after
 * the grant behind it was revoked, which is invariant #4 lost inside a token.
 */
internal class JwtAccessTokens(
    signingKey: ByteArray,
    private val issuer: String,
    private val ttl: Duration,
) : AccessTokens {
    private val algorithm = Algorithm.HMAC256(signingKey)
    private val verifier = JWT.require(algorithm).withIssuer(issuer).build()

    override fun issue(
        actorId: ActorId,
        issuedAt: Instant,
    ): AccessToken {
        val expiresAt = issuedAt + ttl
        val value =
            JWT
                .create()
                .withIssuer(issuer)
                .withSubject(actorId.value.toString())
                .withIssuedAt(Date(issuedAt.toEpochMilliseconds()))
                .withExpiresAt(Date(expiresAt.toEpochMilliseconds()))
                .withJWTId(Uuid.random().toString())
                .sign(algorithm)
        return AccessToken(value, expiresAt)
    }

    /** The exception's class reaches the log, never the token and never the reason a client could probe. */
    override fun verify(presented: String): ActorId? =
        try {
            subjectOf(verifier.verify(presented).subject)
        } catch (rejected: JWTVerificationException) {
            logger.debug("An access token was rejected ({})", rejected.javaClass.simpleName)
            null
        }

    private fun subjectOf(subject: String?): ActorId? =
        subject?.let { runCatching { Uuid.parse(it) }.getOrNull() }?.let(::ActorId)
}
