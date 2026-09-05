package ai.nodera.application.identity.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.identity.CredentialStore
import ai.nodera.application.identity.RevokeCredentialResult
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.CredentialId
import kotlin.time.Clock

private val REVOKED = AuditAction("credential.revoked")

/**
 * Revokes one of the acting actor's own credentials.
 *
 * The ownership condition is part of the write rather than a read followed by a check: two
 * statements leave a window in which the row can change owner, and a `where actor_id = ?` cannot.
 * A credential that is not the caller's is reported as absent, which is the same answer the rest of
 * the contract gives for something invisible.
 */
public class RevokeCredential(
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val credentials: CredentialStore,
    private val clock: Clock,
) {
    public suspend fun revoke(
        ctx: ActorContext,
        id: CredentialId,
    ): RevokeCredentialResult =
        unitOfWork.inTransaction {
            val credential = credentials.revoke(ctx.actorId, id, clock.now())
            if (credential == null) {
                recorder.record(ctx, refused(id))
                RevokeCredentialResult.NotFound
            } else {
                recorder.record(ctx, revoked(credential))
                RevokeCredentialResult.Revoked(credential)
            }
        }
}

private fun revoked(credential: Credential): AuditEntry =
    AuditEntry(
        action = REVOKED,
        entityType = CREDENTIAL_ENTITY,
        entityId = credential.id.value,
        diff = AuditDiff(after = mapOf("revoked_at" to credential.revokedAt?.toString())),
    )

/** Invariant #3: somebody addressing a credential that is not theirs is an attempt, and it counts. */
private fun refused(id: CredentialId): AuditEntry =
    AuditEntry(
        action = REVOKED,
        entityType = CREDENTIAL_ENTITY,
        entityId = id.value,
        outcome = AuditOutcome.DENIED,
    )
