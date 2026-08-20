package ai.nodera.app

import ai.nodera.api.rest.ReadinessProbe
import ai.nodera.api.rest.healthRoutes
import ai.nodera.persistence.DatabaseSettings
import ai.nodera.persistence.Migrator
import ai.nodera.persistence.SchemaState
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintStream

private val logger = LoggerFactory.getLogger("ai.nodera.app")

/**
 * How long in-flight requests get to finish on `SIGTERM`, and the hard ceiling after that.
 *
 * An orchestrator sends `SIGTERM` and then waits; a process that ignores it is killed, and every
 * request it was serving becomes an error the client sees. Draining is the difference between a
 * rolling deployment nobody notices and one that shows up in the error rate.
 */
private const val SHUTDOWN_GRACE_MILLIS = 5_000L
private const val SHUTDOWN_TIMEOUT_MILLIS = 15_000L

/**
 * The `serve` entrypoint: REST and the web assets, from one process and one origin.
 *
 * Serving the assets here rather than from a second container is what makes the frontend's API base
 * URL relative, which is what keeps CORS out of the self-hosting path entirely (ADR-0006). No CORS
 * plugin is installed, and that absence is the point rather than an omission.
 */
internal fun runServe(
    config: ServeConfig,
    out: PrintStream,
): Int {
    val migrator =
        Migrator(
            DatabaseSettings(
                url = config.database.url,
                user = config.database.user,
                password = config.database.password,
            ),
        )
    // In the image the assets are always present. On a development machine they are not, because
    // Vite serves them — so their absence is reported rather than fatal, and reported rather than
    // silent, so a broken image cannot look like a development machine.
    val assets = File(config.staticRoot)
    val server =
        embeddedServer(Netty, port = config.httpPort) {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(buildVersion, readinessProbe(migrator))
                if (assets.isDirectory) {
                    // The SPA fallback: a deep link is served index.html so the client router can
                    // resolve it. Without this every route but "/" is a 404 on a hard refresh.
                    singlePageApplication {
                        filesPath = config.staticRoot
                        defaultPage = "index.html"
                        useResources = false
                    }
                }
            }
        }
    if (!assets.isDirectory) {
        out.println("No web assets at ${assets.absolutePath}; serving the API only.")
    }
    Runtime.getRuntime().addShutdownHook(
        Thread { server.stop(SHUTDOWN_GRACE_MILLIS, SHUTDOWN_TIMEOUT_MILLIS) },
    )
    out.println("Nodera $buildVersion listening on port ${config.httpPort}")
    server.start(wait = true)
    return EXIT_OK
}

/**
 * Asks the database, off the event loop, and hands the answer to [readinessReport].
 *
 * The JDBC calls behind this block, so they run on [Dispatchers.IO]: a slow database must not stall
 * the event loop, or an unrelated liveness probe times out and the orchestrator kills a container
 * that was merely waiting.
 *
 * The driver's exception class is logged, never returned. `/health/ready` is unauthenticated and,
 * in the published compose topology, reachable from the host; the operator needs the category and a
 * stranger does not.
 */
private fun readinessProbe(migrator: Migrator): ReadinessProbe =
    ReadinessProbe {
        withContext(Dispatchers.IO) {
            val state = migrator.state()
            if (state is SchemaState.Unreachable) {
                logger.warn("Readiness: the database could not be read ({})", state.category)
            }
            readinessReport(state)
        }
    }
