package ai.nodera.api.rest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication

/**
 * No response on this surface quotes a credential that was presented to it.
 *
 * The sweep drives the same live refresh token through every position a caller can put one — the
 * `Authorization` header, a JSON body, a path segment, a query string — across every served route,
 * and asserts none of the answers contains it. Driven with a **live** token deliberately: a refusal
 * of an unrecognised one cannot echo a secret it never received, so the same assertions would pass
 * against a wide-open body.
 *
 * **Watched, and one of the two mutations did not go red.** Removing the `SecretRedaction` call from
 * `respondProblem`'s `instance` fails this sweep — that is the load-bearing guard, and it is how the
 * guard came to exist at all: the sweep's first run found the problem document handing the request
 * path straight back. Appending the presented credential to `AuthenticationPlugin.refuse`'s detail
 * — the line a contributor adds while debugging — **stays green**, because the same redaction
 * catches it on the way out. Recorded rather than tidied away: this sweep proves that no credential
 * *reaches* a caller, not that no call site tries to put one there, and it would not catch a leak of
 * something the redactor's rules do not match — a sign-in code is eight digits, and no rule matches
 * eight digits. No route serves one today.
 */
class SecretsNeverLeaveTest :
    StringSpec({

        "no answer to a request carrying a credential contains that credential" {
            testApplication {
                application { noderaApi() }

                val answers = client.hostileRequests()

                answers shouldHaveSize EXPECTED_ANSWERS
                answers.forEach { response ->
                    val body = response.bodyAsText()
                    body shouldNotContain REFRESH_TOKEN
                    body shouldNotContain REFRESH_VERIFIER
                    body shouldNotContain AGENT_VERIFIER
                }
            }
        }

        // The one place a plaintext is meant to leave, so the sweep above must not be read as
        // "no token is ever in a body". This pins the exception to the route that owns it.
        "the only body that carries a token is the one that mints it" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/me/credentials") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                        contentType(ContentType.Application.Json)
                        setBody("""{"label":"ci runner","expiresAt":"2027-01-01T00:00:00Z"}""")
                    }

                response.status shouldBe HttpStatusCode.Created
                response.bodyAsText().contains("nod_pat_${MINTED_SELECTOR}_$MINTED_VERIFIER") shouldBe true
            }
        }
    })

private const val EXPECTED_ANSWERS = 10

private suspend fun HttpClient.hostileRequests(): List<HttpResponse> =
    listOf(
        get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $REFRESH_TOKEN") },
        get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $AGENT_PAT$REFRESH_VERIFIER") },
        get("/api/v1/me?token=$REFRESH_TOKEN") { header(HttpHeaders.Authorization, "Bearer $REFRESH_TOKEN") },
        get("/health/ready") { header(HttpHeaders.Authorization, "Bearer $REFRESH_TOKEN") },
        get("/health/live") { header(HttpHeaders.Authorization, "Bearer $REFRESH_TOKEN") },
        get("/openapi.yaml") { header(HttpHeaders.Authorization, "Bearer $REFRESH_TOKEN") },
        // A token where an identifier belongs. The problem document's `instance` is the request's
        // own path, so without redaction on the way out this one comes straight back.
        delete("/api/v1/me/credentials/$REFRESH_TOKEN") {
            header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
        },
        post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$REFRESH_TOKEN",}""")
        },
        post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"wrongField":"$REFRESH_TOKEN"}""")
        },
        post("/api/v1/me/credentials") {
            header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
            contentType(ContentType.Application.Json)
            setBody("""{"label":"$REFRESH_TOKEN","expiresAt":"nonsense"}""")
        },
    )
