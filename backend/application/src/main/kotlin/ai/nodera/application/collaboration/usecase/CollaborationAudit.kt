package ai.nodera.application.collaboration.usecase

import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.project.ProjectId

internal const val COMMENT_ENTITY: String = "comment"

internal const val REVIEW_ENTITY: String = "review"

internal const val FINDING_ENTITY: String = "review_finding"

internal val COMMENT_CREATED: AuditAction = AuditAction("comment.created")

internal val COMMENT_EDITED: AuditAction = AuditAction("comment.edited")

internal val COMMENT_DELETED: AuditAction = AuditAction("comment.deleted")

internal val COMMENT_READ: AuditAction = AuditAction("comment.read")

internal val REVIEW_SUBMITTED: AuditAction = AuditAction("review.submitted")

internal val REVIEW_READ: AuditAction = AuditAction("review.read")

internal val FINDING_RESOLVED: AuditAction = AuditAction("finding.resolved")

internal const val TICKET_NOT_VISIBLE: String = "no such ticket is visible"

internal const val COMMENT_NOT_VISIBLE: String = "no such comment is visible in this project"

internal const val FINDING_NOT_VISIBLE: String = "no such finding is visible in this project"

internal const val REPLY_OFF_TICKET: String = "a reply must name a comment on the same ticket"

/** What was attempted, before anything about the entity is known. No entity id: there is none yet. */
internal fun attempted(
    action: AuditAction,
    entityType: String,
    projectId: ProjectId,
    before: Map<String, String?> = emptyMap(),
): AuditEntry =
    AuditEntry(
        action = action,
        entityType = entityType,
        projectId = projectId,
        diff = AuditDiff(before = before),
    )

/**
 * A refusal that is a **value**: nothing was mutated and the transaction commits carrying this row
 * alone.
 *
 * Whatever [entityId] the attempt already carries is kept and none is invented. `V4` indexes the
 * trail on `(entity_type, entity_id)`, so a refusal over an id the caller named belongs in that
 * entity's history — and a refusal that wrote nothing must not file some *other* entity's id under
 * this type. The id-addressed use cases set it on the attempt; the key-addressed ones carry the key
 * in the diff instead.
 */
internal fun AuditEntry.refused(reason: String): AuditEntry =
    copy(
        outcome = AuditOutcome.FAILED,
        diff = AuditDiff(before = diff.before, after = mapOf("refusal" to reason)),
    )
