package ai.nodera.domain.actor

import kotlin.uuid.Uuid

/** Which surface a request arrived through. Recorded on every audit event. */
public enum class Surface {
    WEB,
    REST,
    MCP,
    SYSTEM,
}

/**
 * Correlates every audit event produced by one request.
 *
 * A `Uuid` because `audit_event.request_id` is `uuid not null`: a string that is not one would
 * type-check and then fail on the last statement of the mutation's own transaction.
 */
@JvmInline
public value class RequestId(
    public val value: Uuid,
)

/**
 * Who is acting, through what, and on whose instruction.
 *
 * **The first parameter of every use case, always.** Not a thread-local, not a coroutine context
 * element, not a request-scoped singleton — making it a parameter is what makes "who is acting"
 * impossible to forget and the permission check impossible to skip silently. Kotlin's context
 * parameters are a deliberate no for the same reason: their value is that the dependency stops
 * appearing at the call site, and the call site is exactly where a reviewer of an audit-sensitive
 * path needs to see it (`skills/backend-kotlin.md`).
 *
 * @param kind carried for display and audit only (invariant AU2 denormalises it onto every event so
 *   the trail answers "human or agent?" without joining a table whose contents may have changed).
 *   Nothing reads it to decide a permission.
 * @param onBehalfOf the delegation chain (invariant AU4) — the actor whose request caused this one.
 *   This is what turns "the agent closed the ticket" into "the agent closed the ticket, acting on
 *   Anna's instruction, through the MCP tool `ticket_transition`".
 */
public data class ActorContext(
    public val actorId: ActorId,
    public val kind: ActorKind,
    public val surface: Surface,
    public val onBehalfOf: ActorId?,
    public val requestId: RequestId,
)
