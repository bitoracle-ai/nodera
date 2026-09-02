package ai.nodera.persistence.ticket

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.permission.PermissionDirectory
import ai.nodera.application.permission.PermissionService
import ai.nodera.application.ticket.TicketKeyAllocator
import ai.nodera.application.ticket.usecase.CreateTicket
import ai.nodera.application.ticket.usecase.NextTicket
import ai.nodera.application.ticket.usecase.TransitionTicket
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.permission.CapabilityGrant
import ai.nodera.domain.permission.ProjectMembership
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.Ticket
import ai.nodera.domain.ticket.TicketDraft
import ai.nodera.domain.ticket.TicketKey
import ai.nodera.domain.ticket.TicketNumber
import ai.nodera.domain.ticket.TicketPrefix
import ai.nodera.domain.ticket.TicketPriority
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.audit.AuditEventRepository
import ai.nodera.persistence.audit.auditedUnitOfWork
import ai.nodera.persistence.insertHuman
import ai.nodera.persistence.runSql
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID
import kotlin.time.Clock
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * A directory with one self-granted membership.
 *
 * Self-granted makes the actor the root of the attenuation chain, so its capabilities are exactly
 * [role]'s defaults — which is what lets a spec choose what the caller may do by naming a role.
 */
internal class SingleRoleDirectory(
    private val subject: ActorId,
    private val role: ProjectRole,
) : PermissionDirectory {
    override suspend fun membership(
        projectId: ProjectId,
        actorId: ActorId,
    ): ProjectMembership? =
        if (actorId == subject) {
            ProjectMembership(projectId, actorId, role, grantedBy = actorId, expiresAt = null)
        } else {
            null
        }

    override suspend fun capabilityGrants(
        projectId: ProjectId,
        actorId: ActorId,
    ): List<CapabilityGrant> = emptyList()

    override suspend fun actorStatus(actorId: ActorId): ActorStatus? = ActorStatus.ACTIVE
}

/** One project, one actor, and a reviewer who is neither — V3's R1 trigger refuses the reporter. */
internal data class TicketProject(
    val projectId: UUID = UUID.randomUUID(),
    val actorId: UUID = UUID.randomUUID(),
    val reviewerId: UUID = UUID.randomUUID(),
) {
    val projectKey: String get() = "p" + projectId.slug(20)
    val prefix: TicketPrefix get() = TicketPrefix("t" + projectId.slug(8))
    val id: ProjectId get() = ProjectId(projectId.toKotlinUuid())
    val actor: ActorId get() = ActorId(actorId.toKotlinUuid())
    val scope: List<UUID> get() = listOf(projectId)
}

private fun UUID.slug(length: Int): String = toString().replace("-", "").take(length)

/** No `ticket_sequence` row: allocation has to create its own, which is the path a new prefix takes. */
internal fun seedTicketProject(): TicketProject =
    SchemaFixture.asOwner { connection ->
        val rows = TicketProject()
        connection.insertHuman(rows.actorId)
        connection.insertHuman(rows.reviewerId)
        connection.runSql(
            "insert into project (id, key, name) values (?, ?, 'Ticket fixture project')",
            rows.projectId,
            rows.projectKey,
        )
        rows
    }

/**
 * The three use cases, wired the way the composition root will wire them.
 *
 * [scope] is what `SchemaFixture.openApp` establishes as `nodera.project_ids`; an empty one is the
 * unscoped caller, which several specs use deliberately.
 */
internal class TicketUseCases(
    scope: List<UUID>,
    subject: ActorId,
    role: ProjectRole = ProjectRole.OWNER,
    allocator: TicketKeyAllocator = JdbcTicketSequence(),
) {
    val unitOfWork: UnitOfWork = auditedUnitOfWork(scope)
    val recorder: AuditRecorder = AuditRecorder(AuditEventRepository())

    private val permissions = PermissionService(SingleRoleDirectory(subject, role), Clock.System)
    private val repository = JdbcTicketRepository()

    val create: CreateTicket = CreateTicket(permissions, unitOfWork, recorder, allocator, repository)
    val transition: TransitionTicket =
        TransitionTicket(permissions, unitOfWork, recorder, repository, repository, JdbcClosureFacts())
    val next: NextTicket = NextTicket(permissions, unitOfWork, recorder, JdbcTicketCandidates())
}

internal fun ticketKey(
    prefix: TicketPrefix,
    number: Int,
): TicketKey = TicketKey(prefix, TicketNumber(number))

internal fun draft(
    prefix: TicketPrefix,
    title: String = "A fixture ticket",
    priority: TicketPriority = TicketPriority.P3,
): TicketDraft = TicketDraft(prefix, title, priority = priority)

/** The entry a spec records when it drives the ports directly rather than through a use case. */
internal fun sequenceEntry(projectId: UUID): AuditEntry =
    AuditEntry(
        action = AuditAction("ticket.created"),
        entityType = "ticket",
        projectId = ProjectId(projectId.toKotlinUuid()),
    )

internal fun Connection.seedSequence(
    projectId: UUID,
    prefix: TicketPrefix,
    nextNumber: Int,
) {
    runSql(
        "insert into ticket_sequence (project_id, prefix, next_number) values (?, ?, ?)",
        projectId,
        prefix.value,
        nextNumber,
    )
}

/** Read as the owner, so "untouched" means untouched rather than merely invisible. */
internal fun Connection.nextNumber(
    projectId: UUID,
    prefix: TicketPrefix,
): Int? =
    prepareStatement("select next_number from ticket_sequence where project_id = ? and prefix = ?").use {
        it.setObject(1, projectId)
        it.setString(2, prefix.value)
        it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
    }

internal fun Connection.ticketKeys(projectId: UUID): List<String> =
    prepareStatement("select key from ticket where project_id = ? order by number").use {
        it.setObject(1, projectId)
        it.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }

/** Read as the owner: the assertion is about the row, not about whether the caller may see it. */
internal fun Connection.statusOf(ticket: Ticket): String? =
    prepareStatement("select status from ticket where id = ?").use {
        it.setObject(1, ticket.id.value.toJavaUuid())
        it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

internal fun Connection.closedAt(ticket: Ticket): Timestamp? =
    prepareStatement("select closed_at from ticket where id = ?").use {
        it.setObject(1, ticket.id.value.toJavaUuid())
        it.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp(1) else null }
    }

/** The entity a trail row points at, so "what happened to this ticket?" can be asked by id. */
internal fun Connection.auditEntities(requestId: UUID): List<UUID?> =
    prepareStatement("select entity_id from audit_event where request_id = ? order by id").use {
        it.setObject(1, requestId)
        it.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) }
        }
    }

internal fun Connection.auditOutcomes(requestId: UUID): List<String> =
    prepareStatement("select outcome from audit_event where request_id = ? order by id").use {
        it.setObject(1, requestId)
        it.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }

internal fun Connection.seedReview(
    ticketId: UUID,
    reviewerId: UUID,
    round: Int = 1,
): UUID {
    val id = UUID.randomUUID()
    runSql(
        "insert into review (id, ticket_id, reviewer_actor_id, round, verdict) values (?, ?, ?, ?, 'approved')",
        id,
        ticketId,
        reviewerId,
        round,
    )
    return id
}

internal fun Connection.seedCriterion(
    ticketId: UUID,
    ordinal: Int,
    text: String,
    met: Boolean,
    metBy: UUID? = null,
) {
    runSql(
        "insert into acceptance_criterion (ticket_id, ordinal, text, met, met_at, met_by_actor_id) " +
            "values (?, ?, ?, ?, case when ? then now() end, ?)",
        ticketId,
        ordinal,
        text,
        met,
        met,
        if (met) metBy else null,
    )
}

internal fun Connection.seedFinding(
    reviewId: UUID,
    title: String,
    severity: String,
    resolved: Boolean,
    resolvedBy: UUID? = null,
): UUID {
    val id = UUID.randomUUID()
    runSql(
        "insert into review_finding (id, review_id, severity, title, resolved_at, resolved_by_actor_id) " +
            "values (?, ?, ?::finding_severity, ?, case when ? then now() end, ?)",
        id,
        reviewId,
        severity,
        title,
        resolved,
        if (resolved) resolvedBy else null,
    )
    return id
}
