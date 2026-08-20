package ai.nodera.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The dispatcher's contract, including the one rule that outlives the stub it is written against:
 * on `mcp-stdio`, stdout carries MCP framing and nothing else.
 *
 * That rule is guarded here rather than in MCP-01 on purpose. When the real server replaces the
 * stub, the test that stops a diagnostic reaching stdout already exists and already passes — so the
 * replacement cannot quietly introduce the one byte that turns a message into a client-side parse
 * error.
 */
class DispatchTest : StringSpec({

    "an unknown command exits with the usage code and writes nothing to stdout" {
        val run = dispatchCapturing("wibble")

        run.exitCode shouldBe EXIT_USAGE
        run.stdout shouldBe ""
        run.stderr shouldContain "Unknown command 'wibble'"
        run.stderr shouldContain "mcp-stdio"
    }

    // Guard: the choice of stream in runMcpStdio. Change err to out and this goes red.
    "mcp-stdio leaves stdout byte-for-byte empty and explains itself on stderr" {
        val run = dispatchCapturing("mcp-stdio")

        run.exitCode shouldBe EXIT_NOT_IMPLEMENTED
        run.stdout shouldBe ""
        run.stderr shouldContain "MCP-01"
    }

    "mcp-stdio does not read configuration, so it fails the same way with an empty environment" {
        dispatchCapturing("mcp-stdio", environment = emptyMap()).exitCode shouldBe EXIT_NOT_IMPLEMENTED
    }

    // Guard: the ConfigurationError catch in dispatch. Remove it and the process dies with a stack
    // trace instead of a sentence naming the variable.
    "a missing variable is reported as a named configuration error, not a stack trace" {
        val run = dispatchCapturing("migrate", environment = emptyMap())

        run.exitCode shouldBe EXIT_FAILURE
        run.stderr shouldContain "NODERA_DB_URL"
        run.stderr shouldContain "Configuration error"
    }
})

private data class CapturedRun(val exitCode: Int, val stdout: String, val stderr: String)

private fun dispatchCapturing(
    vararg args: String,
    environment: Map<String, String> = emptyMap(),
): CapturedRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exitCode =
        PrintStream(out, true).use { outStream ->
            PrintStream(err, true).use { errStream ->
                dispatch(arrayOf(*args), Environment(environment), outStream, errStream)
            }
        }
    return CapturedRun(exitCode, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
}
