package ai.nodera.api.rest

import ai.nodera.application.identity.RejectionReason
import ai.nodera.domain.identity.SecretRedaction
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication

/**
 * Every `detail` this surface can emit reaches the caller unchanged.
 *
 * `respondProblem` redacts on the way out, which is what stops a mistake upstream reaching a log —
 * and it also rewrites any *deliberate* text that happens to look like a credential. Review round 2
 * found exactly that: `bearer credential` matched the redactor's `bearer|basic \S+` rule and the
 * refusal read "a bearer ***". The rule is right and the sentence was wrong, so the sentence
 * changed; this is the check that says which.
 *
 * Watched red two ways: by restoring the word that was hyphenated, and by putting the redactor's
 * `bearer` rule into one of the five details the sweep was widened to cover — the second is what
 * proves the widened list is swept rather than merely listed. A substring assertion is what let the
 * defect through the first time, so these compare whole strings.
 */
class ProblemDetailTest :
    StringSpec({

        "no refusal detail this surface emits is rewritten by redaction" {
            val details = RejectionReason.entries.map { it.detail } + SURFACE_DETAILS

            details.forEach { detail -> SecretRedaction.redact(detail) shouldBe detail }
        }

        "an unsupported scheme is refused with the whole sentence it was given" {
            testApplication {
                application { noderaApi() }

                val response = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz") }

                response.bodyAsText() shouldBe problemBody("unauthenticated", UNSUPPORTED_SCHEME, "/api/v1/me")
            }
        }

        "a request with no credential is refused with the whole sentence it was given" {
            testApplication {
                application { noderaApi() }

                val response = client.get("/api/v1/me")

                response.bodyAsText() shouldBe problemBody("unauthenticated", NO_CREDENTIAL, "/api/v1/me")
            }
        }
    })

/**
 * Every fixed detail this module passes to `respondProblem`, not a sample of them: the round-1
 * finding was a secrets sweep that claimed "every served route" and drove five of seven, and this
 * list carried two of these seven with the same sentence written over it.
 *
 * `RejectionReason`'s are `:application`'s and are swept beside them.
 */
private val SURFACE_DETAILS =
    listOf(
        UNSUPPORTED_SCHEME,
        NO_CREDENTIAL,
        MALFORMED_BODY,
        ACTOR_GONE,
        CREDENTIAL_NOT_FOUND,
        LABEL_INVALID,
        EXPIRY_INVALID,
    )

private fun problemBody(
    code: String,
    detail: String,
    instance: String,
): String =
    """{"type":"https://nodera.dev/errors/${code.replace('_', '-')}","title":"Unauthenticated",""" +
        """"status":401,"code":"$code","detail":"$detail","instance":"$instance"}"""
