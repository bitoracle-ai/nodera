package ai.nodera.persistence.collaboration

import ai.nodera.application.collaboration.ResolveFindingResult
import ai.nodera.application.collaboration.SubmitReviewResult
import ai.nodera.application.ticket.ClosureFactsReader
import ai.nodera.application.ticket.TransitionResult
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.collaboration.Finding
import ai.nodera.domain.collaboration.FindingDraft
import ai.nodera.domain.collaboration.ReviewVerdict
import ai.nodera.domain.ticket.ClosureFacts
import ai.nodera.domain.ticket.FindingSeverity
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketResolution
import ai.nodera.domain.ticket.TicketStatus
import ai.nodera.persistence.Binding
import ai.nodera.persistence.audit.context
import ai.nodera.persistence.currentConnection
import ai.nodera.persistence.ticket.JdbcClosureFacts
import ai.nodera.persistence.ticket.closureFacts
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import kotlin.uuid.toJavaUuid

private const val GATE_LOCK = "select 1 from ticket where id"

private const val TRANSITION_WRITE = "update ticket set"

private const val MID_CLOSURE = "arrived mid-closure"

/**
 * The gate's reader with its row lock removed, and nothing else changed.
 *
 * The three reads come from the production adapter rather than from a copy here, so this stand-in
 * differs from it in exactly one statement and cannot drift into modelling something else — CORE-03's
 * round-3 finding was a stand-in that had quietly lost a second branch.
 */
private class UnlockedClosureFacts : ClosureFactsReader {
    override suspend fun facts(ticketId: TicketId): ClosureFacts? {
        val connection = requireNotNull(currentConnection()) { "no transaction is open" }
        val visible =
            connection.prepareStatement("select 1 from ticket where id = ?").use { statement ->
                Binding(statement).uuid(ticketId.value)
                statement.executeQuery().use { it.next() }
            }
        return if (visible) connection.closureFacts(ticketId) else null
    }
}

class ClosureFromReviewTest :
    StringSpec({

        // AC5, and it is the case a "latest review" implementation gets wrong: round 2 is clean, and
        // round 1's unresolved blocking finding still holds the gate shut.
        "an unresolved blocking finding from round 1 blocks closure after a clean round 2" {
            val project = seedCollaborationProject()
            val ticketId = project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val closer = context(project.reporterId, UUID.randomUUID())

            val blocking = useCases.roundOne(project, FindingSeverity.BLOCKING)
            useCases.submitReview.submit(
                context(project.agentReviewerId, UUID.randomUUID(), kind = ActorKind.AGENT),
                project.id,
                project.key(1),
                ReviewVerdict.APPROVED,
                summary = "clean",
            )

            val refusal =
                useCases.transition
                    .transition(closer, project.id, project.key(1), TicketStatus.CLOSED, TicketResolution.DONE)
                    .shouldBeInstanceOf<TransitionResult.GateFailed>()

            refusal.unmet.unresolvedBlockingFindings.map { it.title } shouldContainExactly listOf("still open")
            ticketStatus(ticketId) shouldBe "in_review"

            // Resolving it is the only thing that changes, and the same closure then passes.
            useCases.resolveFinding
                .resolve(closer, project.id, blocking.id, note = "fixed in round 2")
                .shouldBeInstanceOf<ResolveFindingResult.Resolved>()

            useCases.transition
                .transition(closer, project.id, project.key(1), TicketStatus.CLOSED, TicketResolution.DONE)
                .shouldBeInstanceOf<TransitionResult.Transitioned>()
            ticketStatus(ticketId) shouldBe "closed"
        }

        // Severity is read rather than assumed: an unresolved non-blocking finding never held anything.
        "an unresolved non-blocking finding does not block closure" {
            val project = seedCollaborationProject()
            val ticketId = project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())

            useCases.roundOne(project, FindingSeverity.NON_BLOCKING)

            useCases.transition
                .transition(
                    context(project.reporterId, UUID.randomUUID()),
                    project.id,
                    project.key(1),
                    TicketStatus.CLOSED,
                    TicketResolution.DONE,
                ).shouldBeInstanceOf<TransitionResult.Transitioned>()
            ticketStatus(ticketId) shouldBe "closed"
        }

        // Resolving twice is not an error and is not a second resolution: the first resolver's note
        // and identity stand.
        "a finding already resolved answers with that, not with a second resolution" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val finding = useCases.roundOne(project, FindingSeverity.BLOCKING)
            val ctx = context(project.reporterId, UUID.randomUUID())

            useCases.resolveFinding.resolve(ctx, project.id, finding.id, note = "first")
            useCases.resolveFinding.resolve(ctx, project.id, finding.id, note = "second") shouldBe
                ResolveFindingResult.AlreadyResolved

            findingResolver(finding.id.value.toJavaUuid()).second shouldBe "first"
        }

        // The race CORE-04 makes reachable, because CORE-04 is the first package that can write a
        // blocking finding. The submission holds the ticket row; the closure has to wait for it, and
        // then sees the finding it was about to close over.
        "a closure racing an open review submission waits for it and is refused" {
            val project = seedCollaborationProject()
            val ticketId = project.seedTicket()

            val (closure, submission) = raceClosureBehindReview(project, JdbcClosureFacts(), GATE_LOCK)

            submission.shouldBeInstanceOf<SubmitReviewResult.Submitted>()
            closure
                .shouldBeInstanceOf<TransitionResult.GateFailed>()
                .unmet.unresolvedBlockingFindings
                .map { it.title } shouldContainExactly listOf(MID_CLOSURE)
            ticketStatus(ticketId) shouldBe "in_review"
        }

        // The paired negative. Without the lock the gate reads past the open submission, sees only
        // the clean first round, and the compare-and-set still matches because nothing touched the
        // ticket row — so the ticket closes as done carrying an unresolved blocking finding, which is
        // exactly what invariant #8 forbids. The closure then blocks on its own write instead, which
        // is why this variant waits on a different statement.
        "the same race without the gate's row lock closes over the finding" {
            val project = seedCollaborationProject()
            val ticketId = project.seedTicket()

            val (closure, submission) = raceClosureBehindReview(project, UnlockedClosureFacts(), TRANSITION_WRITE)

            submission.shouldBeInstanceOf<SubmitReviewResult.Submitted>()
            closure.shouldBeInstanceOf<TransitionResult.Transitioned>()
            ticketStatus(ticketId) shouldBe "closed"
        }
    })

/** Round 1 with one finding of [severity], by the human reviewer. Returns the finding. */
private suspend fun CollaborationUseCases.roundOne(
    project: CollaborationProject,
    severity: FindingSeverity,
): Finding =
    submitReview
        .submit(
            context(project.humanReviewerId, UUID.randomUUID()),
            project.id,
            project.key(1),
            ReviewVerdict.CHANGES_REQUIRED,
            summary = "round one",
            findings = listOf(FindingDraft(severity, "still open")),
        ).shouldBeInstanceOf<SubmitReviewResult.Submitted>()
        .review.findings
        .single()

/**
 * A clean round 1, committed before the race starts.
 *
 * Without it the gate would refuse for want of any review at all, and the race would pass for a
 * reason that has nothing to do with the lock it is about.
 */
private suspend fun CollaborationUseCases.cleanFirstRound(project: CollaborationProject) {
    submitReview
        .submit(
            context(project.humanReviewerId, UUID.randomUUID()),
            project.id,
            project.key(1),
            ReviewVerdict.APPROVED,
            summary = "clean first round",
        ).shouldBeInstanceOf<SubmitReviewResult.Submitted>()
}

/**
 * A closure that starts while a review submission is still open, both on one ticket.
 *
 * A clean round 1 is committed first, so the gate's answer turns on the racing round alone rather
 * than on there being no review at all. The submission is the holder — `nextRound` locks the ticket
 * row — and it commits only once a backend is blocked on [blocksOn], which is the statement the
 * closure reaches first: the gate's lock when the reader takes one, its own write when it does not.
 */
private suspend fun raceClosureBehindReview(
    project: CollaborationProject,
    facts: ClosureFactsReader,
    blocksOn: String,
): Pair<TransitionResult, SubmitReviewResult> =
    coroutineScope {
        val reviewing = CollaborationUseCases(project.scope, project.id, project.maintainers())
        val closing = CollaborationUseCases(project.scope, project.id, project.maintainers(), closureFacts = facts)

        reviewing.cleanFirstRound(project)

        val submitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val submission =
            async(Dispatchers.IO) {
                reviewing.unitOfWork.inTransaction {
                    val outcome =
                        reviewing.submitReview.submit(
                            context(project.agentReviewerId, UUID.randomUUID(), kind = ActorKind.AGENT),
                            project.id,
                            project.key(1),
                            ReviewVerdict.CHANGES_REQUIRED,
                            findings = listOf(FindingDraft(FindingSeverity.BLOCKING, MID_CLOSURE)),
                        )
                    submitted.complete(Unit)
                    release.await()
                    outcome
                }
            }
        submitted.await()

        val closure =
            async(Dispatchers.IO) {
                closing.transition.transition(
                    context(project.reporterId, UUID.randomUUID()),
                    project.id,
                    project.key(1),
                    TicketStatus.CLOSED,
                    TicketResolution.DONE,
                )
            }

        awaitBlockedOn(closure, blocksOn)
        release.complete(Unit)

        closure.await() to submission.await()
    }
