package ai.nodera.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ConsoleAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

/**
 * Every log line must leave by stderr.
 *
 * On the `mcp-stdio` entrypoint stdout is the MCP framing channel. `DispatchTest` proves that
 * *this* code writes nothing there, but it injects its own streams, so it cannot see what a logging
 * framework does with the real `System.out` — and Logback's `ConsoleAppender` defaults to exactly
 * that. The first `logger.info(...)` on the stdio path, which MCP-01 will certainly add, would then
 * put a plain-text line into JSON-RPC framing and surface in an agent's client as a parse error,
 * with every test in the repository still green.
 *
 * So the guarantee is asserted where it is actually decided: on the appender. Remove
 * `<target>System.err</target>` from `logback.xml` and this goes red.
 */
class LoggingTargetTest :
    StringSpec({

        "the console appender writes to stderr, leaving stdout to the MCP framing channel" {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
            val console = root.getAppender("console") as? ConsoleAppender<*>

            console.shouldNotBeNull()
            console.target shouldBe "System.err"
        }
    })
