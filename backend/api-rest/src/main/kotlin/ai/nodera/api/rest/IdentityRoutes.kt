package ai.nodera.api.rest

import ai.nodera.application.error.ErrorCode
import ai.nodera.application.identity.CredentialTerms
import ai.nodera.application.identity.RevokeCredentialResult
import ai.nodera.application.identity.usecase.IssuePersonalAccessToken
import ai.nodera.application.identity.usecase.RevokeCredential
import ai.nodera.application.identity.usecase.WhoAmI
import ai.nodera.application.identity.usecase.WhoAmIResult
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.identity.CredentialId
import ai.nodera.domain.identity.CredentialLabel
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal const val NO_CREDENTIAL = "this endpoint requires a credential; none was presented"

internal const val ACTOR_GONE = "no actor matches the presented credential"
internal const val CREDENTIAL_NOT_FOUND = "no such credential"
internal const val LABEL_INVALID = "label must be 1..200 characters and not blank"
internal const val EXPIRY_INVALID = "expiresAt must be an RFC 3339 timestamp in the future"

/**
 * The caller's own actor and the caller's own credentials — `docs/API_CONTRACT.md` § 3.
 *
 * Nothing here resolves a project, so nothing here asks
 * [ai.nodera.application.permission.PermissionService] anything: a capability is held *in* a project.
 *
 * @param clock used only to refuse an expiry that has already passed.
 */
public fun Route.identityRoutes(
    whoAmI: WhoAmI,
    issueToken: IssuePersonalAccessToken,
    revokeCredential: RevokeCredential,
    clock: Clock,
) {
    route("/api/v1/me") {
        get { call.answerWhoAmI(whoAmI) }
        route("/credentials") {
            post { call.answerIssue(issueToken, clock) }
            delete("/{id}") { call.answerRevoke(revokeCredential) }
        }
    }
}

private suspend fun ApplicationCall.answerWhoAmI(whoAmI: WhoAmI) {
    val ctx = authenticated() ?: return
    when (val result = whoAmI.of(ctx)) {
        is WhoAmIResult.Known -> respond(HttpStatusCode.OK, result.profile.toPayload())
        WhoAmIResult.Unknown -> respondProblem(ErrorCode.NOT_FOUND, ACTOR_GONE)
    }
}

private suspend fun ApplicationCall.answerIssue(
    issueToken: IssuePersonalAccessToken,
    clock: Clock,
) {
    val ctx = authenticated() ?: return
    val body =
        receiveOrNull<IssueCredentialRequest>()
            ?: return respondProblem(ErrorCode.VALIDATION_FAILED, MALFORMED_BODY)
    val label =
        credentialLabel(body.label)
            ?: return respondProblem(ErrorCode.VALIDATION_FAILED, LABEL_INVALID)
    val expiresAt =
        futureInstant(body.expiresAt, clock.now())
            ?: return respondProblem(ErrorCode.VALIDATION_FAILED, EXPIRY_INVALID)

    val issued = issueToken.issue(ctx, CredentialTerms(label, expiresAt))
    respond(
        HttpStatusCode.Created,
        IssuedCredentialPayload(
            id =
                issued.credential.id.value
                    .toString(),
            label = issued.credential.label.value,
            token = issued.plaintext.value,
            expiresAt =
                checkNotNull(issued.credential.expiresAt) {
                    "a credential issued with an expiry came back without one"
                }.toString(),
        ),
    )
}

/**
 * An identifier that is not one answers `404` rather than `422`, so that **one identifier gets one
 * answer** whether the row is absent, somebody else's, or not an identifier at all. `422` here
 * would say "that one is well-formed", which is the oracle the `404` exists to close.
 */
private suspend fun ApplicationCall.answerRevoke(revokeCredential: RevokeCredential) {
    val ctx = authenticated() ?: return
    val id =
        parameters["id"]?.let(::credentialId)
            ?: return respondProblem(ErrorCode.NOT_FOUND, CREDENTIAL_NOT_FOUND)

    when (revokeCredential.revoke(ctx, id)) {
        is RevokeCredentialResult.Revoked -> respond(HttpStatusCode.NoContent)
        RevokeCredentialResult.NotFound -> respondProblem(ErrorCode.NOT_FOUND, CREDENTIAL_NOT_FOUND)
    }
}

/**
 * The context the middleware established, or a refusal already written. A request that presented no
 * credential reaches here, and these routes are all about the caller — so it is refused, not read.
 */
internal suspend fun ApplicationCall.authenticated(): ActorContext? {
    val ctx = actorContext()
    if (ctx == null) respondProblem(ErrorCode.UNAUTHENTICATED, NO_CREDENTIAL)
    return ctx
}

/**
 * A body that will not deserialise is `422` with a fixed message — the deserialiser's own message
 * quotes what it was reading, and one of the bodies here is a refresh token. Cancellation is not a
 * malformed body and is rethrown.
 */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? =
    runCatching { receive<T>() }
        .getOrElse { failure -> if (failure is CancellationException) throw failure else null }

internal fun credentialLabel(raw: String): CredentialLabel? = runCatching { CredentialLabel(raw) }.getOrNull()

internal fun credentialId(raw: String): CredentialId? = runCatching { CredentialId(Uuid.parse(raw)) }.getOrNull()

/** Refused when it has already passed: a token minted expired is a token that never worked. */
internal fun futureInstant(
    raw: String,
    now: Instant,
): Instant? = runCatching { Instant.parse(raw) }.getOrNull()?.takeIf { it > now }
