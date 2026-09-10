package ai.nodera.api.rest

import ai.nodera.application.identity.RejectionReason
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication

private const val REFRESH_BODY = """{"refreshToken":"$REFRESH_TOKEN"}"""

/** `POST /api/v1/auth/refresh` — `docs/API_CONTRACT.md` § 3. */
class AuthenticationRoutesTest :
    StringSpec({

        "a refresh token is exchanged for a new session" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(REFRESH_BODY)
                    }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "nod_ref_${MINTED_SELECTOR}_$MINTED_VERIFIER"
                response.bodyAsText() shouldContain "\"accessToken\":"
                // The token that was spent is not echoed back beside its replacement.
                response.bodyAsText() shouldNotContain REFRESH_TOKEN
            }
        }

        // Rotation is the whole point of an opaque refresh token, so the replay has to meet a
        // revoked row. Watched red by having the fake store's `revoke` return the credential without
        // marking it, which leaves the second call answering 200.
        "the same refresh token presented twice is refused the second time" {
            testApplication {
                application { noderaApi() }

                val first =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(REFRESH_BODY)
                    }
                val replay =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(REFRESH_BODY)
                    }

                first.status shouldBe HttpStatusCode.OK
                replay.status shouldBe HttpStatusCode.Unauthorized
                replay.bodyAsText() shouldContain "revoked"
            }
        }

        // SEC-01's guard, re-proved where a client can actually reach it: a refresh token is spent
        // at rotation and is not a bearer credential. Watched red with the kind in
        // `CredentialAuthenticator.authenticate`'s guard changed so it never fires, which answers 200
        // with the actor's identity — a captured refresh token serving requests for thirty days
        // without ever rotating.
        "a refresh token presented in the Authorization header is not a bearer credential" {
            testApplication {
                application { noderaApi() }

                val response = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $REFRESH_TOKEN") }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldContain "not the kind this operation accepts"
            }
        }

        "a token this deployment never issued is refused without saying which half was wrong" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"nod_ref_000000000000000000000000_${"0".repeat(64)}"}""")
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldContain "no live credential matches"
            }
        }

        // The ordinary renewal, and the one the first draft of this package made impossible. A
        // client keeps a default Authorization header; the access token expires; it calls refresh
        // with the stale header still attached. Watched red by removing `/api/v1/auth/refresh`
        // from UNAUTHENTICATED_PATHS, which answers 401 before routing and names the wrong
        // credential — leaving a client that behaves normally unable to renew a session at all.
        "a stale bearer header does not stop the refresh it accompanies" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/auth/refresh") {
                        header(HttpHeaders.Authorization, "Bearer $EXPIRED_ACCESS_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody(REFRESH_BODY)
                    }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "nod_ref_${MINTED_SELECTOR}_$MINTED_VERIFIER"
            }
        }

        "a stale bearer header does not stop a probe or a client reading the contract" {
            testApplication {
                application { noderaApi() }

                val health =
                    client.get(
                        "/health/ready",
                    ) { header(HttpHeaders.Authorization, "Bearer $EXPIRED_ACCESS_TOKEN") }
                val contract =
                    client.get(
                        "/openapi.yaml",
                    ) { header(HttpHeaders.Authorization, "Bearer $EXPIRED_ACCESS_TOKEN") }

                health.status shouldBe HttpStatusCode.OK
                contract.status shouldBe HttpStatusCode.OK
            }
        }

        // The exemption is per path, not global: a route the contract secures still refuses. This is
        // the assertion that the round-1 fix is not over-broad, watched red by adding `/api/v1/me`
        // to UNAUTHENTICATED_PATHS.
        //
        // The **detail** is what makes it a paired negative. Asserting only the status stayed green
        // under that mutation: with the path exempt the header is ignored, the route sees no context
        // and answers 401 as well — the same status for the opposite behaviour. Only the middleware
        // says "not in a form this deployment issues"; the route says "none was presented".
        "a stale bearer header still refuses a route the contract secures" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.get(
                        "/api/v1/me",
                    ) { header(HttpHeaders.Authorization, "Bearer $EXPIRED_ACCESS_TOKEN") }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldContain RejectionReason.MALFORMED.detail
                response.bodyAsText() shouldNotContain NO_CREDENTIAL
            }
        }

        "a body that is not a refresh request is a validation failure" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"token":"nope"}""")
                    }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "\"code\":\"validation_failed\""
            }
        }

        // Every required field present and one the build does not know: the only thing that can
        // refuse this body is `ignoreUnknownKeys` staying at its default, which `noderaJson` does
        // not state. The other unknown-key bodies in this suite also omit a required field, so they
        // stay green with the setting flipped and prove nothing about it. Watched red with
        // `ignoreUnknownKeys = true` — the refresh then succeeds at 200.
        "a refresh body carrying a field this build does not know is refused" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"$REFRESH_TOKEN","scope":"admin"}""")
                    }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "\"code\":\"validation_failed\""
            }
        }
    })
