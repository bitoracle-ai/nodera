package ai.nodera.api.rest

import ai.nodera.application.identity.AccessToken
import ai.nodera.application.identity.AccessTokens
import ai.nodera.application.identity.ActorDirectory
import ai.nodera.application.identity.ActorPrincipal
import ai.nodera.application.identity.CredentialAuthenticator
import ai.nodera.application.identity.CredentialStore
import ai.nodera.application.identity.CredentialTerms
import ai.nodera.application.identity.CredentialVerifier
import ai.nodera.application.identity.SecretHasher
import ai.nodera.application.identity.StoredCredential
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.actor.Email
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.CredentialId
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialLabel
import ai.nodera.domain.identity.CredentialSelector
import ai.nodera.domain.identity.Secret
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.TokenSecret
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val SELECTOR = "6f1c9a4b2e8d70a3c5f2b1e4"
private const val VERIFIER = "9c2e4a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"
private const val PAT = "nod_pat_${SELECTOR}_$VERIFIER"
private const val UNKNOWN_PAT =
    "nod_pat_000000000000000000000000_" +
        "1111111111111111111111111111111111111111111111111111111111111111"

private val ACTOR = ActorId(Uuid.parse("11111111-1111-4111-8111-111111111111"))

/**
 * The middleware's contract, driven through a real Ktor application.
 *
 * The route below is what proves the two halves that matter: a valid credential arrives as an
 * `ActorContext`, and an invalid one never reaches the route at all.
 */
class AuthenticationPluginTest :
    StringSpec({

        "a request with no credential reaches the route with no actor context" {
            testApplication {
                authenticating()

                val response = client.get("/probe")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "anonymous"
            }
        }

        "a live token reaches the route as the actor it belongs to, on the REST surface" {
            testApplication {
                authenticating()

                val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $PAT") }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "${ACTOR.value} REST"
            }
        }

        // Guard: the whole Rejected branch. Let it fall through and this goes red — the assertion is
        // on the handler rather than on the status because that is the half a status check misses.
        // Removing only `finish()` does *not* go red on this Ktor version: `respond` has already
        // committed the response and routing skips the handler. `finish()` stays as the explicit
        // statement of intent, and this comment says which of the two the test actually pins.
        "a credential that cannot be used never reaches the route" {
            val reached = AtomicBoolean(false)
            testApplication {
                authenticating(reached = reached)

                val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $UNKNOWN_PAT") }

                response.status shouldBe HttpStatusCode.Unauthorized
                reached.get() shouldBe false
            }
        }

        // Driven with the *live* token on purpose: a refusal of an unknown one cannot echo a
        // verifier it never received, so that assertion would pass with the body wide open.
        "the refusal is the contract's problem document, and never quotes the credential" {
            testApplication {
                authenticating(revoked = true)

                val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $PAT") }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe "Bearer"
                response.bodyAsText() shouldContain "\"code\":\"unauthenticated\""
                response.bodyAsText() shouldContain "\"instance\":\"/probe\""
                response.bodyAsText() shouldNotContain VERIFIER
                response.bodyAsText() shouldNotContain PAT
            }
        }

        // Guard: the explicit media type. Let content negotiation answer and this goes red with
        // `application/json` — which is a JSON body that is not a problem document, so a gateway or
        // client keying on the type does not recognise it. `docs/API_CONTRACT.md` § 4 says RFC 9457,
        // and the media type is half of what the RFC defines.
        "the refusal is served as a problem document, not as ordinary JSON" {
            testApplication {
                authenticating(revoked = true)

                val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $PAT") }

                val type = response.contentType()
                type?.contentType shouldBe "application"
                type?.contentSubtype shouldBe "problem+json"
            }
        }

        "a revoked credential is refused, and the reason says which of the two it was" {
            testApplication {
                authenticating(revoked = true)

                val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $PAT") }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldContain "revoked"
            }
        }

        "an Authorization header that is not a bearer credential is refused rather than ignored" {
            testApplication {
                authenticating()

                val response = client.get("/probe") { header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz") }

                response.status shouldBe HttpStatusCode.Unauthorized
                // The whole sentence, not a substring of it: `shouldContain "bearer"` is what let
                // the redactor rewrite this detail to "a bearer ***" undetected (review round 2).
                response.bodyAsText() shouldContain "\"detail\":\"$UNSUPPORTED_SCHEME\""
            }
        }
    })

private fun ApplicationTestBuilder.authenticating(
    revoked: Boolean = false,
    reached: AtomicBoolean = AtomicBoolean(false),
) {
    application {
        install(ContentNegotiation) { json() }
        installCredentialAuthentication(authenticator(revoked))
        routing {
            get("/probe") {
                reached.set(true)
                val context = call.actorContext()
                call.respondText(context?.let { "${it.actorId.value} ${it.surface}" } ?: "anonymous")
            }
        }
    }
}

private fun authenticator(revoked: Boolean): CredentialAuthenticator =
    CredentialAuthenticator(
        unitOfWork = DirectUnitOfWork,
        verifier = CredentialVerifier(OneCredential(revoked), PlaintextHasher, Clock.System),
        actors = OneActor,
        accessTokens = NoAccessTokens,
    )

private object DirectUnitOfWork : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

/** Not a hash — the hashing itself is proved in `:app`, against the real Argon2id. */
private object PlaintextHasher : SecretHasher {
    override fun hash(secret: Secret): SecretHash = SecretHash("fake:" + secret.exposed.hashCode())

    override fun verify(
        hash: SecretHash,
        secret: Secret,
    ): Boolean = hash == this.hash(secret)
}

private class OneCredential(
    private val revoked: Boolean,
) : CredentialStore {
    override suspend fun bySelector(selector: CredentialSelector): StoredCredential? =
        if (selector.value != SELECTOR) {
            null
        } else {
            StoredCredential(
                credential =
                    Credential(
                        id = CredentialId(Uuid.parse("22222222-2222-4222-8222-222222222222")),
                        actorId = ACTOR,
                        kind = CredentialKind.PERSONAL_ACCESS_TOKEN,
                        selector = selector,
                        secretHash = PlaintextHasher.hash(TokenSecret(VERIFIER)),
                        label = CredentialLabel("a token"),
                        expiresAt = null,
                        revokedAt = if (revoked) Instant.parse("2026-09-01T00:00:00Z") else null,
                    ),
                owner = ActorPrincipal(ACTOR, ActorKind.AGENT, ActorStatus.ACTIVE),
            )
        }

    override suspend fun insert(
        actorId: ActorId,
        kind: CredentialKind,
        selector: CredentialSelector,
        secretHash: SecretHash,
        terms: CredentialTerms,
    ): Credential = error("this adapter test never issues a credential")

    override suspend fun revoke(
        actorId: ActorId,
        id: CredentialId,
        at: Instant,
    ): Credential? = error("this adapter test never revokes a credential")
}

private object OneActor : ActorDirectory {
    override suspend fun byId(actorId: ActorId): ActorPrincipal? =
        ActorPrincipal(actorId, ActorKind.AGENT, ActorStatus.ACTIVE).takeIf { actorId == ACTOR }

    override suspend fun byEmail(email: Email): ActorPrincipal? = null
}

private object NoAccessTokens : AccessTokens {
    override fun issue(
        actorId: ActorId,
        issuedAt: Instant,
    ): AccessToken = error("this adapter test never issues an access token")

    override fun verify(presented: String): ActorId? = null
}
