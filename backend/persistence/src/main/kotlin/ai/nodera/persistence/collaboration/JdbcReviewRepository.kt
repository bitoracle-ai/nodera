package ai.nodera.persistence.collaboration

import ai.nodera.application.collaboration.ReviewReader
import ai.nodera.application.collaboration.ReviewRoundAllocator
import ai.nodera.application.collaboration.ReviewWriter
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.collaboration.Finding
import ai.nodera.domain.collaboration.FindingDraft
import ai.nodera.domain.collaboration.FindingId
import ai.nodera.domain.collaboration.Review
import ai.nodera.domain.collaboration.ReviewId
import ai.nodera.domain.collaboration.ReviewRound
import ai.nodera.domain.collaboration.ReviewVerdict
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketId
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.persistence.Binding
import ai.nodera.persistence.currentConnection
import ai.nodera.persistence.label
import java.sql.Connection
import java.sql.ResultSet

// The lock, and the first statement on the path. `review` is unique on (ticket_id, round), so two
// submissions that both read `max + 1` collide on the index instead of becoming rounds 2 and 3 —
// and holding this row is also what stops a submission landing between a closure gate's read and
// its write (docs/plan/CORE-04.md §§ 4.3-4.4).
private const val LOCK_TICKET = "select id from ticket where id = ? for update"

private const val NEXT_ROUND = "select coalesce(max(round), 0) + 1 as next from review where ticket_id = ?"

private const val REVIEW_ROW = "id, ticket_id, reviewer_actor_id, round, verdict, summary"

private const val REVIEW_PROJECTION =
    "w.id, w.ticket_id, w.reviewer_actor_id as author_actor_id, w.round, w.verdict, w.summary, " +
        "a.handle, a.kind, a.display_name"

private const val INSERT_REVIEW =
    "with written as (" +
        "insert into review (ticket_id, reviewer_actor_id, round, verdict, summary) " +
        "values (?, ?, ?, ?::review_verdict, ?) returning $REVIEW_ROW" +
        ") select $REVIEW_PROJECTION from written w join actor a on a.id = w.reviewer_actor_id"

private const val FINDING_ROW = "id, severity, title, detail, (resolved_at is not null) as resolved"

private const val INSERT_FINDING =
    "insert into review_finding (review_id, severity, title, detail) " +
        "values (?, ?::finding_severity, ?, ?) returning $FINDING_ROW"

private const val ROUNDS =
    "select r.id, r.ticket_id, r.reviewer_actor_id as author_actor_id, r.round, r.verdict, r.summary, " +
        "a.handle, a.kind, a.display_name from review r " +
        "join ticket t on t.id = r.ticket_id join actor a on a.id = r.reviewer_actor_id " +
        "where t.project_id = ? and t.key = ?::citext order by r.round"

// The project clause here is defence in depth rather than a guard, and it is described as what it
// is because it was measured: `rounds` groups these by `review_id` and looks up only the reviews
// ROUNDS returned, so rows from another project's ticket of the same key are never read out.
// Deleting it leaves the suite green. It is kept because it stops the read loading them at all, and
// because a reader that stopped grouping would need it.
private const val FINDINGS_BY_TICKET =
    "select f.review_id, f.id, f.severity, f.title, f.detail, (f.resolved_at is not null) as resolved " +
        "from review_finding f join review r on r.id = f.review_id join ticket t on t.id = r.ticket_id " +
        "where t.project_id = ? and t.key = ?::citext order by f.created_at, f.id"

// Scoped through review and ticket, not merely by id, for the reason JdbcCommentRepository's
// by-id read is: the session admits every project the caller belongs to.
private const val FINDING_BY_ID =
    "select f.id, f.severity, f.title, f.detail, (f.resolved_at is not null) as resolved " +
        "from review_finding f " +
        "join review r on r.id = f.review_id join ticket t on t.id = r.ticket_id " +
        "where f.id = ? and t.project_id = ?"

// Conditional, so the first resolver's note and identity stand and a second caller is told the
// finding had already moved rather than silently replacing them.
private const val RESOLVE =
    "update review_finding set resolved_at = now(), resolved_by_actor_id = ?, resolution_note = ? " +
        "where id = ? and resolved_at is null returning $FINDING_ROW"

private const val NO_TRANSACTION =
    "the review record may only be read or written inside the use case's own transaction; " +
        "no transaction is open on this coroutine"

private const val INSERT_RETURNED_NOTHING = "the review insert returned no row"

private const val FINDING_INSERT_RETURNED_NOTHING = "a review finding insert returned no row"

/** Reads and writes `review` and `review_finding`. Rounds are appended and never collapsed. */
public class JdbcReviewRepository :
    ReviewRoundAllocator,
    ReviewWriter,
    ReviewReader {
    override suspend fun nextRound(ticketId: TicketId): ReviewRound {
        val connection = connection()
        connection.lock(ticketId)
        return connection.roundAfterExisting(ticketId)
    }

    override suspend fun submit(
        ticketId: TicketId,
        reviewer: ActorId,
        round: ReviewRound,
        verdict: ReviewVerdict,
        summary: String,
        findings: List<FindingDraft>,
    ): Review {
        val connection = connection()
        val review =
            connection
                .rows(INSERT_REVIEW, {
                    it.uuid(ticketId.value)
                    it.uuid(reviewer.value)
                    it.int(round.value)
                    it.text(verdict.label)
                    it.text(summary)
                }) { it.toReview(emptyList()) }
                .singleOrNull() ?: error(INSERT_RETURNED_NOTHING)

        // Sorted the way `rounds` will return them. `review_finding.created_at` defaults to the
        // transaction clock, so every finding of one submission carries the same timestamp and the
        // reader's tie-break is the id — this makes the two paths agree instead of disagreeing about
        // an order neither of them means anything by.
        val written = findings.map { connection.insert(review.id, it) }
        return review.copy(findings = written.sortedBy { it.id.value.toString() })
    }

    override suspend fun resolve(
        id: FindingId,
        resolvedBy: ActorId,
        note: String,
    ): Finding? =
        connection()
            .rows(RESOLVE, {
                it.uuid(resolvedBy.value)
                it.text(note)
                it.uuid(id.value)
            }) { it.toFinding() }
            .singleOrNull()

    /**
     * Every round in ascending order, each with its own findings, or `null` when the ticket is not
     * visible. Nothing here filters by round or takes a latest — the contradiction between rounds is
     * the record's most informative part (invariant #9).
     */
    override suspend fun rounds(
        projectId: ProjectId,
        key: TicketKey,
    ): List<Review>? {
        val connection = connection()
        if (!connection.ticketIsVisible(projectId, key)) return null

        // Rounds first, findings second, and the order is the point. Each statement takes its own
        // snapshot under read committed, so a review committing between the two would otherwise
        // arrive in the round list with its findings missing — a `changes_required` round carrying
        // a blocking finding, read back as clean. This way the worst a race does is not show the
        // newest round yet, which is the direction this record is allowed to be wrong in.
        val reviews =
            connection.rows(ROUNDS, {
                it.uuid(projectId.value)
                it.text(key.rendered)
            }) { it.toReview(emptyList()) }

        val byReview =
            connection
                .rows(FINDINGS_BY_TICKET, {
                    it.uuid(projectId.value)
                    it.text(key.rendered)
                }) { ReviewId(it.uuidAt("review_id")) to it.toFinding() }
                .groupBy({ it.first }, { it.second })

        return reviews.map { it.copy(findings = byReview[it.id].orEmpty()) }
    }

    override suspend fun findingById(
        projectId: ProjectId,
        id: FindingId,
    ): Finding? =
        connection()
            .rows(FINDING_BY_ID, {
                it.uuid(id.value)
                it.uuid(projectId.value)
            }) { it.toFinding() }
            .singleOrNull()

    private suspend fun connection(): Connection = currentConnection() ?: error(NO_TRANSACTION)
}

/**
 * The round after the ones that exist, with no lock of its own.
 *
 * Shared with the test stand-in that models this allocator **without** the lock, so the two differ
 * in exactly one statement — the shape CORE-03 arrived at after a stand-in drifted from what it
 * claimed to model.
 */
internal fun Connection.roundAfterExisting(ticketId: TicketId): ReviewRound =
    ReviewRound(rows(NEXT_ROUND, { it.uuid(ticketId.value) }) { it.getInt("next") }.single())

private fun Connection.lock(ticketId: TicketId) {
    prepareStatement(LOCK_TICKET).use { statement ->
        Binding(statement).uuid(ticketId.value)
        statement.executeQuery().use { it.next() }
    }
}

private fun Connection.insert(
    reviewId: ReviewId,
    draft: FindingDraft,
): Finding =
    rows(INSERT_FINDING, {
        it.uuid(reviewId.value)
        it.text(draft.severity.label)
        it.text(draft.title)
        it.text(draft.detail)
    }) { it.toFinding() }.singleOrNull() ?: error(FINDING_INSERT_RETURNED_NOTHING)

private fun ResultSet.toReview(findings: List<Finding>): Review =
    Review(
        id = ReviewId(uuidAt("id")),
        ticketId = TicketId(uuidAt("ticket_id")),
        reviewer = toAuthor(),
        round = ReviewRound(getInt("round")),
        verdict = ReviewVerdict.valueOf(getString("verdict").uppercase()),
        summary = getString("summary"),
        findings = findings,
    )
