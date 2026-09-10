package ai.nodera.api.rest

import ai.nodera.application.audit.AuditEventSink
import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.identity.AccessToken
import ai.nodera.application.identity.AccessTokens
import ai.nodera.application.identity.ActorDirectory
import ai.nodera.application.identity.ActorPrincipal
import ai.nodera.application.identity.ActorProfile
import ai.nodera.application.identity.ActorProfiles
import ai.nodera.application.identity.CredentialAuthenticator
import ai.nodera.application.identity.CredentialStore
import ai.nodera.application.identity.CredentialTerms
import ai.nodera.application.identity.CredentialVerifier
import ai.nodera.application.identity.RefreshSession
import ai.nodera.application.identity.SecretGenerator
import ai.nodera.application.identity.SecretHasher
import ai.nodera.application.identity.Secrets
import ai.nodera.application.identity.SessionIssuer
import ai.nodera.application.identity.StoredCredential
import ai.nodera.application.identity.usecase.IssuePersonalAccessToken
import ai.nodera.application.identity.usecase.RevokeCredential
import ai.nodera.application.identity.usecase.WhoAmI
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.actor.ActorSummary
import ai.nodera.domain.actor.DisplayName
import ai.nodera.domain.actor.Email
import ai.nodera.domain.actor.Handle
import ai.nodera.domain.audit.AuditEvent
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.CredentialId
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialLabel
import ai.nodera.domain.identity.CredentialSelector
import ai.nodera.domain.identity.Secret
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCode
import ai.nodera.domain.identity.TokenSecret
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal const val AGENT_SELECTOR = "6f1c9a4b2e8d70a3c5f2b1e4"
internal const val AGENT_VERIFIER =
    "9c2e4a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"
internal const val AGENT_PAT = "nod_pat_${AGENT_SELECTOR}_$AGENT_VERIFIER"

internal const val REFRESH_SELECTOR = "aa1c9a4b2e8d70a3c5f2b1e4"
internal const val REFRESH_VERIFIER =
    "11114a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"
internal const val REFRESH_TOKEN = "nod_ref_${REFRESH_SELECTOR}_$REFRESH_VERIFIER"

/**
 * What a client still has in its `Authorization` header at the moment it calls refresh.
 *
 * Not a nodera token, so it takes the access-token branch; [FixedAccessTokens] verifies nothing, so
 * it is refused there exactly as a genuinely expired JWT is.
 */
internal const val EXPIRED_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.c2lnbmF0dXJlLXBsYWNlaG9sZGVy"

internal const val OTHER_SELECTOR = "cc3c9a4b2e8d70a3c5f2b1e4"

internal const val MINTED_SELECTOR = "bb2c9a4b2e8d70a3c5f2b1e4"
internal const val MINTED_VERIFIER =
    "22224a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"

internal val NOW: Instant = Instant.parse("2026-09-10T12:00:00Z")
internal val FIXED_CLOCK: Clock =
    object : Clock {
        override fun now(): Instant = NOW
    }

internal val AGENT = ActorId(Uuid.parse("11111111-1111-4111-8111-111111111111"))
internal val OWNER = ActorId(Uuid.parse("22222222-2222-4222-8222-222222222222"))
internal val AGENT_CREDENTIAL = CredentialId(Uuid.parse("33333333-3333-4333-8333-333333333333"))
internal val REFRESH_CREDENTIAL = CredentialId(Uuid.parse("44444444-4444-4444-8444-444444444444"))
internal val SOMEBODY_ELSES = CredentialId(Uuid.parse("55555555-5555-4555-8555-555555555555"))

internal val AGENT_PROFILE =
    ActorProfile(
        actor =
            ActorSummary(
                id = AGENT,
                handle = Handle("release-bot"),
                kind = ActorKind.AGENT,
                displayName = DisplayName("Release Bot"),
            ),
        owner =
            ActorSummary(
                id = OWNER,
                handle = Handle("anna"),
                kind = ActorKind.HUMAN,
                displayName = DisplayName("Anna Weber"),
            ),
    )

internal object SameTransaction : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

/** Not a hash. Argon2id itself is proved in `:app`, against the real implementation. */
internal object PlainHasher : SecretHasher {
    override fun hash(secret: Secret): SecretHash = SecretHash("plain:${secret.exposed}")

    override fun verify(
        hash: SecretHash,
        secret: Secret,
    ): Boolean = hash == hash(secret)
}

internal object MintedSecrets : SecretGenerator {
    override fun selector(): CredentialSelector = CredentialSelector(MINTED_SELECTOR)

    override fun tokenSecret(): TokenSecret = TokenSecret(MINTED_VERIFIER)

    override fun signInCode(): SignInCode = SignInCode("12345678")
}

internal object FixedAccessTokens : AccessTokens {
    override fun issue(
        actorId: ActorId,
        issuedAt: Instant,
    ): AccessToken = AccessToken("eyJ.access.${actorId.value}", issuedAt + 15.days)

    /** Nothing in these tests presents a JWT: the agent path is a personal access token. */
    override fun verify(presented: String): ActorId? = null
}

internal object OneActorDirectory : ActorDirectory {
    override suspend fun byId(actorId: ActorId): ActorPrincipal? =
        ActorPrincipal(actorId, ActorKind.AGENT, ActorStatus.ACTIVE).takeIf { actorId == AGENT }

    override suspend fun byEmail(email: Email): ActorPrincipal? = null
}

internal class InMemoryProfiles(
    private val known: Map<ActorId, ActorProfile> = mapOf(AGENT to AGENT_PROFILE),
) : ActorProfiles {
    override suspend fun profile(actorId: ActorId): ActorProfile? = known[actorId]
}

internal class RecordingAuditSink : AuditEventSink {
    val events: MutableList<AuditEvent> = mutableListOf()

    override suspend fun append(event: AuditEvent) {
        events += event
    }
}

/**
 * Credential rows in a map, keyed the way the real store keys them.
 *
 * [revoke] carries the ownership condition into the lookup rather than reading and then checking,
 * because that is what the real adapter does and it is the property the enumeration test rests on.
 */
internal class InMemoryCredentials : CredentialStore {
    val rows: MutableMap<CredentialId, StoredCredential> = mutableMapOf()

    init {
        put(AGENT_CREDENTIAL, AGENT, CredentialKind.PERSONAL_ACCESS_TOKEN, AGENT_SELECTOR, AGENT_VERIFIER)
        put(REFRESH_CREDENTIAL, AGENT, CredentialKind.SESSION, REFRESH_SELECTOR, REFRESH_VERIFIER)
        put(SOMEBODY_ELSES, OWNER, CredentialKind.PERSONAL_ACCESS_TOKEN, OTHER_SELECTOR, AGENT_VERIFIER)
    }

    override suspend fun bySelector(selector: CredentialSelector): StoredCredential? =
        rows.values.firstOrNull { it.credential.selector == selector }

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
        rows[credential.id] = StoredCredential(credential, principal(actorId))
        return credential
    }

    override suspend fun revoke(
        actorId: ActorId,
        id: CredentialId,
        at: Instant,
    ): Credential? {
        val stored = rows[id]?.takeIf { it.credential.actorId == actorId && it.credential.revokedAt == null }
        return stored?.credential?.copy(revokedAt = at)?.also { rows[id] = stored.copy(credential = it) }
    }

    private fun put(
        id: CredentialId,
        actorId: ActorId,
        kind: CredentialKind,
        selector: String,
        verifier: String,
    ) {
        rows[id] =
            StoredCredential(
                credential =
                    Credential(
                        id = id,
                        actorId = actorId,
                        kind = kind,
                        selector = CredentialSelector(selector),
                        secretHash = PlainHasher.hash(TokenSecret(verifier)),
                        label = CredentialLabel("a token"),
                        expiresAt = null,
                        revokedAt = null,
                    ),
                owner = principal(actorId),
            )
    }

    private fun principal(actorId: ActorId): ActorPrincipal =
        ActorPrincipal(actorId, ActorKind.AGENT, ActorStatus.ACTIVE)
}

/** The same store with the other actor's credential never written, for the enumeration test. */
internal fun InMemoryCredentials.withoutSomebodyElses(): InMemoryCredentials = also { it.rows.remove(SOMEBODY_ELSES) }

/**
 * The served application, over fakes of the `:application` ports.
 *
 * It mounts [noderaApiRoutes] — the same function `Serve.kt` mounts — so `ContractDriftTest` walks
 * the routing tree the server actually exposes rather than a copy of it kept in step by hand.
 */
internal fun Application.noderaApi(
    credentials: InMemoryCredentials = InMemoryCredentials(),
    sink: RecordingAuditSink = RecordingAuditSink(),
    profiles: ActorProfiles = InMemoryProfiles(),
) {
    val secrets = Secrets(MintedSecrets, PlainHasher)
    val recorder = AuditRecorder(sink)
    val verifier = CredentialVerifier(credentials, PlainHasher, FIXED_CLOCK)
    val sessions = SessionIssuer(credentials, FixedAccessTokens, secrets, 30.days, FIXED_CLOCK)

    install(ContentNegotiation) { json(noderaJson) }
    installRequestCorrelation()
    installCredentialAuthentication(
        CredentialAuthenticator(SameTransaction, verifier, OneActorDirectory, FixedAccessTokens),
    )
    routing {
        noderaApiRoutes(
            version = "test",
            readiness = ReadinessProbe { ReadinessReport(ready = true, detail = "schema is current") },
            identity =
                IdentitySurface(
                    refreshSession =
                        RefreshSession(SameTransaction, recorder, verifier, credentials, sessions, FIXED_CLOCK),
                    whoAmI = WhoAmI(SameTransaction, profiles),
                    issueToken = IssuePersonalAccessToken(SameTransaction, recorder, credentials, secrets),
                    revokeCredential = RevokeCredential(SameTransaction, recorder, credentials, FIXED_CLOCK),
                    clock = FIXED_CLOCK,
                ),
        )
    }
}
