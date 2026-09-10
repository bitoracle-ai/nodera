package ai.nodera.api.rest

import ai.nodera.domain.actor.RequestId
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import kotlin.uuid.Uuid

/** `docs/API_CONTRACT.md` § 1. Echoed on every response, including a refusal. */
public const val REQUEST_ID_HEADER: String = "X-Request-Id"

private val CORRELATION_ID = AttributeKey<RequestId>("nodera.requestId")

/**
 * Establishes the id this request is correlated by, and echoes the one it used — the rule and its
 * reasoning are `docs/API_CONTRACT.md` § 1.
 *
 * `Setup`, so the id exists before authentication, which records it.
 */
public fun Application.installRequestCorrelation() {
    intercept(ApplicationCallPipeline.Setup) {
        val presented = call.request.header(REQUEST_ID_HEADER)?.let(::parseUuid)
        val id = RequestId(presented ?: Uuid.random())
        call.attributes.put(CORRELATION_ID, id)
        call.response.header(REQUEST_ID_HEADER, id.value.toString())
    }
}

/**
 * The correlation id of this request.
 *
 * Without [installRequestCorrelation] the fallback mints a **fresh id per call** and echoes none, so
 * two readers of one request disagree — which is not what a request carrying no usable header gets.
 * `serve` installs the plugin; the fallback keeps a test application that does not from failing.
 */
public fun ApplicationCall.correlationId(): RequestId = attributes.getOrNull(CORRELATION_ID) ?: RequestId(Uuid.random())

private fun parseUuid(raw: String): Uuid? = runCatching { Uuid.parse(raw.trim()) }.getOrNull()
