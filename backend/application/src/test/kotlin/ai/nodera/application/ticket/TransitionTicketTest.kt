package ai.nodera.application.ticket

import ai.nodera.application.audit.AuditEventSink
import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.permission.Directory
import ai.nodera.application.permission.PROJECT
import ai.nodera.application.permission.actor
import ai.nodera.application.permission.context
import ai.nodera.application.permission.engine
import ai.nodera.application.ticket.usecase.TransitionTicket
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.audit.AuditEvent
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.AcceptanceCriterion
import ai.nodera.domain.ticket.ClosureFacts
import ai.nodera.domain.ticket.ReviewRequirement
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketDraft
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.domain.ticket.TicketNumber
import ai.nodera.domain.ticket.TicketPrefix
import ai.nodera.domain.ticket.TicketPriority
import ai.nodera.domain.ticket.TicketResolution
import ai.nodera.domain.ticket.TicketState
import ai.nodera.domain.ticket.TicketStatus
import ai.nodera.domain.ticket.TransitionRefusal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.uuid.Uuid

private val MAINTAINER = actor(1)
private val KEY = TicketKey(TicketPrefix("core"), TicketNumber(12))

/**
 * Runs the block and nothing else.
 *
 * There is no database here, so there is nothing to mutate and nothing for CORE-02's completeness
 * harness to watch. That harness stays the proof of invariant #3, in `:persistence`, where the
 * transactions are real; this module exercises the branches a database cannot be made to produce.
 */
private object DirectUnitOfWork : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

private class RecordingSink : AuditEventSink {
    val events: MutableList<AuditEvent> = mutableListOf()

    override suspend fun append(event: AuditEvent) {
        events += event
    }
}

private fun ticket(status: TicketStatus): Ticket =
    Ticket(
        id = TicketId(Uuid.random()),
        projectId = PROJECT,
        key = KEY,
        title = "a ticket",
        priority = TicketPriority.P2,
        state = TicketState(status),
        reporter = MAINTAINER,
        assignee = null,
    )

private class StubTickets(
    private val ticket: Ticket?,
    private val moved: Boolean = false,
) : TicketReader,
    TicketWriter {
    var written: TicketState? = null

    override suspend fun byKey(
        projectId: ProjectId,
        key: TicketKey,
    ): Ticket? = ticket

    override suspend fun create(
        projectId: ProjectId,
        key: TicketKey,
        draft: TicketDraft,
        reporter: ActorId,
    ): Ticket = error("not part of this spec")

    /** [moved] models the row leaving [from] between the read and the write. */
    override suspend fun applyTransition(
        id: TicketId,
        from: TicketStatus,
        to: TicketState,
    ): Ticket? {
        written = to
        return if (moved) null else checkNotNull(ticket).copy(state = to)
    }
}

private class StubFacts(
    private val facts: ClosureFacts?,
) : ClosureFactsReader {
    override suspend fun facts(ticketId: TicketId): ClosureFacts? = facts
}

private fun useCase(
    tickets: StubTickets,
    facts: ClosureFactsReader,
    sink: RecordingSink,
    role: ProjectRole = ProjectRole.MAINTAINER,
): TransitionTicket =
    TransitionTicket(
        engine(Directory().member(MAINTAINER, role)),
        DirectUnitOfWork,
        AuditRecorder(sink),
        tickets,
        tickets,
        facts,
    )

class TransitionTicketTest :
    StringSpec({

        // The branch a database cannot be made to take: byKey runs first and the project context is
        // session-level, so the JDBC reader can never answer null here. The port permits it, so the
        // use case has to, and closing on empty facts is the fail-open this package exists to avoid.
        "a ticket whose closure facts cannot be read is not found, and is never closed" {
            val subject = ticket(TicketStatus.IN_REVIEW)
            val tickets = StubTickets(subject)
            val sink = RecordingSink()

            useCase(tickets, StubFacts(null), sink).transition(
                context(MAINTAINER),
                PROJECT,
                KEY,
                TicketStatus.CLOSED,
                TicketResolution.DONE,
            ) shouldBe TransitionResult.NotFound

            tickets.written shouldBe null
            sink.events.map { it.entry.outcome } shouldContainExactly listOf(AuditOutcome.FAILED)
            sink.events.map { it.entry.entityId } shouldContainExactly listOf(subject.id.value)
        }

        "an unmet criterion refuses closure and names it, without writing" {
            val tickets = StubTickets(ticket(TicketStatus.IN_REVIEW))
            val facts = ClosureFacts(listOf(AcceptanceCriterion(1, "unmet", met = false)), emptyList(), 1)
            val sink = RecordingSink()

            val unmet =
                useCase(tickets, StubFacts(facts), sink)
                    .transition(context(MAINTAINER), PROJECT, KEY, TicketStatus.CLOSED, TicketResolution.DONE)
                    .shouldBeInstanceOf<TransitionResult.GateFailed>()
                    .unmet

            unmet.acceptanceCriteria.map { it.ordinal } shouldContainExactly listOf(1)
            unmet.reviews shouldBe ReviewRequirement.PRESENT
            tickets.written shouldBe null
            sink.events.map { it.entry.outcome } shouldContainExactly listOf(AuditOutcome.FAILED)
        }

        // The row moved between the read and the write, so the decision was made against a status
        // the ticket no longer had. Writing anyway would land an edge the machine never allows.
        "a ticket that moved under the decision is refused rather than overwritten" {
            val subject = ticket(TicketStatus.IN_REVIEW)
            val tickets = StubTickets(subject, moved = true)
            val sink = RecordingSink()

            useCase(tickets, StubFacts(ClosureFacts(emptyList(), emptyList(), 1)), sink)
                .transition(context(MAINTAINER), PROJECT, KEY, TicketStatus.CLOSED, TicketResolution.DONE)
                .shouldBeInstanceOf<TransitionResult.Refused>()
                .refusal shouldBe TransitionRefusal.ConcurrentlyChanged(TicketStatus.IN_REVIEW)

            sink.events.map { it.entry.outcome } shouldContainExactly listOf(AuditOutcome.FAILED)
            // V4 indexes the trail on (entity_type, entity_id). A refusal about a ticket that was
            // read has to be reachable from it, or a lost race is invisible in that ticket's history.
            sink.events.map { it.entry.entityId } shouldContainExactly listOf(subject.id.value)
        }

        "a gate that is satisfied lets the transition through" {
            val tickets = StubTickets(ticket(TicketStatus.IN_REVIEW))
            val sink = RecordingSink()

            useCase(tickets, StubFacts(ClosureFacts(emptyList(), emptyList(), 1)), sink)
                .transition(context(MAINTAINER), PROJECT, KEY, TicketStatus.CLOSED, TicketResolution.DONE)
                .shouldBeInstanceOf<TransitionResult.Transitioned>()

            tickets.written shouldBe TicketState(TicketStatus.CLOSED, TicketResolution.DONE)
            sink.events.map { it.entry.outcome } shouldContainExactly listOf(AuditOutcome.SUCCESS)
        }

        // An observer holds ticket.read only, so neither verb is present and the first one missing
        // is what the caller is told.
        "a caller without the transition capability is denied, and nothing is read or written" {
            val tickets = StubTickets(ticket(TicketStatus.OPEN))
            val sink = RecordingSink()

            useCase(tickets, StubFacts(null), sink, role = ProjectRole.OBSERVER).transition(
                context(MAINTAINER),
                PROJECT,
                KEY,
                TicketStatus.IN_PROGRESS,
                null,
            ) shouldBe TransitionResult.Denied(Capability.TICKET_TRANSITION)

            tickets.written shouldBe null
            sink.events.map { it.entry.outcome } shouldContainExactly listOf(AuditOutcome.DENIED)
        }
    })
