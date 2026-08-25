package ai.nodera.domain.actor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ActorIdentityTest :
    StringSpec({

        // Guard: the require() in DisplayName. Drop it and the domain accepts what V1's check
        // constraint refuses, which turns a validation error into a failed insert.
        "a display name matches the column's own rule: 1..200 characters after trimming" {
            DisplayName("a").value shouldBe "a"
            DisplayName("x".repeat(200)).value shouldBe "x".repeat(200)
            DisplayName("  Anna Weber  ").value shouldBe "  Anna Weber  "

            shouldThrow<IllegalArgumentException> { DisplayName("") }
            shouldThrow<IllegalArgumentException> { DisplayName("   ") }
            shouldThrow<IllegalArgumentException> { DisplayName("x".repeat(201)) }
        }

        "a handle and an email must not be blank" {
            shouldThrow<IllegalArgumentException> { Handle(" ") }
            shouldThrow<IllegalArgumentException> { Email("") }
        }

        "a request id must not be blank, so an audit event always correlates" {
            shouldThrow<IllegalArgumentException> { RequestId("  ") }
        }
    })
