package ai.nodera.persistence.collaboration

import ai.nodera.application.collaboration.ResolveFindingResult
import ai.nodera.application.collaboration.ReviewRecordResult
import ai.nodera.application.collaboration.ReviewRoundAllocator
import ai.nodera.application.collaboration.SubmitReviewResult
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.collaboration.FindingDraft
import ai.nodera.domain.collaboration.ReviewRefusal
import ai.nodera.domain.collaboration.ReviewRound
import ai.nodera.domain.collaboration.ReviewVerdict
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.ticket.FindingSeverity
import ai.nodera.domain.ticket.TicketId
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.audit.context
import ai.nodera.persistence.currentConnection
import ai.nodera.persistence.ticket.auditEntities
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import kotlin.uuid.toJavaUuid

private const val LOCKING_STATEMENT = "select id from ticket where id"

/** Both kinds, one rule: the case runs twice so a rule that read the kind would pass only half. */
private class AssigneeCase(
    val description: String,
    val kind: ActorKind,
    val actor: (CollaborationProject) -> UUID,
)

private val ASSIGNEES =
    listOf(
        AssigneeCase("a human", ActorKind.HUMAN) { it.humanReviewerId },
        AssigneeCase("an agent", ActorKind.AGENT) { it.agentReviewerId },
    )

/**
 * The round allocator with the ticket lock removed, and nothing else changed.
 *
 * Committed rather than run once by hand: a lock nobody has watched fail is an assertion about a
 * lock. Driven through the same deterministic race as the real one, it turns the second submission
 * into a unique-constraint failure instead of round 2.
 */
private class UnlockedReviewRounds : ReviewRoundAllocator {
    override suspend fun nextRound(ticketId: TicketId): ReviewRound {
        val connection = requireNotNull(currentConnection()) { "no transaction is open" }
        return connection.roundAfterExisting(ticketId)
    }
}

class ReviewRecordTest :
    StringSpec({

        // AC3, and the half of R1 that always applies.
        ASSIGNEES.forEach { case ->
            "${case.description} assignee cannot review the ticket they hold" {
                val project = seedCollaborationProject()
                val ticket = project.seedTicket(assignee = case.actor(project))
                val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())

                useCases.submitReview.submit(
                    context(case.actor(project), UUID.randomUUID(), kind = case.kind),
                    project.id,
                    project.key(1),
                    ReviewVerdict.APPROVED,
                ) shouldBe SubmitReviewResult.NotIndependent(ReviewRefusal.ReviewerIsAssignee)

                reviewRounds(ticket) shouldContainExactly emptyList()
            }
        }

        // V4 indexes the trail on (entity_type, entity_id): a refusal that wrote no review must not
        // file the ticket's id under entity_type 'review'.
        "a refused submission records its refusal without inventing a review entity" {
            val project = seedCollaborationProject()
            project.seedTicket(assignee = project.humanReviewerId)
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val refused = UUID.randomUUID()

            useCases.submitReview.submit(
                context(project.humanReviewerId, refused),
                project.id,
                project.key(1),
                ReviewVerdict.APPROVED,
            ) shouldBe SubmitReviewResult.NotIndependent(ReviewRefusal.ReviewerIsAssignee)

            SchemaFixture.asOwner { it.auditEntities(refused) } shouldContainExactly listOf(null)
        }

        // The other half, and the asymmetry V3's trigger draws: the reporter is the author only
        // while nobody else holds the work.
        "the reporter cannot review an unassigned ticket but may review an assigned one" {
            val project = seedCollaborationProject()
            val unassigned = project.seedTicket(number = 1, assignee = null)
            val assigned = project.seedTicket(number = 2, assignee = project.assigneeId)
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())

            useCases.submitReview.submit(
                context(project.reporterId, UUID.randomUUID()),
                project.id,
                project.key(1),
                ReviewVerdict.APPROVED,
            ) shouldBe SubmitReviewResult.NotIndependent(ReviewRefusal.ReviewerIsReporterOfUnassigned)
            reviewRounds(unassigned) shouldContainExactly emptyList()

            useCases.submitReview
                .submit(
                    context(project.reporterId, UUID.randomUUID()),
                    project.id,
                    project.key(2),
                    ReviewVerdict.APPROVED,
                ).shouldBeInstanceOf<SubmitReviewResult.Submitted>()
            reviewRounds(assigned) shouldContainExactly listOf(1)
        }

        // AC4. Nothing anywhere takes a latest: both rounds come back, in order, disagreeing.
        "a round-2 verdict contradicting round 1 leaves both readable" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())

            useCases.submitReview.submit(
                context(project.humanReviewerId, UUID.randomUUID()),
                project.id,
                project.key(1),
                ReviewVerdict.CHANGES_REQUIRED,
                summary = "not yet",
                findings = listOf(FindingDraft(FindingSeverity.BLOCKING, "the gate is clickable")),
            )
            useCases.submitReview.submit(
                context(project.agentReviewerId, UUID.randomUUID(), kind = ActorKind.AGENT),
                project.id,
                project.key(1),
                ReviewVerdict.APPROVED,
                summary = "fixed",
            )

            val record =
                useCases.listReviews
                    .rounds(context(project.reporterId, UUID.randomUUID()), project.id, project.key(1))
                    .shouldBeInstanceOf<ReviewRecordResult.Rounds>()
                    .reviews

            record.map { it.round.value } shouldContainExactly listOf(1, 2)
            record.map { it.verdict } shouldContainExactly
                listOf(ReviewVerdict.CHANGES_REQUIRED, ReviewVerdict.APPROVED)
            record.first().reviewer.kind shouldBe ActorKind.HUMAN
            record.last().reviewer.kind shouldBe ActorKind.AGENT
            record.first().findings.map { it.title } shouldContainExactly listOf("the gate is clickable")
            record.last().findings shouldHaveSize 0
        }

        // The lock, put on trial: the contender starts only once the holder has allocated, and the
        // holder commits only once Postgres reports a backend blocked on the locking statement.
        "a submission racing an open transaction becomes round 2 rather than colliding" {
            val project = seedCollaborationProject()
            val ticket = project.seedTicket()

            val (first, second) = raceSubmissions(project, JdbcReviewRepository())

            first.shouldBeInstanceOf<SubmitReviewResult.Submitted>().review.round shouldBe ReviewRound(1)
            second.shouldBeInstanceOf<SubmitReviewResult.Submitted>().review.round shouldBe ReviewRound(2)
            reviewRounds(ticket) shouldContainExactly listOf(1, 2)
        }

        // The paired negative for the line above. Same race, same fixture, no lock: both read round
        // 1 and the second submission dies on `unique (ticket_id, round)`.
        "the same race without the ticket lock collides on the round" {
            val project = seedCollaborationProject()
            val ticket = project.seedTicket()

            val failure =
                shouldThrowAny {
                    raceSubmissions(project, UnlockedReviewRounds(), blocksOn = "insert into review")
                }

            failure.message.orEmpty() shouldContain "review_ticket_id_round_key"
            reviewRounds(ticket) shouldContainExactly listOf(1)
        }

        // Round 1's fix, pinned. `submit` sorts what it returns the way `rounds` will order it;
        // nothing else in the suite looks at either key, so without this the two could drift apart
        // and no test would notice.
        "the findings a submission returns are in the order the record reads them back" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())

            val submitted =
                useCases.submitReview
                    .submit(
                        context(project.humanReviewerId, UUID.randomUUID()),
                        project.id,
                        project.key(1),
                        ReviewVerdict.CHANGES_REQUIRED,
                        findings = (1..8).map { FindingDraft(FindingSeverity.NON_BLOCKING, "finding $it") },
                    ).shouldBeInstanceOf<SubmitReviewResult.Submitted>()
                    .review.findings
                    .map { it.id }

            val read =
                useCases.listReviews
                    .rounds(context(project.reporterId, UUID.randomUUID()), project.id, project.key(1))
                    .shouldBeInstanceOf<ReviewRecordResult.Rounds>()
                    .reviews
                    .single()
                    .findings
                    .map { it.id }

            read shouldContainExactly submitted
        }

        "concurrent resolutions leave one winner, and the row carries that winner's note" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())
            val finding =
                useCases.submitReview
                    .submit(
                        context(project.humanReviewerId, UUID.randomUUID()),
                        project.id,
                        project.key(1),
                        ReviewVerdict.CHANGES_REQUIRED,
                        findings = listOf(FindingDraft(FindingSeverity.BLOCKING, "one to fix")),
                    ).shouldBeInstanceOf<SubmitReviewResult.Submitted>()
                    .review.findings
                    .single()

            val resolvers = listOf(project.reporterId, project.humanReviewerId)
            val gate = CyclicBarrier(resolvers.size)
            val outcomes =
                coroutineScope {
                    resolvers
                        .map { actor ->
                            async(Dispatchers.IO) {
                                gate.await()
                                actor to
                                    useCases.resolveFinding.resolve(
                                        context(actor, UUID.randomUUID()),
                                        project.id,
                                        finding.id,
                                        note = "resolved by $actor",
                                    )
                            }
                        }.awaitAll()
                }

            outcomes.map { it.second }.filterIsInstance<ResolveFindingResult.Resolved>() shouldHaveSize 1
            outcomes.map { it.second }.filterIsInstance<ResolveFindingResult.AlreadyResolved>() shouldHaveSize 1

            // Against the actor whose call actually won, not against whatever the row happens to
            // hold: reading the resolver out of the row and then checking the note against it only
            // proves the two columns agree, which an unconditional second write also satisfies.
            val winner = outcomes.single { it.second is ResolveFindingResult.Resolved }.first
            findingResolver(finding.id.value.toJavaUuid()) shouldBe (winner to "resolved by $winner")
        }

        // Invariant #5 again, on the id-addressed write of this half of the package.
        "a caller permitted in one project cannot resolve a finding in another" {
            val permitted = seedCollaborationProject()
            val other = seedCollaborationProject()
            permitted.seedTicket()
            other.seedTicket()
            val bothScopes = permitted.scope + other.scope

            val finding =
                CollaborationUseCases(bothScopes, other.id, other.maintainers())
                    .submitReview
                    .submit(
                        context(other.humanReviewerId, UUID.randomUUID()),
                        other.id,
                        other.key(1),
                        ReviewVerdict.CHANGES_REQUIRED,
                        findings = listOf(FindingDraft(FindingSeverity.BLOCKING, "theirs to fix")),
                    ).shouldBeInstanceOf<SubmitReviewResult.Submitted>()
                    .review.findings
                    .single()

            CollaborationUseCases(bothScopes, permitted.id, permitted.maintainers())
                .resolveFinding
                .resolve(
                    context(permitted.reporterId, UUID.randomUUID()),
                    permitted.id,
                    finding.id,
                ) shouldBe ResolveFindingResult.NotFound

            findingResolver(finding.id.value.toJavaUuid()).first shouldBe null
        }

        // The read use case refuses too, and its refusal is the only thing it writes.
        "a caller with no membership is refused the review record, and the refusal is recorded" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val roles = project.maintainers() - project.strangerId
            val denied = UUID.randomUUID()

            CollaborationUseCases(project.scope, project.id, roles)
                .listReviews
                .rounds(
                    context(project.strangerId, denied),
                    project.id,
                    project.key(1),
                ) shouldBe ReviewRecordResult.Denied(Capability.TICKET_READ)
            outcomesOf(denied) shouldContainExactly listOf("denied")
        }

        "the review record of a ticket the caller cannot see is not an empty record" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val ctx = context(project.reporterId, UUID.randomUUID())

            CollaborationUseCases(emptyList(), project.id, project.maintainers())
                .listReviews
                .rounds(ctx, project.id, project.key(1)) shouldBe ReviewRecordResult.NotFound

            CollaborationUseCases(project.scope, project.id, project.maintainers())
                .listReviews
                .rounds(ctx, project.id, project.key(1)) shouldBe ReviewRecordResult.Rounds(emptyList())
        }

        "a submission and a resolution each record exactly one audit event, and a denial records one" {
            val project = seedCollaborationProject()
            project.seedTicket()
            val useCases = CollaborationUseCases(project.scope, project.id, project.maintainers())

            val submitted = UUID.randomUUID()
            val finding =
                useCases.submitReview
                    .submit(
                        context(project.humanReviewerId, submitted),
                        project.id,
                        project.key(1),
                        ReviewVerdict.CHANGES_REQUIRED,
                        findings =
                            listOf(
                                FindingDraft(FindingSeverity.BLOCKING, "one"),
                                FindingDraft(FindingSeverity.NON_BLOCKING, "two"),
                            ),
                    ).shouldBeInstanceOf<SubmitReviewResult.Submitted>()
                    .review.findings
                    .first()
            // Three rows written — a review and two findings — and one event, which is what the
            // completeness harness insists on for a use case.
            outcomesOf(submitted) shouldContainExactly listOf("success")

            val resolved = UUID.randomUUID()
            useCases.resolveFinding.resolve(context(project.reporterId, resolved), project.id, finding.id)
            outcomesOf(resolved) shouldContainExactly listOf("success")

            // Both denied paths, and the outcome rather than the row count: a success would satisfy
            // a count too.
            val contributors = project.maintainers() + (project.strangerId to ProjectRole.CONTRIBUTOR)
            val denied = CollaborationUseCases(project.scope, project.id, contributors)

            val onSubmit = UUID.randomUUID()
            denied.submitReview.submit(
                context(project.strangerId, onSubmit),
                project.id,
                project.key(1),
                ReviewVerdict.APPROVED,
            ) shouldBe SubmitReviewResult.Denied(Capability.REVIEW_SUBMIT)
            outcomesOf(onSubmit) shouldContainExactly listOf("denied")

            val onResolve = UUID.randomUUID()
            denied.resolveFinding.resolve(
                context(project.strangerId, onResolve),
                project.id,
                finding.id,
            ) shouldBe ResolveFindingResult.Denied(Capability.REVIEW_SUBMIT)
            outcomesOf(onResolve) shouldContainExactly listOf("denied")
            // Denied, so nothing moved: the row still names the actor who actually resolved it.
            findingResolver(finding.id.value.toJavaUuid()).first shouldBe project.reporterId
        }
    })

/**
 * Two submissions on one ticket, the second starting while the first transaction is still open.
 *
 * Deterministic in both directions: the contender starts only once the holder has allocated its
 * round, and the holder commits only once a backend is blocked on [blocksOn].
 */
private suspend fun raceSubmissions(
    project: CollaborationProject,
    allocator: ReviewRoundAllocator,
    blocksOn: String = LOCKING_STATEMENT,
): Pair<SubmitReviewResult, SubmitReviewResult> =
    coroutineScope {
        val useCases =
            CollaborationUseCases(project.scope, project.id, project.maintainers(), roundAllocator = allocator)
        val allocated = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holder =
            async(Dispatchers.IO) {
                useCases.unitOfWork.inTransaction {
                    val outcome =
                        useCases.submitReview.submit(
                            context(project.humanReviewerId, UUID.randomUUID()),
                            project.id,
                            project.key(1),
                            ReviewVerdict.CHANGES_REQUIRED,
                        )
                    allocated.complete(Unit)
                    release.await()
                    outcome
                }
            }
        allocated.await()

        val contender =
            async(Dispatchers.IO) {
                useCases.submitReview.submit(
                    context(project.agentReviewerId, UUID.randomUUID(), kind = ActorKind.AGENT),
                    project.id,
                    project.key(1),
                    ReviewVerdict.APPROVED,
                )
            }

        awaitBlockedOn(contender, blocksOn)
        release.complete(Unit)

        holder.await() to contender.await()
    }
