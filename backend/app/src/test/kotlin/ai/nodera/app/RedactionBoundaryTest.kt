package ai.nodera.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.Encoder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory

// A real, well-formed token: a redaction test over a fixture with nothing credential-shaped in it
// passes for the wrong reason. `.gitleaks.toml` names these values for the same reason.
private const val SELECTOR = "6f1c9a4b2e8d70a3c5f2b1e4"
private const val VERIFIER = "9c2e4a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"
private const val PAT = "nod_pat_${SELECTOR}_$VERIFIER"

/**
 * The acceptance criterion that a token's plaintext never reaches a log line, asserted where it is
 * actually decided.
 *
 * Not against a hand-built encoder: against **the encoder the shipped `logback.xml` configures**,
 * reached through the appender the running process uses. A converter that works while the
 * configuration does not use it is the shape this whole guard exists to rule out — so the paired
 * negative is an edit to that file, not to Kotlin.
 */
class RedactionBoundaryTest :
    StringSpec({

        // Guard: the %rmsg conversion rule in logback.xml. Put %msg back and this goes red.
        "a token in a log message is gone from what the appender writes" {
            val written = encode("authentication failed for $PAT")

            written shouldNotContain PAT
            written shouldNotContain VERIFIER
            written shouldContain "nod_***"
        }

        // Guard: the %rex conversion rule. Remove it and the message above is clean while the stack
        // trace beneath it carries the token — which is the half that is easy to forget.
        "a token in an exception message is gone from the stack trace too" {
            val written = encode("upstream call failed", IllegalStateException("rejected $PAT"))

            written shouldNotContain PAT
            written shouldContain "IllegalStateException"
        }

        "leaves a line that carries no secret exactly as it was" {
            encode("Applied 7 migration(s); schema is current.") shouldContain
                "Applied 7 migration(s); schema is current."
        }
    })

/** Runs one event through the configured appender's own encoder and returns the bytes it produced. */
private fun encode(
    message: String,
    throwable: Throwable? = null,
): String {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    val console = root.getAppender("console") as? ConsoleAppender<ILoggingEvent>
    console.shouldNotBeNull()

    @Suppress("UNCHECKED_CAST")
    val encoder = console.encoder as Encoder<ILoggingEvent>
    val event = LoggingEvent(Logger::class.java.name, root, Level.INFO, message, throwable, emptyArray())

    return String(encoder.encode(event), Charsets.UTF_8)
}
