package ai.nodera.application.identity

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.identity.usecase.IssuePersonalAccessToken
import ai.nodera.application.identity.usecase.RevokeCredential
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.identity.CredentialLabel
import ai.nodera.domain.identity.TokenPlaintext
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal val ACCESS_TTL = 15.minutes
internal val REFRESH_TTL = 30.days
internal val CODE_TTL = 10.minutes

/**
 * The whole identity graph, wired the way the composition root wires it, with the database, the
 * clock and the cryptography replaced.
 *
 * One assembly rather than one per spec: the thing under test here is how the pieces behave
 * together, and a spec that builds its own subset can quietly leave out the piece that would have
 * refused.
 *
 * The two wrappers exist because the guards that decide a concurrent second caller live in the
 * *result* of a conditional write, and nothing about a sequential test can produce one. A spec that
 * needs the losing side of a race interposes a store that has already written; everything else
 * takes the identity default.
 */
internal class World(
    wrapCredentials: (CredentialStore) -> CredentialStore = { it },
    wrapCodes: (SignInCodeStore) -> SignInCodeStore = { it },
) {
    val clock = MutableClock()
    val actors = FakeActors()
    val credentials = FakeCredentials(actors)
    val audit = RecordingAudit()
    val delivery = RecordingDelivery()
    val hasher = FakeHasher()

    private val codeStore = FakeSignInCodes(clock)
    private val store = wrapCredentials(credentials)
    private val codeRows = wrapCodes(codeStore)
    private val secrets = Secrets(SequenceGenerator(), hasher)
    private val accessTokens = FakeAccessTokens(ACCESS_TTL)
    private val recorder = AuditRecorder(audit)
    private val verifier = CredentialVerifier(store, secrets.hasher, clock)
    private val sessions = SessionIssuer(store, accessTokens, secrets, REFRESH_TTL, clock)
    private val codes = SignInCodes(codeRows, secrets, CODE_TTL, clock)

    val authenticator = CredentialAuthenticator(DirectUnitOfWork, verifier, actors, accessTokens)
    val requestSignInCode = RequestSignInCode(DirectUnitOfWork, recorder, actors, codes, delivery)
    val redeemSignInCode = RedeemSignInCode(DirectUnitOfWork, recorder, actors, codes, sessions)
    val refreshSession = RefreshSession(DirectUnitOfWork, recorder, verifier, store, sessions, clock)
    val issueToken = IssuePersonalAccessToken(DirectUnitOfWork, recorder, store, secrets)
    val revokeCredential = RevokeCredential(DirectUnitOfWork, recorder, store, clock)

    val codeRecords get() = codeStore.records

    fun contextFor(
        id: ActorId,
        surface: Surface = Surface.REST,
    ): ActorContext = actors.require(id).contextOn(surface, REQUEST)

    /** A personal access token, minted the way the use case mints one. */
    suspend fun tokenFor(
        id: ActorId,
        label: String = "a token",
        expiresAt: Instant? = null,
    ): TokenPlaintext =
        issueToken
            .issue(contextFor(id), CredentialTerms(CredentialLabel(label), expiresAt))
            .plaintext

    fun withActor(
        seed: Int,
        kind: ActorKind,
    ): ActorId = actor(seed).also { actors.add(it, kind) }
}
