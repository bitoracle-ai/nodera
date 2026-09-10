package ai.nodera.api.rest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.uuid.Uuid

private const val CLIENT_ID = "0189d4c0-7c1a-4e64-9d0e-2f27a2a5d5f1"
private const val NOT_A_UUID = "req-42-from-the-proxy"

/** `X-Request-Id` — `docs/API_CONTRACT.md` § 1. */
class CorrelationTest :
    StringSpec({

        "a client-supplied request id is echoed unchanged" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.get("/api/v1/me") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                        header(REQUEST_ID_HEADER, CLIENT_ID)
                    }

                response.headers[REQUEST_ID_HEADER] shouldBe CLIENT_ID
            }
        }

        // Guard: `parseUuid` refusing a value the audit column cannot hold. Watched red by passing
        // the header through unparsed, which echoes the proxy's string — and would then reach
        // `audit_event.request_id`, a `uuid not null`, failing the mutation's last statement rather
        // than the edge.
        "a request id that is not a UUID is replaced by one, not echoed and not refused" {
            testApplication {
                application { noderaApi() }

                val response =
                    client.get("/api/v1/me") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                        header(REQUEST_ID_HEADER, NOT_A_UUID)
                    }

                response.status shouldBe HttpStatusCode.OK
                response.headers[REQUEST_ID_HEADER] shouldNotBe NOT_A_UUID
                Uuid.parse(checkNotNull(response.headers[REQUEST_ID_HEADER]))
            }
        }

        "a request that carried no id is still answered with the one it was correlated by" {
            testApplication {
                application { noderaApi() }

                val response = client.get("/health/live")

                Uuid.parse(checkNotNull(response.headers[REQUEST_ID_HEADER]))
            }
        }

        "a refusal carries the correlation id too, which is where a caller most needs it" {
            testApplication {
                application { noderaApi() }

                val response = client.get("/api/v1/me") { header(REQUEST_ID_HEADER, CLIENT_ID) }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[REQUEST_ID_HEADER] shouldBe CLIENT_ID
            }
        }

        // The header would be decoration if the trail carried a different id. Watched red by giving
        // `installCredentialAuthentication` its own fresh id per call instead of the correlation one,
        // which leaves the echo correct and the trail uncorrelated — the failure a status check misses.
        "the id echoed on the response is the id the audit trail recorded" {
            val sink = RecordingAuditSink()
            testApplication {
                application { noderaApi(sink = sink) }

                val response =
                    client.post("/api/v1/me/credentials") {
                        header(HttpHeaders.Authorization, "Bearer $AGENT_PAT")
                        header(REQUEST_ID_HEADER, CLIENT_ID)
                        contentType(ContentType.Application.Json)
                        setBody("""{"label":"ci runner","expiresAt":"2027-01-01T00:00:00Z"}""")
                    }

                response.status shouldBe HttpStatusCode.Created
                sink.events.map {
                    it.context.requestId.value
                        .toString()
                } shouldBe listOf(CLIENT_ID)
            }
        }
    })
