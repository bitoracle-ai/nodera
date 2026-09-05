package ai.nodera.domain.identity

import ai.nodera.domain.actor.ActorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val NOW = Instant.parse("2026-09-05T12:00:00Z")

private fun code(
    attempts: Int = 0,
    expiresAt: Instant = NOW + 10.minutes,
    consumedAt: Instant? = null,
): SignInCodeRecord =
    SignInCodeRecord(
        id = SignInCodeId(Uuid.parse("33333333-3333-4333-8333-333333333333")),
        actorId = ActorId(Uuid.parse("22222222-2222-4222-8222-222222222222")),
        codeHash = SecretHash("not-a-real-hash"),
        attempts = attempts,
        expiresAt = expiresAt,
        consumedAt = consumedAt,
    )

class SignInCodeStateTest :
    StringSpec({

        "is live while unconsumed, unexhausted and unexpired" {
            code().stateAt(NOW) shouldBe SignInCodeState.LIVE
        }

        // Guard: the consumedAt branch. Remove it and a redeemed code redeems a second time.
        "is consumed once redeemed, so a code is single-use" {
            code(consumedAt = NOW).stateAt(NOW) shouldBe SignInCodeState.CONSUMED
        }

        // Guard: the attempts branch. Remove it and eight digits are brute-forceable.
        "is exhausted at the attempt limit, not one guess after it" {
            code(attempts = SIGN_IN_CODE_MAX_ATTEMPTS - 1).stateAt(NOW) shouldBe SignInCodeState.LIVE
            code(attempts = SIGN_IN_CODE_MAX_ATTEMPTS).stateAt(NOW) shouldBe SignInCodeState.EXHAUSTED
        }

        "is expired once the instant has been reached" {
            code(expiresAt = NOW).stateAt(NOW) shouldBe SignInCodeState.EXPIRED
        }

        "refuses a code that is not the agreed number of digits" {
            shouldThrow<IllegalArgumentException> { SignInCode("1234567") }
            shouldThrow<IllegalArgumentException> { SignInCode("abcdefgh") }
            SignInCode("0".repeat(SIGN_IN_CODE_LENGTH)).value.length shouldBe SIGN_IN_CODE_LENGTH
        }

        "never renders its own digits when interpolated into a string" {
            val plaintext = SignInCode("48210937")

            "$plaintext" shouldNotContain "48210937"
        }
    })
