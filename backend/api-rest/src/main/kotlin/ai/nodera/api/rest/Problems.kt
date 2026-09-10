package ai.nodera.api.rest

import ai.nodera.application.error.ErrorCode
import ai.nodera.domain.identity.SecretRedaction
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable

private const val TYPE_BASE = "https://nodera.dev/errors/"

internal val PROBLEM_JSON: ContentType = ContentType("application", "problem+json")

/** The one detail two route files share, declared once so the sweep over them cannot miss a copy. */
internal const val MALFORMED_BODY = "the request body is not the shape this endpoint accepts"

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

/** The REST half of the shared taxonomy: what each code becomes on this wire, and nothing else. */
internal val ErrorCode.status: HttpStatusCode
    get() =
        when (this) {
            ErrorCode.UNAUTHENTICATED -> HttpStatusCode.Unauthorized
            ErrorCode.FORBIDDEN -> HttpStatusCode.Forbidden
            ErrorCode.NOT_FOUND -> HttpStatusCode.NotFound
            ErrorCode.VALIDATION_FAILED -> HttpStatusCode.UnprocessableEntity
            ErrorCode.CLOSURE_GATE_FAILED -> HttpStatusCode.Conflict
            ErrorCode.DEPENDENCY_CYCLE -> HttpStatusCode.Conflict
            ErrorCode.IDEMPOTENCY_CONFLICT -> HttpStatusCode.Conflict
            ErrorCode.RATE_LIMITED -> HttpStatusCode.TooManyRequests
        }

internal val ErrorCode.title: String
    get() =
        when (this) {
            ErrorCode.UNAUTHENTICATED -> "Unauthenticated"
            ErrorCode.FORBIDDEN -> "Forbidden"
            ErrorCode.NOT_FOUND -> "Not found"
            ErrorCode.VALIDATION_FAILED -> "Validation failed"
            ErrorCode.CLOSURE_GATE_FAILED -> "Ticket cannot be closed"
            ErrorCode.DEPENDENCY_CYCLE -> "Dependency cycle"
            ErrorCode.IDEMPOTENCY_CONFLICT -> "Idempotency conflict"
            ErrorCode.RATE_LIMITED -> "Rate limited"
        }

internal val ErrorCode.type: String get() = TYPE_BASE + wire.replace('_', '-')

/**
 * Serialised here rather than through content negotiation: the media type is half of what RFC 9457
 * defines, and a gateway keying on it does not recognise `application/json`.
 *
 * Both caller-visible strings pass [SecretRedaction] — `instance` is the request's own path, so a
 * token put where an identifier belongs would otherwise come back. `docs/API_CONTRACT.md` § 4.
 *
 * @param detail never a quotation of what was presented.
 */
public suspend fun ApplicationCall.respondProblem(
    code: ErrorCode,
    detail: String,
) {
    val problem =
        ProblemDetail(
            type = code.type,
            title = code.title,
            status = code.status.value,
            code = code.wire,
            detail = SecretRedaction.redact(detail),
            instance = SecretRedaction.redact(request.path()),
        )
    respondText(noderaJson.encodeToString(problem), PROBLEM_JSON, code.status)
}
