package ai.nodera.persistence.collaboration

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.collaboration.ReviewRoundAllocator
import ai.nodera.application.collaboration.usecase.CreateComment
import ai.nodera.application.collaboration.usecase.DeleteComment
import ai.nodera.application.collaboration.usecase.EditComment
import ai.nodera.application.collaboration.usecase.ListComments
import ai.nodera.application.collaboration.usecase.ListReviews
import ai.nodera.application.collaboration.usecase.ResolveFinding
import ai.nodera.application.collaboration.usecase.SubmitReview
import ai.nodera.application.permission.PermissionDirectory
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.ticket.ClosureFactsReader
import ai.nodera.application.ticket.usecase.TransitionTicket
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.collaboration.CommentBody
import ai.nodera.domain.permission.CapabilityGrant
import ai.nodera.domain.permission.ProjectMembership
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.domain.ticket.TicketNumber
import ai.nodera.domain.ticket.TicketPrefix
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.audit.AuditEventRepository
import ai.nodera.persistence.audit.auditedUnitOfWork
import ai.nodera.persistence.insertAgent
import ai.nodera.persistence.insertHuman
import ai.nodera.persistence.runSql
import ai.nodera.persistence.ticket.JdbcClosureFacts
import ai.nodera.persistence.ticket.JdbcTicketRepository
import ai.nodera.persistence.ticket.auditOutcomes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Memberships for several actors at once, each self-granted so its capabilities are exactly its
 * role's defaults.
 *
 * Bound to one project on purpose: a caller with a role in project A and none in project B is what
 * the cross-project cases need, and a directory that answered for every project could not express it.
 */
internal class RoleDirectory(
    private val project: ProjectId,
    private val roles: Map<UUID, ProjectRole>,
) : PermissionDirectory {
    override suspend fun membership(
        projectId: ProjectId,
        actorId: ActorId,
    ): ProjectMembership? {
        if (projectId != project) return null
        val role = roles[actorId.value.toJavaUuid()] ?: return null
        return ProjectMembership(projectId, actorId, role, grantedBy = actorId, expiresAt = null)
    }

    override suspend fun capabilityGrants(
        projectId: ProjectId,
        actorId: ActorId,
    ): List<CapabilityGrant> = emptyList()

    override suspend fun actorStatus(actorId: ActorId): ActorStatus? = ActorStatus.ACTIVE
}

/**
 * One project and five actors: a human reporter, an agent that holds the work, a human and an agent
 * reviewer, and one actor with no membership at all.
 *
 * Two reviewers of different kinds because every case that refuses or admits a reviewer runs twice —
 * a rule that reads the kind would pass the half it was written for (invariant #1).
 */
internal data class CollaborationProject(
    val projectId: UUID = UUID.randomUUID(),
    val reporterId: UUID = UUID.randomUUID(),
    val assigneeId: UUID = UUID.randomUUID(),
    val humanReviewerId: UUID = UUID.randomUUID(),
    val agentReviewerId: UUID = UUID.randomUUID(),
    val strangerId: UUID = UUID.randomUUID(),
    // Derived by default, but settable, and that is not a convenience. `ticket` is
    // `unique (project_id, key)`, so two projects may both own `x-1`; while every fixture project
    // derived its prefix from its own id, no test could put the same key in two projects, and the
    // project clause on the key-addressed reads was deletable with the suite still green.
    val prefixValue: String = "t" + projectId.slug(8),
) {
    val projectKey: String get() = "p" + projectId.slug(20)
    val prefix: TicketPrefix get() = TicketPrefix(prefixValue)
    val id: ProjectId get() = ProjectId(projectId.toKotlinUuid())
    val scope: List<UUID> get() = listOf(projectId)

    fun key(number: Int): TicketKey = TicketKey(prefix, TicketNumber(number))

    fun handleOf(actorId: UUID): String = "h" + actorId.slug(24)
}

private fun UUID.slug(length: Int): String = toString().replace("-", "").take(length)

internal fun seedCollaborationProject(prefix: String? = null): CollaborationProject =
    SchemaFixture.asOwner { connection ->
        val rows = CollaborationProject().let { if (prefix == null) it else it.copy(prefixValue = prefix) }
        connection.insertHuman(rows.reporterId)
        connection.insertHuman(rows.humanReviewerId)
        connection.insertHuman(rows.strangerId)
        connection.insertAgent(rows.assigneeId, rows.reporterId)
        connection.insertAgent(rows.agentReviewerId, rows.reporterId)
        connection.runSql(
            "insert into project (id, key, name) values (?, ?, 'Collaboration fixture project')",
            rows.projectId,
            rows.projectKey,
        )
        // The memberships the mention directory reads. Permission comes from RoleDirectory; these
        // rows exist so a mention can resolve, and the stranger deliberately has none.
        listOf(rows.reporterId, rows.assigneeId, rows.humanReviewerId, rows.agentReviewerId).forEach {
            connection.runSql(
                "insert into project_membership (project_id, actor_id, role, granted_by_actor_id) " +
                    "values (?, ?, 'maintainer', ?)",
                rows.projectId,
                it,
                rows.reporterId,
            )
        }
        rows
    }

/** A ticket in `in_review`, which is the status every closure case starts from. */
internal fun CollaborationProject.seedTicket(
    number: Int = 1,
    assignee: UUID? = assigneeId,
    status: String = "in_review",
): UUID =
    SchemaFixture.asOwner { connection ->
        val ticketId = UUID.randomUUID()
        connection.runSql(
            "insert into ticket (id, project_id, key, prefix, number, title, status, " +
                "reporter_actor_id, assignee_actor_id) " +
                "values (?, ?, ?, ?, ?, 'Collaboration fixture ticket', ?::ticket_status, ?, ?)",
            ticketId,
            projectId,
            key(number).rendered,
            prefix.value,
            number,
            status,
            reporterId,
            assignee,
        )
        ticketId
    }

/**
 * The seven collaboration use cases plus the transition, wired the way the composition root will.
 *
 * [closureFacts] is a parameter so the gate race can drive the same transition against an adapter
 * that models this one without its row lock.
 */
internal class CollaborationUseCases(
    scope: List<UUID>,
    projectId: ProjectId,
    roles: Map<UUID, ProjectRole>,
    closureFacts: ClosureFactsReader = JdbcClosureFacts(),
    roundAllocator: ReviewRoundAllocator = JdbcReviewRepository(),
) {
    val unitOfWork: UnitOfWork = auditedUnitOfWork(scope)
    val recorder: AuditRecorder = AuditRecorder(AuditEventRepository())

    private val permissions = PermissionService(RoleDirectory(projectId, roles), Clock.System)
    private val tickets = JdbcTicketRepository()
    private val comments = JdbcCommentRepository()
    private val reviews = JdbcReviewRepository()

    val createComment: CreateComment =
        CreateComment(permissions, unitOfWork, recorder, tickets, comments, comments, JdbcMentionDirectory())
    val editComment: EditComment = EditComment(permissions, unitOfWork, recorder, comments, comments)
    val deleteComment: DeleteComment = DeleteComment(permissions, unitOfWork, recorder, comments, comments)
    val listComments: ListComments = ListComments(permissions, unitOfWork, recorder, comments)
    val submitReview: SubmitReview =
        SubmitReview(permissions, unitOfWork, recorder, tickets, roundAllocator, reviews)
    val resolveFinding: ResolveFinding = ResolveFinding(permissions, unitOfWork, recorder, reviews, reviews)
    val listReviews: ListReviews = ListReviews(permissions, unitOfWork, recorder, reviews)
    val transition: TransitionTicket =
        TransitionTicket(permissions, unitOfWork, recorder, tickets, tickets, closureFacts)
}

/** Everyone a maintainer, which is the floor for review submission and comment moderation. */
internal fun CollaborationProject.maintainers(): Map<UUID, ProjectRole> =
    listOf(reporterId, assigneeId, humanReviewerId, agentReviewerId, strangerId)
        .associateWith { ProjectRole.MAINTAINER }

internal fun body(raw: String): CommentBody = CommentBody.of(raw)

// ---------------------------------------------------------------------------
// Deterministic contention — CORE-03's shape, reused rather than re-derived.
// ---------------------------------------------------------------------------

private val ARRIVAL_TIMEOUT = 30.seconds

private val ARRIVAL_POLL = 20.milliseconds

private const val BLOCKED_STATEMENTS =
    "select query from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"

private const val NO_CONTENDER =
    "the second transaction finished without ever waiting, so this race proves nothing"

private const val WRONG_WAIT =
    "no backend blocked on the statement this race is about. Releasing the holder on some other " +
        "wait lets the contender run after the holder has committed, which is how a race silently " +
        "stops testing the lock. Expected a wait on: "

/**
 * Releases nothing until a backend is blocked on the statement named by [statement].
 *
 * **Which** statement, not merely that something waited: CORE-03's phase 4 showed that a race
 * matching only on the table releases its holder before the contender has read, and then signs off
 * on an implementation with no lock at all.
 */
internal suspend fun awaitBlockedOn(
    contender: Deferred<*>,
    statement: String,
) {
    withTimeoutOrNull(ARRIVAL_TIMEOUT) {
        while (SchemaFixture.asOwner { it.blockedStatements() }.none { it.contains(statement) }) {
            if (contender.isCompleted) {
                // Rethrows if it failed; otherwise it finished without ever contending.
                contender.await()
                error(NO_CONTENDER)
            }
            delay(ARRIVAL_POLL)
        }
    } ?: error(WRONG_WAIT + statement)
}

private fun Connection.blockedStatements(): List<String> =
    prepareStatement(BLOCKED_STATEMENTS).use { statement ->
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }

// ---------------------------------------------------------------------------
// Inspection — always as the owner, so "absent" means absent rather than invisible.
// ---------------------------------------------------------------------------

internal data class CommentRow(
    val body: String,
    val author: UUID,
    val editedAt: Timestamp?,
    val deletedAt: Timestamp?,
    val deletedBy: UUID?,
    val inReplyTo: UUID?,
)

internal fun commentRow(id: UUID): CommentRow? =
    SchemaFixture.asOwner { connection ->
        connection
            .prepareStatement(
                "select body, author_actor_id, edited_at, deleted_at, deleted_by_actor_id, " +
                    "in_reply_to_comment_id from comment where id = ?",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        CommentRow(
                            body = rows.getString(1),
                            author = rows.getObject(2, UUID::class.java),
                            editedAt = rows.getTimestamp(3),
                            deletedAt = rows.getTimestamp(4),
                            deletedBy = rows.getObject(5, UUID::class.java),
                            inReplyTo = rows.getObject(6, UUID::class.java),
                        )
                    }
                }
            }
    }

/** The outcome of every trail row one request wrote. A count alone cannot tell a denial from a success. */
internal fun outcomesOf(requestId: UUID): List<String> = SchemaFixture.asOwner { it.auditOutcomes(requestId) }

internal fun mentionedActors(commentId: UUID): List<UUID> =
    SchemaFixture.asOwner { connection ->
        connection.uuidList("select actor_id from comment_mention where comment_id = ? order by actor_id", commentId)
    }

internal fun findingResolver(findingId: UUID): Pair<UUID?, String?> =
    SchemaFixture.asOwner { connection ->
        connection
            .prepareStatement("select resolved_by_actor_id, resolution_note from review_finding where id = ?")
            .use { statement ->
                statement.setObject(1, findingId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getString(2) else null to null
                }
            }
    }

internal fun ticketStatus(ticketId: UUID): String? =
    SchemaFixture.asOwner { connection ->
        connection.prepareStatement("select status from ticket where id = ?").use { statement ->
            statement.setObject(1, ticketId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

internal fun reviewRounds(ticketId: UUID): List<Int> =
    SchemaFixture.asOwner { connection ->
        connection.prepareStatement("select round from review where ticket_id = ? order by round").use { statement ->
            statement.setObject(1, ticketId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getInt(1)) }
            }
        }
    }

private fun Connection.uuidList(
    sql: String,
    key: UUID,
): List<UUID> =
    prepareStatement(sql).use { statement ->
        statement.setObject(1, key)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) }
        }
    }
