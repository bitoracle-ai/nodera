package ai.nodera.api.rest

import ai.nodera.application.error.ErrorCode
import ai.nodera.application.identity.AuthenticationResult
import ai.nodera.application.identity.CredentialAuthenticator
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.util.AttributeKey

private const val BEARER = "Bearer"

/**
 * Hyphenated, and that is load-bearing: [ai.nodera.domain.identity.SecretRedaction] replaces
 * `bearer` or `basic` followed by whitespace and a token, so "a bearer credential" left this as
 * "a bearer ***". `ProblemDetailTest` asserts every detail this surface emits survives redaction.
 */
internal const val UNSUPPORTED_SCHEME: String =
    "the Authorization header must carry a bearer-scheme credential"

private val ACTOR_CONTEXT = AttributeKey<ActorContext>("nodera.actorContext")

/**
 * The credential this request carried, or `null` if it carried none.
 *
 * On every path the contract secures there is no third state: something presented and unusable was
 * answered `401` before routing. On the [UNAUTHENTICATED_PATHS] there **is** — a header is ignored
 * there, so `null` can also mean "presented and deliberately not read". No route under those paths
 * asks, and one that did would have to say which it meant.
 */
public fun ApplicationCall.actorContext(): ActorContext? = attributes.getOrNull(ACTOR_CONTEXT)

/**
 * Turns an `Authorization` header into an [ActorContext] before routing, and refuses the request
 * when it cannot. It decides nothing: the surface is the only thing this file contributes.
 *
 * A header that is present and unusable is a `401` here rather than an anonymous request a route
 * later mistakes for a deliberate one. A request with no header is left alone.
 *
 * @param unauthenticated paths this never touches, because the contract gives them no security at
 *   all — [UNAUTHENTICATED_PATHS] says why refusing there breaks the client it is meant to protect.
 * @param requestId defaults to the correlation id [installRequestCorrelation] established, which is
 *   the one echoed on the response and the one the audit trail carries.
 */
public fun Application.installCredentialAuthentication(
    authenticator: CredentialAuthenticator,
    unauthenticated: Set<String> = UNAUTHENTICATED_PATHS,
    requestId: (ApplicationCall) -> RequestId = ApplicationCall::correlationId,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path() in unauthenticated) return@intercept
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

/** `WWW-Authenticate` on top of the shared problem document, because this refusal is about a scheme. */
private suspend fun ApplicationCall.refuse(detail: String) {
    response.header(HttpHeaders.WWWAuthenticate, BEARER)
    respondProblem(ErrorCode.UNAUTHENTICATED, detail)
}
