package ai.nodera.domain.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val SELECTOR = "6f1c9a4b2e8d70a3c5f2b1e4"
private const val SECRET = "9c2e4a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"
private const val PAT = "nod_pat_${SELECTOR}_$SECRET"

// An unmistakably fake compact JWS: three base64url segments that decode to nothing meaningful.
private const val JWS = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.c2lnbmF0dXJlLXBsYWNlaG9sZGVy"

class SecretRedactionTest :
    StringSpec({

        "removes a personal access token from a message that quotes it" {
            val redacted = SecretRedaction.redact("authentication failed for $PAT")

            redacted shouldNotContain SECRET
            redacted shouldNotContain SELECTOR
            redacted shouldContain "nod_***"
        }

        "removes a refresh token by the same rule, so a second prefix is not a second gap" {
            SecretRedaction.redact("nod_ref_${SELECTOR}_$SECRET") shouldNotContain SECRET
        }

        "removes a token that is truncated or mangled, because a mistake upstream is still a secret" {
            SecretRedaction.redact("token=nod_pat_6f1c9a4b") shouldNotContain "6f1c9a4b"
        }

        "removes a compact JWS, which is the access token's shape" {
            val redacted = SecretRedaction.redact("Authorization header carried $JWS")

            redacted shouldNotContain "eyJhbGciOiJIUzI1NiJ9"
            redacted shouldContain SecretRedaction.PLACEHOLDER
        }

        "removes whatever an authorisation header carries, keeping the scheme readable" {
            SecretRedaction.redact("Authorization: Bearer opaque-value-of-unknown-shape") shouldBe
                "Authorization: Bearer ***"
            SecretRedaction.redact("basic dXNlcjpwYXNz") shouldBe "basic ***"
        }

        "removes every occurrence, not merely the first" {
            val redacted = SecretRedaction.redact("first $PAT second $PAT")

            redacted shouldBe "first nod_*** second nod_***"
        }

        // The counterpart that keeps the rule usable: over-redaction destroys the diagnostic the
        // log line existed for, and a redactor nobody trusts is one somebody switches off.
        "leaves a message that contains no secret exactly as it was" {
            val message = "Migration V7__credential_selector_and_sign_in_code.sql failed: relation already exists"

            SecretRedaction.redact(message) shouldBe message
        }

        "leaves ordinary hexadecimal alone, such as a commit id or a uuid" {
            val message = "ticket 8371f29 for actor 22222222-2222-4222-8222-222222222222"

            SecretRedaction.redact(message) shouldBe message
        }
    })
