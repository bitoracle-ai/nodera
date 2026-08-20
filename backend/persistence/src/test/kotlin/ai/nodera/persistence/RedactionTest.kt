package ai.nodera.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The redaction guard, reachable directly rather than only through a failure that has to happen
 * first.
 *
 * It previously had no test at all. The container check that appeared to cover it could not: the
 * privilege refusal in [Migrator.apply] happens before Flyway runs, so nothing on that path ever
 * called the redactor — deleting it outright would have left every check green.
 */
class RedactionTest : StringSpec({

    // Guard: the replace(). Remove it and this goes red. This is the case that matters — a failing
    // V4 with debug logging on quotes `create role nodera_app login password '<the real one>'`.
    "removes the role password from a message that quotes the statement" {
        val message =
            "Migration V4__audit_and_rls.sql failed\n" +
                "Statement: create role nodera_app login password 'sup3r-s3cret'"

        val redacted = redactSecret(message, "sup3r-s3cret")

        redacted shouldNotContain "sup3r-s3cret"
        redacted shouldContain "***"
    }

    "removes every occurrence, not merely the first" {
        redactSecret("a=hunter2 and b=hunter2", "hunter2") shouldBe "a=*** and b=***"
    }

    "leaves a message alone when it does not contain the secret" {
        val message = "Migration V2__tickets.sql failed: relation already exists"

        redactSecret(message, "sup3r-s3cret") shouldBe message
    }

    // An empty secret would otherwise make `replace` match at every character boundary and turn the
    // whole message into asterisks — losing the diagnostic entirely for a caller that had nothing
    // to hide in the first place.
    "leaves a message intact when the secret is empty rather than shredding it" {
        val message = "Migration failed: connection refused"

        redactSecret(message, "") shouldBe message
    }
})
