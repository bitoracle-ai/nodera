package ai.nodera.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A configuration value is missing, ambiguous or unusable. Always fatal; never a warning. */
class ConfigurationError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Suffix for the file-backed form of any variable. Docker Secrets, Kubernetes Secrets and Vault all
 * deliver a secret as a mounted file, and an environment variable holding the same value is visible
 * in `docker inspect` and in the process table of anything that can read `/proc`.
 */
private const val FILE_SUFFIX = "_FILE"

private const val DEFAULT_HTTP_PORT = 8080
private const val DEFAULT_STATIC_ROOT = "static"

/**
 * Reads configuration from a map of variables, resolving the `_FILE` form.
 *
 * Both the map and the file reader are parameters so this is testable without touching the real
 * environment or the real filesystem — a configuration loader that can only be exercised by
 * mutating the process environment is a loader nobody writes negative tests for.
 */
class Environment(
    private val variables: Map<String, String>,
    private val readFile: (String) -> String = { path -> Files.readString(Path.of(path)) },
) {
    /**
     * @throws ConfigurationError when the variable is absent, blank, or set in both forms. There is
     *   deliberately no default: invariant #6 is that a missing required value refuses start-up,
     *   because a warning in a log nobody reads is how a system runs on a development signing key.
     */
    fun required(name: String): String =
        resolve(name) ?: throw ConfigurationError(
            "$name is not set. Set it, or set $name$FILE_SUFFIX to a file containing it. " +
                "Nodera refuses to start rather than fall back to a guessable value.",
        )

    fun optional(
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

/** Connection settings. Required by every command that talks to the database. */
data class DatabaseConfig(val url: String, val user: String, val password: String)

/**
 * @param appRolePassword substituted into `V4`'s `create role nodera_app`. Required for `migrate`
 *   and for nothing else, which is why it is not part of [ServeConfig]: the serving process must
 *   never hold a credential it has no use for.
 */
data class MigrateConfig(val database: DatabaseConfig, val appRolePassword: String)

data class ServeConfig(val database: DatabaseConfig, val httpPort: Int, val staticRoot: String)

/**
 * Configuration is loaded **per command**, not once for the process.
 *
 * `migrate` needs the schema owner's credentials and the role placeholder; `serve` needs neither and
 * must not be able to obtain them. Loading one global configuration would mean every command
 * demanding every secret, which trains operators to supply credentials to processes that do not use
 * them — the opposite of what the privilege split in `V4` is for.
 */
object Configuration {
    fun database(env: Environment): DatabaseConfig =
        DatabaseConfig(
            url = env.required("NODERA_DB_URL"),
            user = env.required("NODERA_DB_USER"),
            password = env.required("NODERA_DB_PASSWORD"),
        )

    fun migrate(env: Environment): MigrateConfig =
        MigrateConfig(
            database = database(env),
            appRolePassword = env.required("NODERA_APP_PASSWORD"),
        )

    fun serve(env: Environment): ServeConfig {
        val port = env.optional("NODERA_HTTP_PORT", DEFAULT_HTTP_PORT.toString())
        return ServeConfig(
            database = database(env),
            httpPort =
                port.toIntOrNull()
                    ?: throw ConfigurationError("NODERA_HTTP_PORT is '$port', which is not a port number."),
            staticRoot = env.optional("NODERA_STATIC_ROOT", DEFAULT_STATIC_ROOT),
        )
    }
}
