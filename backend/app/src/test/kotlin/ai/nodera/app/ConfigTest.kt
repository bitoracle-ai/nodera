package ai.nodera.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

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

        // ---- Identity (SEC-01) -------------------------------------------------------------
        // Each of these is invariant #6 in one place: a missing or unusable value refuses start-up
        // rather than defaulting, because a warning nobody reads is how a system runs in production
        // on a development signing key.

        // Guard: the signingKey call in Configuration.identity. Drop it and serve starts with no key.
        "serve refuses to start without a signing key" {
            val env = Environment(complete(emptyMap()) - "NODERA_JWT_SIGNING_KEY")
            shouldThrow<ConfigurationError> { Configuration.serve(env) }
                .message
                .shouldContainNotNull("NODERA_JWT_SIGNING_KEY")
        }

        "serve refuses to start without an issuer" {
            val env = Environment(complete(emptyMap()) - "NODERA_JWT_ISSUER")
            shouldThrow<ConfigurationError> { Configuration.serve(env) }
                .message
                .shouldContainNotNull("NODERA_JWT_ISSUER")
        }

        "refuses a signing key that is not base64 rather than signing with its bytes" {
            val env = Environment(complete(mapOf("NODERA_JWT_SIGNING_KEY" to "not base64 at all!!")))
            shouldThrow<ConfigurationError> { Configuration.identity(env) }
                .message
                .shouldContainNotNull("base64")
        }

        // Guard: the MIN_SIGNING_KEY_BYTES check. Remove it and a nine-byte key is accepted, which
        // is a signature anybody with a word list can forge.
        "refuses a signing key that decodes to too few bytes, reporting the length and not the value" {
            val short = "dG9vLXNob3J0"
            val error =
                shouldThrow<ConfigurationError> {
                    Configuration.identity(Environment(complete(mapOf("NODERA_JWT_SIGNING_KEY" to short))))
                }
            error.message.shouldContainNotNull("9 bytes")
            error.message.orEmpty() shouldNotContain short
        }

        "refuses a token lifetime that is not a positive duration" {
            listOf("PT0S", "-PT5M", "fifteen minutes").forEach { value ->
                shouldThrow<ConfigurationError> {
                    Configuration.identity(Environment(complete(mapOf("NODERA_ACCESS_TOKEN_TTL" to value))))
                }.message.shouldContainNotNull("NODERA_ACCESS_TOKEN_TTL")
            }
        }

        // Guard: the minimum in Configuration.cost. Remove it and a deployment can set the memory
        // cost to 1 KiB, which leaves Argon2id in the code and nothing of it in the protection.
        "refuses an Argon2id cost below the floor this build accepts" {
            listOf(
                "NODERA_TOKEN_HASH_MEMORY_KIB" to "1024",
                "NODERA_TOKEN_HASH_ITERATIONS" to "1",
                "NODERA_TOKEN_HASH_PARALLELISM" to "0",
            ).forEach { (name, value) ->
                shouldThrow<ConfigurationError> {
                    Configuration.identity(Environment(complete(mapOf(name to value))))
                }.message.shouldContainNotNull(name)
            }
        }

        "accepts a cost above the floor, so raising it is possible and lowering it is not" {
            val env = Environment(complete(mapOf("NODERA_TOKEN_HASH_MEMORY_KIB" to "131072")))

            Configuration.identity(env).hashCost.memoryKib shouldBe 131072
        }

        // Guard: the partial-configuration branch in Configuration.oidc. Return null instead and a
        // deployment that believes it delegates sign-in to its provider quietly accepts e-mail codes.
        "refuses an OIDC configuration that is only partly set, naming what is missing" {
            val env = Environment(complete(mapOf("NODERA_OIDC_ISSUER" to "https://issuer.example")))
            val error = shouldThrow<ConfigurationError> { Configuration.identity(env) }

            error.message.shouldContainNotNull("NODERA_OIDC_CLIENT_ID")
            error.message.shouldContainNotNull("NODERA_OIDC_CLIENT_SECRET")
        }

        "treats no OIDC variables as local sign-in, and all three as a configured provider" {
            Configuration.identity(Environment(complete(emptyMap()))).oidc shouldBe null

            val configured =
                Configuration.identity(
                    Environment(
                        complete(
                            mapOf(
                                "NODERA_OIDC_ISSUER" to "https://issuer.example",
                                "NODERA_OIDC_CLIENT_ID" to "nodera",
                                "NODERA_OIDC_CLIENT_SECRET" to "ci-dummy",
                            ),
                        ),
                    ),
                )

            configured.oidc?.issuer shouldBe "https://issuer.example"
        }

        "reads the documented defaults when only the required values are set" {
            val identity = Configuration.identity(Environment(complete(emptyMap())))

            identity.accessTtl shouldBe 15.minutes
            identity.refreshTtl shouldBe 30.days
            identity.hashCost shouldBe HashCost(memoryKib = 65536, iterations = 3, parallelism = 1)
        }

        "never puts the signing key into a refusal, whichever value is wrong" {
            val env = Environment(complete(mapOf("NODERA_ACCESS_TOKEN_TTL" to "nonsense")))
            val error = shouldThrow<ConfigurationError> { Configuration.identity(env) }

            error.message.orEmpty() shouldNotContain SIGNING_KEY
        }

        // Guard: the toString overrides on DatabaseConfig, OidcConfig and IdentityConfig. Remove any
        // one of them and this goes red — a configuration object is exactly the thing that ends up
        // interpolated into a start-up log line or an exception message.
        "renders no secret when a configuration object is printed" {
            val env =
                Environment(
                    complete(
                        mapOf(
                            "NODERA_OIDC_ISSUER" to "https://issuer.example",
                            "NODERA_OIDC_CLIENT_ID" to "nodera",
                            "NODERA_OIDC_CLIENT_SECRET" to CLIENT_SECRET,
                        ),
                    ),
                )
            val printed = "${Configuration.serve(env)}"

            printed shouldNotContain DB_PASSWORD
            printed shouldNotContain CLIENT_SECRET
            printed shouldNotContain SIGNING_KEY
            // Not vacuous: the fields around the redacted ones are still readable.
            printed shouldContain "https://issuer.example"
        }

        "renders no password when the migrate configuration is printed" {
            val env = Environment(complete(mapOf("NODERA_APP_PASSWORD" to APP_PASSWORD)))

            "${Configuration.migrate(env)}" shouldNotContain APP_PASSWORD
        }
    })

/** Base64 of an ASCII sentence that says what it is. Forty bytes, so it clears the minimum. */
private const val SIGNING_KEY = "bm9kZXJhLXRlc3Qtb25seS1ub3QtYS1zZWNyZXQtMDAwMDAwMDAwMA=="

// Distinct values, so an assertion that one of them is absent cannot pass because another was
// redacted in its place.
private const val DB_PASSWORD = "nodera-local-dev-only-db"
private const val APP_PASSWORD = "nodera-local-dev-only-app"
private const val CLIENT_SECRET = "nodera-local-dev-only-oidc"

private fun complete(extra: Map<String, String>): Map<String, String> =
    mapOf(
        "NODERA_DB_URL" to "jdbc:postgresql://localhost:5432/nodera",
        "NODERA_DB_USER" to "nodera",
        "NODERA_DB_PASSWORD" to DB_PASSWORD,
        "NODERA_JWT_SIGNING_KEY" to SIGNING_KEY,
        "NODERA_JWT_ISSUER" to "https://nodera.test",
    ) + extra

private fun Throwable?.shouldBeIoException() {
    (this is IOException) shouldBe true
}

private fun String?.shouldContainNotNull(expected: String) {
    this.orEmpty() shouldContain expected
}
