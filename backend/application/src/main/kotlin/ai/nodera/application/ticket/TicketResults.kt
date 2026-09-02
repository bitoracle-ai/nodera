package ai.nodera.application.ticket

import ai.nodera.domain.permission.Capability
import ai.nodera.domain.ticket.ReadyTicket
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TransitionRefusal
import ai.nodera.domain.ticket.UnmetClosureRequirements

public sealed interface CreateTicketResult {
    public data class Created(
        public val ticket: Ticket,
    ) : CreateTicketResult

    public data class Denied(
        public val capability: Capability,
    ) : CreateTicketResult
}

/**
 * Expected outcomes as values, not exceptions — an adapter maps each to an HTTP status or an MCP
 * error code, and [GateFailed] carries the itemised refusal `docs/MCP.md` § 4 renders.
 *
 * [NotFound] is deliberately distinct from every other case: a ticket nobody could read is not a
 * ticket with nothing outstanding.
 */
public sealed interface TransitionResult {
    public data class Transitioned(
        public val ticket: Ticket,
    ) : TransitionResult

    public data class GateFailed(
        public val unmet: UnmetClosureRequirements,
    ) : TransitionResult

    public data class Refused(
        public val refusal: TransitionRefusal,
    ) : TransitionResult

    public data class Denied(
        public val capability: Capability,
    ) : TransitionResult

    public data object NotFound : TransitionResult
}

public sealed interface NextTicketResult {
    public data class Selected(
        public val ready: ReadyTicket,
    ) : NextTicketResult

    public data object NothingReady : NextTicketResult

    public data class Denied(
        public val capability: Capability,
    ) : NextTicketResult
}
