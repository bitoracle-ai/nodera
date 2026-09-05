package ai.nodera.application.identity

import ai.nodera.application.audit.AuditEventSink
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.actor.Email
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.audit.AuditEvent
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.CredentialId
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialSelector
import ai.nodera.domain.identity.SIGN_IN_CODE_MAX_ATTEMPTS
import ai.nodera.domain.identity.Secret
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCode
import ai.nodera.domain.identity.SignInCodeId
import ai.nodera.domain.identity.SignInCodeRecord
import ai.nodera.domain.identity.TokenSecret
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val START: Instant = Instant.parse("2026-09-05T12:00:00Z")
internal val REQUEST: RequestId = RequestId(Uuid.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
internal val ADDRESS: Email = Email("anna@example.test")

internal fun actor(seed: Int): ActorId =
    ActorId(Uuid.parse("00000000-0000-4000-8000-" + seed.toString().padStart(12, '0')))

internal class MutableClock(
    private var instant: Instant = START,
) : Clock {
    override fun now(): Instant = instant

    fun advance(by: Duration) {
        instant += by
    }
}

/**
 * A hasher whose output does not contain its input.
 *
 * Deliberate: several specs assert that a plaintext is absent from a row or an audit entry, and a
 * fake that embedded the secret in its "hash" would make those pass or fail for reasons that have
 * nothing to do with the code under test. Argon2id itself is proved where it is implemented.
 */
internal class FakeHasher : SecretHasher {
    /** How much work a caller spent verifying. The unit in which a timing oracle is measurable. */
    var verifications: Int = 0
        private set

    override fun hash(secret: Secret): SecretHash = SecretHash("argon2id-fake:" + secret.exposed.hashCode())

    override fun verify(
        hash: SecretHash,
        secret: Secret,
    ): Boolean {
        verifications += 1
        return hash == this.hash(secret)
    }
}

/** Deterministic, well-formed material, so a failing assertion names the same token every run. */
internal class SequenceGenerator : SecretGenerator {
    private var issued = 0

    override fun selector(): CredentialSelector = CredentialSelector(hex(SELECTOR_DIGITS))

    override fun tokenSecret(): TokenSecret = TokenSecret(hex(SECRET_DIGITS))

    override fun signInCode(): SignInCode = SignInCode((++issued).toString().padStart(CODE_DIGITS, '0'))

    private fun hex(length: Int): String = (++issued).toString(HEX_RADIX).padStart(length, '0')

    private companion object {
        const val SELECTOR_DIGITS = 24
        const val SECRET_DIGITS = 64
        const val CODE_DIGITS = 8
        const val HEX_RADIX = 16
    }
}

/** Opaque to the caller and recognisable to itself — the shape a signed token has, without a signature. */
internal class FakeAccessTokens(
    private val ttl: Duration,
) : AccessTokens {
    private val issued = mutableMapOf<String, ActorId>()

    override fun issue(
        actorId: ActorId,
        issuedAt: Instant,
    ): AccessToken {
        val value = "eyJmYWtlIn0.${issued.size}.c2lnbmF0dXJl"
        issued[value] = actorId
        return AccessToken(value, issuedAt + ttl)
    }

    override fun verify(presented: String): ActorId? = issued[presented]
}

internal class FakeActors : ActorDirectory {
    private val principals = mutableMapOf<ActorId, ActorPrincipal>()
    private val addresses = mutableMapOf<Email, ActorId>()

    fun add(
        id: ActorId,
        kind: ActorKind,
        status: ActorStatus = ActorStatus.ACTIVE,
        email: Email? = null,
    ): FakeActors {
        principals[id] = ActorPrincipal(id, kind, status)
        email?.let { addresses[it] = id }
        return this
    }

    fun setStatus(
        id: ActorId,
        status: ActorStatus,
    ) {
        principals[id] = principals.getValue(id).copy(status = status)
    }

    fun require(id: ActorId): ActorPrincipal = principals.getValue(id)

    override suspend fun byId(actorId: ActorId): ActorPrincipal? = principals[actorId]

    override suspend fun byEmail(email: Email): ActorPrincipal? = addresses[email]?.let(principals::get)
}

internal class FakeCredentials(
    private val actors: FakeActors,
) : CredentialStore {
    private val rows = mutableMapOf<CredentialId, Credential>()

    val stored: List<Credential> get() = rows.values.toList()

    override suspend fun bySelector(selector: CredentialSelector): StoredCredential? =
        rows.values
            .firstOrNull { it.selector == selector }
            ?.let { StoredCredential(it, actors.require(it.actorId)) }

    override suspend fun insert(
        actorId: ActorId,
        kind: CredentialKind,
        selector: CredentialSelector,
        secretHash: SecretHash,
        terms: CredentialTerms,
    ): Credential {
        val credential =
            Credential(
                id = CredentialId(Uuid.random()),
                actorId = actorId,
                kind = kind,
                selector = selector,
                secretHash = secretHash,
                label = terms.label,
                expiresAt = terms.expiresAt,
                revokedAt = null,
            )
        rows[credential.id] = credential
        return credential
    }

    /** Conditional on ownership and on still being live, exactly as the statement behind it is. */
    override suspend fun revoke(
        actorId: ActorId,
        id: CredentialId,
        at: Instant,
    ): Credential? =
        rows[id]
            ?.takeIf { it.actorId == actorId && it.revokedAt == null }
            ?.copy(revokedAt = at)
            ?.also { rows[id] = it }
}

internal class FakeSignInCodes(
    private val clock: Clock,
) : SignInCodeStore {
    private val rows = mutableListOf<SignInCodeRecord>()

    val records: List<SignInCodeRecord> get() = rows.toList()

    override suspend fun issue(
        actorId: ActorId,
        codeHash: SecretHash,
        expiresAt: Instant,
    ): SignInCodeRecord {
        supersede(actorId)
        val record =
            SignInCodeRecord(
                id = SignInCodeId(Uuid.random()),
                actorId = actorId,
                codeHash = codeHash,
                attempts = 0,
                expiresAt = expiresAt,
                consumedAt = null,
            )
        rows += record
        return record
    }

    override suspend fun mostRecent(actorId: ActorId): SignInCodeRecord? = rows.lastOrNull { it.actorId == actorId }

    /** Conditional on the stored count and on the row still being live, as the statement is. */
    override suspend fun claimAttempt(
        id: SignInCodeId,
        limit: Int,
    ): Boolean {
        val index = rows.indexOfFirst { it.id == id && it.consumedAt == null && it.attempts < limit }
        if (index >= 0) rows[index] = rows[index].copy(attempts = rows[index].attempts + 1)
        return index >= 0
    }

    /** Conditional on the row still being unconsumed, exactly as the statement behind it is. */
    override suspend fun consume(
        id: SignInCodeId,
        at: Instant,
    ): Boolean {
        val index = rows.indexOfFirst { it.id == id && it.consumedAt == null }
        if (index >= 0) rows[index] = rows[index].copy(consumedAt = at)
        return index >= 0
    }

    private fun supersede(actorId: ActorId) {
        rows.indices.forEach { index ->
            val row = rows[index]
            if (row.actorId == actorId && row.consumedAt == null) rows[index] = row.copy(consumedAt = clock.now())
        }
    }
}

/**
 * The concurrent caller that already committed: it performs the conditional write once before the
 * code under test does, so the call under test sees exactly what the loser of a race sees.
 */
internal class AlreadyRotated(
    private val delegate: CredentialStore,
) : CredentialStore by delegate {
    override suspend fun revoke(
        actorId: ActorId,
        id: CredentialId,
        at: Instant,
    ): Credential? {
        delegate.revoke(actorId, id, at)
        return delegate.revoke(actorId, id, at)
    }
}

/** The same shape for a sign-in code: consumed once by the winner, then by the call under test. */
internal class AlreadyConsumed(
    private val delegate: SignInCodeStore,
) : SignInCodeStore by delegate {
    override suspend fun consume(
        id: SignInCodeId,
        at: Instant,
    ): Boolean {
        delegate.consume(id, at)
        return delegate.consume(id, at)
    }
}

/**
 * The concurrent guesses that committed *after* this caller read the row: `mostRecent` hands back
 * the snapshot taken before them.
 */
internal class AlreadyExhausted(
    private val delegate: SignInCodeStore,
) : SignInCodeStore by delegate {
    override suspend fun mostRecent(actorId: ActorId): SignInCodeRecord? =
        delegate.mostRecent(actorId)?.also { stale ->
            repeat(SIGN_IN_CODE_MAX_ATTEMPTS) { delegate.claimAttempt(stale.id, SIGN_IN_CODE_MAX_ATTEMPTS) }
        }
}

internal class RecordingDelivery : SignInCodeDelivery {
    val delivered = mutableListOf<Pair<Email, SignInCode>>()

    override suspend fun deliver(
        email: Email,
        code: SignInCode,
    ) {
        delivered += email to code
    }
}

internal class RecordingAudit : AuditEventSink {
    val events = mutableListOf<AuditEvent>()

    override suspend fun append(event: AuditEvent) {
        events += event
    }
}

/** No database, so no transaction — the boundary itself is proved in `:persistence`. */
internal object DirectUnitOfWork : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}
