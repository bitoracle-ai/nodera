package ai.nodera.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
class DispatchTest :
    StringSpec({

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

        // Guard: the credentialArgument check at the top of dispatch. Remove it and the process
        // carries on with a token in its argv, where the process table and shell history keep it.
        "refuses a token passed as an argument, saying why and without echoing it" {
            val run = dispatchCapturing("serve", PAT)

            run.exitCode shouldBe EXIT_USAGE
            run.stdout shouldBe ""
            run.stderr shouldContain "process table"
            run.stderr shouldContain "argument 2"
            run.stderr shouldNotContain PAT
            run.stderr shouldNotContain PAT.substringAfterLast('_')
        }

        "refuses a token however it is dressed up as an option" {
            listOf("--token=$PAT", "--password=hunter2", "--client-secret", "-key").forEach { argument ->
                dispatchCapturing("serve", argument).exitCode shouldBe EXIT_USAGE
            }
        }

        "refuses before the command is parsed, so no entrypoint is exempt" {
            dispatchCapturing("mcp-stdio", PAT).exitCode shouldBe EXIT_USAGE
            dispatchCapturing(PAT).exitCode shouldBe EXIT_USAGE
        }

        "leaves ordinary arguments alone, so the refusal is about credentials and not about arity" {
            dispatchCapturing("mcp-stdio", "--verbose").exitCode shouldBe EXIT_NOT_IMPLEMENTED
        }
    })

/** A real, well-formed token: a fixture with nothing token-shaped in it would pass for the wrong reason. */
private const val PAT =
    "nod_pat_6f1c9a4b2e8d70a3c5f2b1e4_" +
        "9c2e4a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"

private data class CapturedRun(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

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
