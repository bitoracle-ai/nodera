package ai.nodera.api.rest

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal const val OPENAPI_RESOURCE: String = "/openapi.yaml"

private val YAML = ContentType("application", "yaml")

/**
 * Serves the committed contract, as bytes, from the classpath.
 *
 * Read once at registration so a missing document fails start-up rather than one request, and
 * answered as bytes rather than as text so "what the server serves" is the file itself — no
 * re-encoding step for the two to differ across.
 */
public fun Route.openApiDocument() {
    val document = openApiBytes()
    get(OPENAPI_RESOURCE) {
        call.respondBytes(document, YAML, HttpStatusCode.OK)
    }
}

/** The committed document, for the server and for the test that compares it with the routing tree. */
public fun openApiBytes(): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream(OPENAPI_RESOURCE)) {
        "$OPENAPI_RESOURCE is missing from the :api-rest resources"
    }.use { it.readBytes() }
