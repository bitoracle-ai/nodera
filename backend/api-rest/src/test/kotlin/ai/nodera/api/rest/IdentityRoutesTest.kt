package ai.nodera.api.rest

import ai.nodera.application.identity.ActorProfile
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorSummary
import ai.nodera.domain.actor.DisplayName
import ai.nodera.domain.actor.Handle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.delete
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

private const val VALID_BODY = """{"label":"ci runner","expiresAt":"2027-01-01T00:00:00Z"}"""

private const val NOT_AN_IDENTIFIER = "not-a-uuid"
private const val NEVER_ISSUED = "99999999-9999-4999-8999-999999999999"

private val HUMAN_ONLY =
    mapOf(
        AGENT to
            ActorProfile(
                actor =
                    ActorSummary(
                        id = AGENT,
                        handle = Handle("anna"),
                        kind = ActorKind.HUMAN,
                        displayName = DisplayName("Anna Weber"),
                    ),
                owner = null,
            ),
    )

/** `/api/v1/me` and the caller's own credentials — `docs/API_CONTRACT.md` § 3. */
class IdentityRoutesTest :
    StringSpec({

        "an agent reading /me is told its own identity, its kind and its owner" {
            testApplication {
                application { noderaApi() }

                val response = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $AGENT_PAT") }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"kind\":\"agent\""
                response.bodyAsText() shouldContain "\"handle\":\"release-bot\""
                response.bodyAsText() shouldContain "\"owner\":{"
                response.bodyAsText() shouldContain "\"handle\":\"anna\""
            }
        }

        // Guard: `explicitNulls = false` in `noderaJson`. Watched red with `explicitNulls = true`,
        // which emits `"owner":null` — a shape the generated Zod schema's `.optional()` rejects, so
        // the client would fail on every human actor while every backend test stayed green.
        //
        // It stayed green the first time it was watched: `owner` carried a `= null` default, so
        // `encodeDefaults` dropped the key and the setting the comment named was doing nothing. The
        // default is gone, and this is why.
        "an actor with no owner has no owner key at all, rather than a null one" {
            testApplication {
                application { noderaApi(profiles = InMemoryProfiles(HUMAN_ONLY)) }

                val response = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $AGENT_PAT") }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"kind\":\"human\""
                response.bodyAsText() shouldNotContain "owner"
            }
        }

        "a request with no credential is refused rather than answered anonymously" {
            testApplication {
                application { noderaApi() }

                val response = client.get("/api/v1/me")

                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldContain "\"code\":\"unauthenticated\""
            }
        }

        "an actor that vanished after authenticating is not answered from the context" {
            testApplication {
                application { noderaApi(profiles = InMemoryProfiles(emptyMap())) }

                val response = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $AGENT_PAT") }

                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        "minting a token returns its plaintext once, and nothing that could reconstruct it" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/me/credentials") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                        contentType(ContentType.Application.Json)
                        setBody(VALID_BODY)
                    }

                response.status shouldBe HttpStatusCode.Created
                response.bodyAsText() shouldContain "nod_pat_${MINTED_SELECTOR}_$MINTED_VERIFIER"
                response.bodyAsText() shouldContain "\"label\":\"ci runner\""
                // The stored form, in the shape this suite's hasher produces. A response carrying it
                // would be handing back the verifier the row exists to keep.
                response.bodyAsText() shouldNotContain "plain:"
                response.bodyAsText() shouldNotContain "secretHash"
            }
        }

        "an expiry that has already passed is refused rather than minted" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/me/credentials") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                        contentType(ContentType.Application.Json)
                        setBody("""{"label":"ci runner","expiresAt":"2020-01-01T00:00:00Z"}""")
                    }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "expiresAt"
            }
        }

        "a blank label is refused, and the refusal names the field" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/me/credentials") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                        contentType(ContentType.Application.Json)
                        setBody("""{"label":"  ","expiresAt":"2027-01-01T00:00:00Z"}""")
                    }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "label"
            }
        }

        // Guard: `receiveOrNull`. Watched red by calling `receive` directly, which answers 500 —
        // an unhandled deserialisation failure, whose message quotes what it was reading.
        "a body that will not deserialise is a validation failure, not a server error" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/me/credentials") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                        contentType(ContentType.Application.Json)
                        setBody("""{"label":"ci runner"}""")
                    }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "\"code\":\"validation_failed\""
            }
        }

        "minting requires a credential of its own" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.post("/api/v1/me/credentials") {
                        contentType(ContentType.Application.Json)
                        setBody(VALID_BODY)
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "revoking one of the caller's own credentials answers no content" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.delete("/api/v1/me/credentials/${AGENT_CREDENTIAL.value}") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                    }

                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        // The enumeration criterion, asked so that nothing but the row's existence differs: the same
        // identifier, the same URL, the same caller — once with the row present and owned by
        // somebody else, once with no such row at all.
        //
        // Guard: `RevokeCredential` reporting another actor's credential as absent. Watched red by
        // dropping the `actor_id` condition from the fake store's `revoke`, which makes the first
        // call answer 204 while the second still answers 404.
        "a credential that is somebody else's is indistinguishable from one that does not exist" {
            var presentStatus: HttpStatusCode? = null
            var presentBody = ""

            testApplication {
                application { noderaApi(credentials = InMemoryCredentials()) }

                val response =
                    client.delete("/api/v1/me/credentials/${SOMEBODY_ELSES.value}") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                    }

                presentStatus = response.status
                presentBody = response.bodyAsText()
            }

            testApplication {
                application { noderaApi(credentials = InMemoryCredentials().withoutSomebodyElses()) }

                val response =
                    client.delete("/api/v1/me/credentials/${SOMEBODY_ELSES.value}") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                    }

                presentStatus shouldBe HttpStatusCode.NotFound
                response.status shouldBe presentStatus
                response.bodyAsText() shouldBe presentBody
            }
        }

        // The two answers differ only where the request does — `instance` is the request's own path
        // — so they are compared whole with that one difference substituted out. Asserting a status
        // and a substring is what let round 2's corruption through, and `422` here would be the
        // oracle the `404` exists to close: it would say "that one is well-formed".
        //
        // Guard: the `?: return respondProblem(NOT_FOUND, …)` on the identifier parse. Watched red
        // by answering `VALIDATION_FAILED` there instead.
        "an identifier that is not a credential identifier answers exactly as an absent one does" {
            testApplication {
                application { noderaApi() }

                val malformed =
                    client.delete("/api/v1/me/credentials/$NOT_AN_IDENTIFIER") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                    }
                val absent =
                    client.delete("/api/v1/me/credentials/$NEVER_ISSUED") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                    }

                malformed.status shouldBe HttpStatusCode.NotFound
                absent.status shouldBe HttpStatusCode.NotFound
                malformed.bodyAsText().replace(NOT_AN_IDENTIFIER, NEVER_ISSUED) shouldBe absent.bodyAsText()
            }
        }
    })
