package ai.nodera.api.rest

import ai.nodera.application.identity.AuthenticationResult
import ai.nodera.application.identity.CredentialAuthenticator
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private const val BEARER = "Bearer"
private const val UNAUTHENTICATED = "unauthenticated"
private const val PROBLEM_TYPE = "https://nodera.dev/errors/unauthenticated"
private const val PROBLEM_TITLE = "Unauthenticated"

private const val UNSUPPORTED_SCHEME =
    "the Authorization header must carry a bearer credential"

private val ACTOR_CONTEXT = AttributeKey<ActorContext>("nodera.actorContext")
private val PROBLEM_JSON = ContentType("application", "problem+json")

/** RFC 9457, with the `code` clients switch on — `docs/API_CONTRACT.md` § 4. */
@Serializable
public data class ProblemDetail(
    public val type: String,
    public val title: String,
    public val status: Int,
    public val code: String,
    public val detail: String,
    public val instance: String,
)

/**
 * The credential this request carried, or `null` if it carried none. There is no third state: a
 * request that presented something unusable was answered `401` before routing, so "no context"
 * here can never mean "something was presented and quietly ignored".
 */
public fun ApplicationCall.actorContext(): ActorContext? = attributes.getOrNull(ACTOR_CONTEXT)

/**
 * Turns an `Authorization` header into an [ActorContext] before routing, and refuses the request
 * when it cannot. It decides nothing: the surface is the only thing this file contributes.
 *
 * A header that is present and unusable is a `401` here rather than an anonymous request a route
 * later mistakes for a deliberate one. A request with no header is left alone.
 *
 * @param requestId defaults to a fresh id per call. Correlating with a client-supplied header
 *   belongs with the package that builds the routes.
 */
public fun Application.installCredentialAuthentication(
    authenticator: CredentialAuthenticator,
    requestId: (ApplicationCall) -> RequestId = { RequestId(Uuid.random()) },
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val header = call.request.header(HttpHeaders.Authorization)?.trim() ?: return@intercept
        val presented =
            header
                .takeIf { it.startsWith("$BEARER ", ignoreCase = true) }
                ?.substring(BEARER.length + 1)

        if (presented == null) {
            call.refuse(UNSUPPORTED_SCHEME)
            finish()
            return@intercept
        }

        when (val result = authenticator.authenticate(presented.trim(), Surface.REST, requestId(call))) {
            is AuthenticationResult.Authenticated -> call.attributes.put(ACTOR_CONTEXT, result.context)

            is AuthenticationResult.Rejected -> {
                call.refuse(result.reason.detail)
                finish()
            }
        }
    }
}

/**
 * Serialised here rather than through content negotiation, because the media type is part of what
 * makes it a problem document: RFC 9457 defines `application/problem+json`, and a gateway or client
 * keying on it does not recognise `application/json`.
 */
private suspend fun ApplicationCall.refuse(detail: String) {
    response.header(HttpHeaders.WWWAuthenticate, BEARER)
    val problem =
        ProblemDetail(
            type = PROBLEM_TYPE,
            title = PROBLEM_TITLE,
            status = HttpStatusCode.Unauthorized.value,
            code = UNAUTHENTICATED,
            detail = detail,
            instance = request.path(),
        )
    respondText(Json.encodeToString(problem), PROBLEM_JSON, HttpStatusCode.Unauthorized)
}
