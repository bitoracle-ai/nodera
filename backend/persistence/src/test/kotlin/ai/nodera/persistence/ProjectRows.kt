package ai.nodera.persistence

import java.sql.Connection
import java.util.UUID

/**
 * One fully populated project: every project-scoped table carries exactly one row, keyed by an id
 * the test already holds.
 *
 * Every id is generated before anything is inserted, so a probe can ask for a specific row by
 * primary key. That is what stops a zero coming from an empty table instead of from the boundary.
 */
internal data class ProjectRows(
    val projectId: UUID = UUID.randomUUID(),
    val humanActorId: UUID = UUID.randomUUID(),
    val agentActorId: UUID = UUID.randomUUID(),
    val outsiderActorId: UUID = UUID.randomUUID(),
    val capabilityGrantId: UUID = UUID.randomUUID(),
    val ticketId: UUID = UUID.randomUUID(),
    val dependencyTicketId: UUID = UUID.randomUUID(),
    val labelId: UUID = UUID.randomUUID(),
    val criterionId: UUID = UUID.randomUUID(),
    val commentId: UUID = UUID.randomUUID(),
    val reviewId: UUID = UUID.randomUUID(),
    val findingId: UUID = UUID.randomUUID(),
    val auditRequestId: UUID = UUID.randomUUID(),
) {
    val projectKey: String get() = "p" + projectId.slug(20)
    val ticketPrefix: String get() = "t" + ticketId.slug(8)
}

private fun UUID.slug(length: Int): String = toString().replace("-", "").take(length)

/** Seeded as the owner, so the rows exist regardless of any project context a test later sets. */
internal fun Connection.seedProject(rows: ProjectRows = ProjectRows()): ProjectRows {
    insertActors(rows)
    insertProject(rows)
    insertTickets(rows)
    insertCollaboration(rows)
    return rows
}

private fun Connection.insertActors(rows: ProjectRows) {
    listOf(rows.humanActorId, rows.outsiderActorId).forEach { id ->
        insertHuman(id)
    }
    insertAgent(rows.agentActorId, rows.humanActorId)
}

internal fun Connection.insertHuman(id: UUID): UUID {
    runSql(
        "insert into actor (id, kind, handle, display_name) values (?, 'human', ?, 'Fixture human')",
        id,
        "h" + id.slug(24),
    )
    runSql("insert into human_actor (actor_id, email) values (?, ?)", id, id.slug(24) + "@example.test")
    return id
}

internal fun Connection.insertAgent(
    id: UUID,
    ownerActorId: UUID,
): UUID {
    runSql(
        "insert into actor (id, kind, handle, display_name) values (?, 'agent', ?, 'Fixture agent')",
        id,
        "h" + id.slug(24),
    )
    runSql("insert into agent_actor (actor_id, owner_actor_id) values (?, ?)", id, ownerActorId)
    return id
}

private fun Connection.insertProject(rows: ProjectRows) {
    runSql(
        "insert into project (id, key, name) values (?, ?, 'Fixture project')",
        rows.projectId,
        rows.projectKey,
    )
    runSql(
        "insert into project_membership (project_id, actor_id, role, granted_by_actor_id) " +
            "values (?, ?, 'owner', ?)",
        rows.projectId,
        rows.humanActorId,
        rows.humanActorId,
    )
    runSql(
        "insert into capability_grant (id, project_id, actor_id, capability, granted_by_actor_id) " +
            "values (?, ?, ?, 'ticket.close', ?)",
        rows.capabilityGrantId,
        rows.projectId,
        rows.agentActorId,
        rows.humanActorId,
    )
    runSql(
        "insert into ticket_sequence (project_id, prefix, next_number) values (?, ?, 3)",
        rows.projectId,
        rows.ticketPrefix,
    )
    runSql(
        "insert into label (id, project_id, name) values (?, ?, 'fixture')",
        rows.labelId,
        rows.projectId,
    )
}

private fun Connection.insertTickets(rows: ProjectRows) {
    insertTicket(rows, rows.ticketId, 1, rows.agentActorId)
    insertTicket(rows, rows.dependencyTicketId, 2, null)
    runSql(
        "insert into acceptance_criterion (id, ticket_id, ordinal, text) values (?, ?, 1, 'Fixture criterion')",
        rows.criterionId,
        rows.ticketId,
    )
    runSql(
        "insert into ticket_dependency (ticket_id, depends_on_ticket_id) values (?, ?)",
        rows.ticketId,
        rows.dependencyTicketId,
    )
    runSql("insert into ticket_label (ticket_id, label_id) values (?, ?)", rows.ticketId, rows.labelId)
}

/** [assignee] is null for the second ticket, which is what makes the unassigned reviewer case reachable. */
internal fun Connection.insertTicket(
    rows: ProjectRows,
    ticketId: UUID,
    number: Int,
    assignee: UUID?,
): UUID {
    runSql(
        "insert into ticket (id, project_id, key, prefix, number, title, reporter_actor_id, assignee_actor_id) " +
            "values (?, ?, ?, ?, ?, 'Fixture ticket', ?, ?)",
        ticketId,
        rows.projectId,
        rows.ticketPrefix + "-" + number,
        rows.ticketPrefix,
        number,
        rows.humanActorId,
        assignee,
    )
    return ticketId
}

private fun Connection.insertCollaboration(rows: ProjectRows) {
    runSql(
        "insert into comment (id, ticket_id, author_actor_id, body) values (?, ?, ?, 'Fixture comment')",
        rows.commentId,
        rows.ticketId,
        rows.agentActorId,
    )
    runSql(
        "insert into comment_mention (comment_id, actor_id) values (?, ?)",
        rows.commentId,
        rows.outsiderActorId,
    )
    runSql(
        "insert into review (id, ticket_id, reviewer_actor_id, round, verdict) values (?, ?, ?, 1, 'approved')",
        rows.reviewId,
        rows.ticketId,
        rows.outsiderActorId,
    )
    runSql(
        "insert into review_finding (id, review_id, severity, title) values (?, ?, 'non_blocking', 'Fixture')",
        rows.findingId,
        rows.reviewId,
    )
    runSql(
        "insert into audit_event " +
            "(project_id, actor_id, actor_kind, surface, action, entity_type, entity_id, request_id) " +
            "values (?, ?, 'human', 'rest', 'ticket.created', 'ticket', ?, ?)",
        rows.projectId,
        rows.humanActorId,
        rows.ticketId,
        rows.auditRequestId,
    )
}
