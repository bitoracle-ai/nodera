package ai.nodera.api.rest

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * What the composition root answers when asked whether this instance can serve traffic.
 *
 * `:api-rest` deliberately cannot answer this itself: the question is about the database, and this
 * module does not depend on `:persistence` — the build fails if it does. So the adapter states the
 * question and `:app` supplies the answer, which is the same direction every other dependency in
 * this codebase points.
 */
fun interface ReadinessProbe {
    suspend fun check(): ReadinessReport
}

/**
 * @param detail a short, fixed category — never a driver message. This is rendered on an
 *   unauthenticated endpoint, and exception strings routinely carry the host, the port and the
 *   connecting user.
 */
data class ReadinessReport(val ready: Boolean, val detail: String)

@Serializable
data class LivenessResponse(val status: String, val version: String)

@Serializable
data class ReadinessResponse(val status: String, val version: String, val detail: String)

private const val ALIVE = "alive"
private const val READY = "ready"
private const val NOT_READY = "not_ready"

/**
 * Liveness and readiness, deliberately separate.
 *
 * **Liveness never consults the probe.** A process that is running is alive; if liveness reported
 * the database's state, an orchestrator would kill and restart a healthy container every time the
 * database was briefly unreachable or a migration was still pending — turning a wait into a crash
 * loop. Readiness is the signal that removes an instance from rotation; liveness is the signal that
 * destroys it, and conflating them is the most common way a correct deployment fails under load.
 */
fun Route.healthRoutes(
    version: String,
    probe: ReadinessProbe,
) {
    route("/health") {
        get("/live") {
            call.respond(HttpStatusCode.OK, LivenessResponse(status = ALIVE, version = version))
        }
        get("/ready") {
            val report = probe.check()
            call.respond(
                if (report.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadinessResponse(
                    status = if (report.ready) READY else NOT_READY,
                    version = version,
                    detail = report.detail,
                ),
            )
        }
    }
}
