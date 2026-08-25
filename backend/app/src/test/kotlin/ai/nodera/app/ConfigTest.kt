package ai.nodera.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException

/**
 * Fail-closed is a claim, so every one of these has a guard it is paired against: remove the check
 * named in the comment and the test goes red. A configuration loader tested only on its happy path
 * is a loader whose refusals nobody has ever seen happen.
 */
class ConfigTest :
    StringSpec({

        // Guard: Environment.required's throw. Remove it (return null) and this goes red.
        "refuses a required variable that is absent, naming it" {
            val error =
                shouldThrow<ConfigurationError> {
                    Environment(emptyMap()).required("NODERA_DB_PASSWORD")
                }
            error.message shouldContain "NODERA_DB_PASSWORD"
        }

        // Guard: the isNotBlank filter. Drop it and an empty string is accepted as a password.
        "treats a blank variable as absent rather than as an empty value" {
            shouldThrow<ConfigurationError> {
                Environment(mapOf("NODERA_DB_PASSWORD" to "   ")).required("NODERA_DB_PASSWORD")
            }
        }

        // Guard: the both-set conflict check. Replace it with a precedence rule and this goes red.
        "refuses a variable set both directly and as _FILE, rather than picking one" {
            val env =
                Environment(
                    mapOf(
                        "NODERA_DB_PASSWORD" to "from-the-environment",
                        "NODERA_DB_PASSWORD_FILE" to "/run/secrets/db",
                    ),
                )
            val error = shouldThrow<ConfigurationError> { env.required("NODERA_DB_PASSWORD") }
            error.message shouldContain "NODERA_DB_PASSWORD_FILE"
        }

        // Guard: readSecretFile. Ignore the _FILE suffix and this reads nothing.
        "reads the value from the file named by _FILE, without its trailing newline" {
            var requested: String? = null
            val env =
                Environment(mapOf("NODERA_DB_PASSWORD_FILE" to "/run/secrets/db")) { path ->
                    requested = path
                    "from-the-file\n"
                }
            env.required("NODERA_DB_PASSWORD") shouldBe "from-the-file"
            requested shouldBe "/run/secrets/db"
        }

        "refuses a _FILE that exists but is empty" {
            val env = Environment(mapOf("NODERA_DB_PASSWORD_FILE" to "/run/secrets/db")) { "" }
            shouldThrow<ConfigurationError> { env.required("NODERA_DB_PASSWORD") }
        }

        "refuses a _FILE that cannot be read, naming the path rather than swallowing the cause" {
            val env =
                Environment(mapOf("NODERA_DB_PASSWORD_FILE" to "/run/secrets/db")) {
                    throw IOException("no such file")
                }
            val error = shouldThrow<ConfigurationError> { env.required("NODERA_DB_PASSWORD") }
            error.message shouldContain "/run/secrets/db"
            error.cause.shouldBeIoException()
        }

        // A refusal message is read by whoever is deploying, and often pasted into a chat or a ticket.
        "never puts the value itself into the refusal message" {
            val env =
                Environment(
                    mapOf(
                        "NODERA_DB_PASSWORD" to "s3cr3t-value",
                        "NODERA_DB_PASSWORD_FILE" to "/run/secrets/db",
                    ),
                )
            val error = shouldThrow<ConfigurationError> { env.required("NODERA_DB_PASSWORD") }
            error.message.orEmpty() shouldNotContain "s3cr3t-value"
        }

        // Guard: the range check. Remove `takeIf { it in MIN_PORT..MAX_PORT }` and this goes red —
        // 0 and 70000 both parse as integers and then fail inside Netty with a stack trace instead of
        // the named refusal every other value in this file gets.
        "refuses a port outside the valid range, not merely one that is not a number" {
            listOf("0", "-1", "70000").forEach { value ->
                val env = Environment(complete(mapOf("NODERA_HTTP_PORT" to value)))
                shouldThrow<ConfigurationError> { Configuration.serve(env) }
                    .message
                    .shouldContainNotNull("NODERA_HTTP_PORT")
            }
        }

        "refuses a port that is not a number instead of falling back to the default" {
            val env = Environment(complete(mapOf("NODERA_HTTP_PORT" to "eighty-eighty")))
            val error = shouldThrow<ConfigurationError> { Configuration.serve(env) }
            error.message shouldContain "NODERA_HTTP_PORT"
        }

        // Per-command configuration: serve must not be able to demand a credential it has no use for.
        "migrate requires the role password and serve does not" {
            val env = Environment(complete(emptyMap()))
            Configuration.serve(env).httpPort shouldBe 8080
            shouldThrow<ConfigurationError> { Configuration.migrate(env) }
                .message
                .shouldContainNotNull("NODERA_APP_PASSWORD")
        }
    })

private fun complete(extra: Map<String, String>): Map<String, String> =
    mapOf(
        "NODERA_DB_URL" to "jdbc:postgresql://localhost:5432/nodera",
        "NODERA_DB_USER" to "nodera",
        "NODERA_DB_PASSWORD" to "nodera-local-dev-only",
    ) + extra

private fun Throwable?.shouldBeIoException() {
    (this is IOException) shouldBe true
}

private fun String?.shouldContainNotNull(expected: String) {
    this.orEmpty() shouldContain expected
}
