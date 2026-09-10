package ai.nodera.api.rest

import ai.nodera.application.error.ErrorCode
import ai.nodera.application.identity.RefreshSession
import ai.nodera.application.identity.SignInResult
import ai.nodera.domain.actor.Surface
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Turning a credential into a session — `docs/API_CONTRACT.md` § 3, which also says why only
 * rotation is served and what blocks the other two.
 *
 * **An agent never comes here.** It presents `nod_pat_…` on the request it wanted to make.
 */
public fun Route.authenticationRoutes(refreshSession: RefreshSession) {
    route("/api/v1/auth") {
        post("/refresh") {
            val body =
                call.receiveOrNull<RefreshRequest>()
                    ?: return@post call.respondProblem(ErrorCode.VALIDATION_FAILED, MALFORMED_BODY)

            when (val result = refreshSession.refresh(body.refreshToken, Surface.REST, call.correlationId())) {
                is SignInResult.SignedIn -> call.respond(HttpStatusCode.OK, result.session.toPayload())
                // Named, because the caller already holds this credential; an unknown selector and a
                // wrong verifier already share one answer upstream.
                is SignInResult.Rejected -> call.respondProblem(ErrorCode.UNAUTHENTICATED, result.reason.detail)
            }
        }
    }
}
