package ai.nodera.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.time.Duration

/** A configuration value is missing, ambiguous or unusable. Always fatal; never a warning. */
internal class ConfigurationError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Suffix for the file-backed form of any variable. Docker Secrets, Kubernetes Secrets and Vault all
 * deliver a secret as a mounted file, and an environment variable holding the same value is visible
 * in `docker inspect` and in the process table of anything that can read `/proc`.
 */
private const val FILE_SUFFIX = "_FILE"

private const val DEFAULT_HTTP_PORT = 8080

// 0, -1 and 70000 all parse as integers and then fail inside Netty with a stack trace instead
// of the named refusal the rest of this file promises.
private const val MIN_PORT = 1
private const val MAX_PORT = 65535
private const val DEFAULT_STATIC_ROOT = "static"
private const val REDACTED = "***"

// A signing key shorter than the digest it feeds adds no strength; HMAC-SHA256's block is 32 bytes.
private const val MIN_SIGNING_KEY_BYTES = 32
private const val DEFAULT_ACCESS_TTL = "PT15M"
private const val DEFAULT_REFRESH_TTL = "P30D"
private const val DEFAULT_SIGN_IN_CODE_TTL = "PT10M"

// Defaults are what .env.example ships. The minima are OWASP's floor for Argon2id, enforced so a
// deployment can raise the cost as its hardware allows and cannot lower it into uselessness.
private const val DEFAULT_HASH_MEMORY_KIB = 65536
private const val DEFAULT_HASH_ITERATIONS = 3
private const val DEFAULT_HASH_PARALLELISM = 1
private const val MIN_HASH_MEMORY_KIB = 19456
private const val MIN_HASH_ITERATIONS = 2
private const val MIN_HASH_PARALLELISM = 1

private const val OIDC_ISSUER = "NODERA_OIDC_ISSUER"
private const val OIDC_CLIENT_ID = "NODERA_OIDC_CLIENT_ID"
private const val OIDC_CLIENT_SECRET = "NODERA_OIDC_CLIENT_SECRET"
private val OIDC_VARIABLES = listOf(OIDC_ISSUER, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET)

/**
 * Reads configuration from a map of variables, resolving the `_FILE` form.
 *
 * Both the map and the file reader are parameters so this is testable without touching the real
 * environment or the real filesystem — a configuration loader that can only be exercised by
 * mutating the process environment is a loader nobody writes negative tests for.
 */
internal class Environment(
    private val variables: Map<String, String>,
    private val readFile: (String) -> String = { path -> Files.readString(Path.of(path)) },
) {
    /**
     * @throws ConfigurationError when the variable is absent, blank, or set in both forms. There is
     *   deliberately no default: invariant #6 is that a missing required value refuses start-up,
     *   because a warning in a log nobody reads is how a system runs on a development signing key.
     */
    internal fun required(name: String): String =
        resolve(name) ?: throw ConfigurationError(
            "$name is not set. Set it, or set $name$FILE_SUFFIX to a file containing it. " +
                "Nodera refuses to start rather than fall back to a guessable value.",
        )

    internal fun optional(
        name: String,
        default: String,
    ): String = resolve(name) ?: default

    /**
     * Setting both `NAME` and `NAME_FILE` is refused rather than resolved by precedence.
     *
     * A precedence rule resolves the ambiguity silently, and the operator finds out which value won
     * during an incident. Refusing costs a restart at deploy time, which is the cheapest moment this
     * mistake can possibly be found.
     */
    private fun resolve(name: String): String? {
        val direct = variables[name]?.takeIf { it.isNotBlank() }
        val fileName = name + FILE_SUFFIX
        val path = variables[fileName]?.takeIf { it.isNotBlank() }

        if (direct != null && path != null) {
            throw ConfigurationError(
                "$name and $fileName are both set. Set exactly one — Nodera will not guess which " +
                    "value you meant.",
            )
        }
        return direct ?: path?.let { readSecretFile(fileName, it) }
    }

    private fun readSecretFile(
        variable: String,
        path: String,
    ): String {
        val content =
            try {
                readFile(path)
            } catch (e: IOException) {
                throw ConfigurationError("$variable points at $path, which could not be read.", e)
            }
        // Trailing newline: every editor and most secret mounts add one, and a password with an
        // invisible newline fails authentication in a way that reads like a wrong password.
        return content.trim().takeIf { it.isNotEmpty() }
            ?: throw ConfigurationError("$variable points at $path, which is empty.")
    }
}

/**
 * Connection settings. Required by every command that talks to the database.
 *
 * Every configuration object holding a secret overrides `toString`, because the generated one
 * prints it and these objects are composed into [ServeConfig] and [MigrateConfig] — one `"$config"`
 * in a log line or an exception message is all it takes. `ConfigTest` is red without the overrides.
 */
internal data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    override fun toString(): String = "DatabaseConfig(url=$url, user=$user, password=$REDACTED)"
}

/**
 * @param appRolePassword substituted into `V4`'s `create role nodera_app`. Required for `migrate`
 *   and for nothing else, which is why it is not part of [ServeConfig]: the serving process must
 *   never hold a credential it has no use for.
 */
internal data class MigrateConfig(
    val database: DatabaseConfig,
    val appRolePassword: String,
) {
    override fun toString(): String = "MigrateConfig(database=$database, appRolePassword=$REDACTED)"
}

internal data class ServeConfig(
    val database: DatabaseConfig,
    val httpPort: Int,
    val staticRoot: String,
    val identity: IdentityConfig,
)

/** Argon2id's cost. Not a secret — but a floor under it is, in effect, a security control. */
internal data class HashCost(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
)

/**
 * An external provider for human sign-in, present only when **all three** variables are set.
 *
 * A partial configuration refuses start-up rather than falling back to local sign-in. Falling back
 * is how a deployment that believes it delegates authentication to its identity provider quietly
 * accepts e-mail codes instead.
 */
internal class OidcConfig(
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
) {
    override fun toString(): String = "OidcConfig(issuer=$issuer, clientId=$clientId, clientSecret=$REDACTED)"
}

/**
 * Not a `data class`: a generated `equals` over [signingKey] would compare `ByteArray` identities,
 * which is the wrong answer rather than an inconvenient one.
 */
internal class IdentityConfig(
    val signingKey: ByteArray,
    val issuer: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
    val signInCodeTtl: Duration,
    val hashCost: HashCost,
    val oidc: OidcConfig?,
) {
    override fun toString(): String =
        "IdentityConfig(issuer=$issuer, signingKey=$REDACTED, accessTtl=$accessTtl, " +
            "refreshTtl=$refreshTtl, signInCodeTtl=$signInCodeTtl, hashCost=$hashCost, oidc=$oidc)"
}

/**
 * Configuration is loaded **per command**, not once for the process.
 *
 * `migrate` needs the schema owner's credentials and the role placeholder; `serve` needs neither and
 * must not be able to obtain them. Loading one global configuration would mean every command
 * demanding every secret, which trains operators to supply credentials to processes that do not use
 * them — the opposite of what the privilege split in `V4` is for.
 */
internal object Configuration {
    internal fun database(env: Environment): DatabaseConfig =
        DatabaseConfig(
            url = env.required("NODERA_DB_URL"),
            user = env.required("NODERA_DB_USER"),
            password = env.required("NODERA_DB_PASSWORD"),
        )

    internal fun migrate(env: Environment): MigrateConfig =
        MigrateConfig(
            database = database(env),
            appRolePassword = env.required("NODERA_APP_PASSWORD"),
        )

    internal fun serve(env: Environment): ServeConfig {
        val port = env.optional("NODERA_HTTP_PORT", DEFAULT_HTTP_PORT.toString())
        return ServeConfig(
            database = database(env),
            httpPort =
                port.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }
                    ?: throw ConfigurationError("NODERA_HTTP_PORT is '$port', which is not a port number."),
            staticRoot = env.optional("NODERA_STATIC_ROOT", DEFAULT_STATIC_ROOT),
            identity = identity(env),
        )
    }

    /**
     * Every value authentication needs, refused rather than defaulted (invariant #6).
     *
     * There is no development fallback anywhere in this file. A fallback signing key is how a
     * deployment ends up verifying tokens anybody can mint, with nothing in the log to say so.
     */
    internal fun identity(env: Environment): IdentityConfig =
        IdentityConfig(
            signingKey = signingKey(env),
            issuer = env.required("NODERA_JWT_ISSUER"),
            accessTtl = duration(env, "NODERA_ACCESS_TOKEN_TTL", DEFAULT_ACCESS_TTL),
            refreshTtl = duration(env, "NODERA_REFRESH_TOKEN_TTL", DEFAULT_REFRESH_TTL),
            signInCodeTtl = duration(env, "NODERA_SIGN_IN_CODE_TTL", DEFAULT_SIGN_IN_CODE_TTL),
            hashCost = hashCost(env),
            oidc = oidc(env),
        )

    /** The decoded length is reported; the value never is. A refusal is read by whoever deploys. */
    private fun signingKey(env: Environment): ByteArray {
        val encoded = env.required("NODERA_JWT_SIGNING_KEY")
        val decoded =
            try {
                Base64.getDecoder().decode(encoded)
            } catch (notBase64: IllegalArgumentException) {
                throw ConfigurationError(
                    "NODERA_JWT_SIGNING_KEY is not base64. Generate one with: openssl rand -base64 48",
                    notBase64,
                )
            }
        if (decoded.size < MIN_SIGNING_KEY_BYTES) {
            throw ConfigurationError(
                "NODERA_JWT_SIGNING_KEY decodes to ${decoded.size} bytes; at least $MIN_SIGNING_KEY_BYTES " +
                    "are required. Generate one with: openssl rand -base64 48",
            )
        }
        return decoded
    }

    private fun duration(
        env: Environment,
        name: String,
        default: String,
    ): Duration {
        val raw = env.optional(name, default)
        return runCatching { Duration.parse(raw) }.getOrNull()?.takeIf { it.isPositive() }
            ?: throw ConfigurationError("$name is '$raw', which is not a positive ISO-8601 duration such as PT15M.")
    }

    private fun hashCost(env: Environment): HashCost =
        HashCost(
            memoryKib = cost(env, "NODERA_TOKEN_HASH_MEMORY_KIB", DEFAULT_HASH_MEMORY_KIB, MIN_HASH_MEMORY_KIB),
            iterations = cost(env, "NODERA_TOKEN_HASH_ITERATIONS", DEFAULT_HASH_ITERATIONS, MIN_HASH_ITERATIONS),
            parallelism =
                cost(env, "NODERA_TOKEN_HASH_PARALLELISM", DEFAULT_HASH_PARALLELISM, MIN_HASH_PARALLELISM),
        )

    private fun cost(
        env: Environment,
        name: String,
        default: Int,
        minimum: Int,
    ): Int {
        val raw = env.optional(name, default.toString())
        return raw.toIntOrNull()?.takeIf { it >= minimum }
            ?: throw ConfigurationError("$name is '$raw'; this build accepts no less than $minimum.")
    }

    private fun oidc(env: Environment): OidcConfig? {
        val values = OIDC_VARIABLES.associateWith { env.optional(it, "") }
        val missing = values.filterValues { it.isBlank() }.keys

        return when {
            missing.size == OIDC_VARIABLES.size -> null

            missing.isEmpty() ->
                OidcConfig(
                    issuer = values.getValue(OIDC_ISSUER),
                    clientId = values.getValue(OIDC_CLIENT_ID),
                    clientSecret = values.getValue(OIDC_CLIENT_SECRET),
                )

            else ->
                throw ConfigurationError(
                    "OIDC is configured in part: ${missing.sorted().joinToString(", ")} not set. " +
                        "Set all of ${OIDC_VARIABLES.joinToString(", ")}, or none of them for local sign-in.",
                )
        }
    }
}
