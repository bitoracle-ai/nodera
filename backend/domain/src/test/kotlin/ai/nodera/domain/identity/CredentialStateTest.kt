package ai.nodera.domain.identity

import ai.nodera.domain.actor.ActorId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val NOW = Instant.parse("2026-09-05T12:00:00Z")

private fun credential(
    expiresAt: Instant? = null,
    revokedAt: Instant? = null,
): Credential =
    Credential(
        id = CredentialId(Uuid.parse("11111111-1111-4111-8111-111111111111")),
        actorId = ActorId(Uuid.parse("22222222-2222-4222-8222-222222222222")),
        kind = CredentialKind.PERSONAL_ACCESS_TOKEN,
        selector = CredentialSelector("6f1c9a4b2e8d70a3c5f2b1e4"),
        secretHash = SecretHash("not-a-real-hash"),
        label = CredentialLabel("release bot, staging"),
        expiresAt = expiresAt,
        revokedAt = revokedAt,
    )

/**
 * The two refusals SEC-01 rests on, as pure rules over an injected instant. No clock, no database:
 * a temporal guard that can only be exercised by waiting is a guard nobody watches fail.
 */
class CredentialStateTest :
    StringSpec({

        "is live when it has neither an expiry nor a revocation" {
            credential().stateAt(NOW) shouldBe CredentialState.LIVE
        }

        "is live while its expiry is still ahead" {
            credential(expiresAt = NOW + 1.hours).stateAt(NOW) shouldBe CredentialState.LIVE
        }

        // Guard: the expiresAt branch in stateAt. Remove it and this goes red.
        "is expired once the instant has been reached, not merely passed" {
            credential(expiresAt = NOW).stateAt(NOW) shouldBe CredentialState.EXPIRED
            credential(expiresAt = NOW - 1.seconds).stateAt(NOW) shouldBe CredentialState.EXPIRED
        }

        // Guard: the revokedAt branch in stateAt. Remove it and this goes red.
        "is revoked whatever the clock says, because a revocation is not a deadline" {
            credential(revokedAt = NOW - 1.seconds).stateAt(NOW) shouldBe CredentialState.REVOKED
            credential(revokedAt = NOW + 365.days).stateAt(NOW) shouldBe CredentialState.REVOKED
        }

        "reports revocation rather than expiry when a credential is both" {
            credential(expiresAt = NOW - 1.hours, revokedAt = NOW - 1.hours).stateAt(NOW) shouldBe
                CredentialState.REVOKED
        }
    })
