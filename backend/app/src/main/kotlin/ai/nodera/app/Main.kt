package ai.nodera.app

import java.io.PrintStream
import kotlin.system.exitProcess

internal const val EXIT_OK = 0
internal const val EXIT_FAILURE = 1
internal const val EXIT_USAGE = 2
internal const val EXIT_NOT_IMPLEMENTED = 3

/**
 * The version this build was stamped with, set by the `application` plugin as a JVM argument.
 *
 * `unknown` is deliberately visible rather than silently plausible: it appears in the start-up line
 * and in every readiness response, so an image that cannot say what it is says so out loud. A
 * released image always carries the real value, because `release.yml` passes `-Pversion`.
 */
internal val buildVersion: String = System.getProperty("nodera.version") ?: "unknown"

/**
 * One image, three entrypoints (ADR-0006). The same artefact is the API container, the migration job
 * and the agent-spawned stdio server, which is what makes "one artefact" a fact rather than a claim.
 */
internal enum class Command(val argument: String) {
    SERVE("serve"),
    MIGRATE("migrate"),
    MCP_STDIO("mcp-stdio"),
}

private fun usage(): String =
    """
    Usage: nodera [command]

      serve       (default) REST on NODERA_HTTP_PORT, serving the web assets from the same origin
      migrate     apply outstanding migrations as the schema owner, then exit
      mcp-stdio   Model Context Protocol server over stdio, for an agent-spawned process
    """.trimIndent()

fun main(args: Array<String>) {
    exitProcess(dispatch(args, Environment(System.getenv()), System.out, System.err))
}

/**
 * Parses the command and runs it, returning the process exit code.
 *
 * Separate from [main] so it can be tested without ending the test JVM, and so both streams are
 * parameters — which is what makes "nothing reaches stdout on the stdio entrypoint" an assertion a
 * test can make rather than a claim in a comment.
 */
internal fun dispatch(
    args: Array<String>,
    env: Environment,
    out: PrintStream,
    err: PrintStream,
): Int {
    val requested = args.firstOrNull() ?: Command.SERVE.argument
    val command = Command.entries.firstOrNull { it.argument == requested }
    if (command == null) {
        err.println("Unknown command '$requested'.")
        err.println(usage())
        return EXIT_USAGE
    }
    return try {
        when (command) {
            Command.SERVE -> runServe(Configuration.serve(env), out)
            Command.MIGRATE -> runMigrate(Configuration.migrate(env), out, err)
            Command.MCP_STDIO -> runMcpStdio(err)
        }
    } catch (e: ConfigurationError) {
        // Fail closed and say which value is wrong. The message never contains the value itself.
        err.println("Configuration error: ${e.message}")
        EXIT_FAILURE
    }
}

/**
 * Dispatch exists; the server does not yet.
 *
 * The diagnostic goes to **stderr**, and that is the load-bearing part. On this entrypoint stdout is
 * the MCP framing channel: a stray byte there reaches an agent's client as a parse error rather than
 * as a sentence. The rule is established here, guarded by a test, and inherited by MCP-01 — which is
 * worth more than leaving the entrypoint out until then.
 */
internal fun runMcpStdio(err: PrintStream): Int {
    err.println(
        "mcp-stdio is not implemented in this version; it arrives with MCP-01. " +
            "Nothing was written to stdout: on this entrypoint stdout carries MCP framing only.",
    )
    return EXIT_NOT_IMPLEMENTED
}
