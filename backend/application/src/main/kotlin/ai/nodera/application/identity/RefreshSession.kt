package ai.nodera.application.identity

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.identity.CredentialId
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialToken
import ai.nodera.domain.identity.PresentedToken
import kotlin.time.Clock

private val ROTATED = AuditAction("session.rotated")
private const val SESSION_ENTITY = "credential"

/**
 * Exchanges a refresh token for a new session, revoking the one that was presented.
 *
 * Rotation is what makes an opaque refresh token worth having: the presented credential is revoked
 * in the same transaction that mints its replacement, so a token that is captured and replayed
 * afterwards meets a revoked row and is refused like any other.
 *
 * Not under `usecase/`, for the same reason as sign-in: the credential presented is what identifies
 * the actor, so there is no context to receive.
 */
public class RefreshSession(
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val verifier: CredentialVerifier,
    private val credentials: CredentialStore,
    private val sessions: SessionIssuer,
    private val clock: Clock,
) {
    /** As in [CredentialAuthenticator]: a refusal that reads no row opens no transaction. */
    public suspend fun refresh(
        presented: String,
        surface: Surface,
        requestId: RequestId,
    ): SignInResult {
        val token = CredentialToken.parse(presented) ?: return SignInResult.Rejected(RejectionReason.MALFORMED)
        // The prefix that arrived, which is the caller's; CredentialVerifier compares it with the
        // row's kind, and that is what makes this refusal hold.
        if (token.kind != CredentialKind.SESSION) return SignInResult.Rejected(RejectionReason.WRONG_KIND)

        return unitOfWork.inTransaction { rotate(token, surface, requestId) }
    }

    private suspend fun rotate(
        token: PresentedToken,
        surface: Surface,
        requestId: RequestId,
    ): SignInResult =
        when (val verification = verifier.verify(token)) {
            is CredentialVerification.Refused -> refuseVerified(verification, surface, requestId)
            is CredentialVerification.Verified -> replace(verification.stored, surface, requestId)
        }

    /**
     * The ordinary replay lands here, not in [replace]: a captured refresh token presented after
     * the legitimate rotation revoked it meets a revoked row, and that is the attempt this whole
     * design exists to detect. It is recorded whenever the selector addressed a row, which is
     * whenever there is an actor to name — invariant #3 covers attempts.
     */
    private suspend fun refuseVerified(
        refused: CredentialVerification.Refused,
        surface: Surface,
        requestId: RequestId,
    ): SignInResult {
        refused.owner?.let { recorder.record(it.contextOn(surface, requestId), replayed(refused.reason, null)) }
        return SignInResult.Rejected(refused.reason)
    }

    /**
     * The revocation decides, not the verification before it: `null` means another rotation of the
     * same token committed first, and the loser is refused exactly as a sequential replay is
     * (`docs/plan/SEC-01.md` § 6.1).
     */
    private suspend fun replace(
        stored: StoredCredential,
        surface: Surface,
        requestId: RequestId,
    ): SignInResult {
        val owner = stored.owner
        val ctx = owner.contextOn(surface, requestId)
        val previous = stored.credential.id

        credentials.revoke(owner.id, previous, clock.now())
            ?: return refuse(ctx, previous)

        val session = sessions.issue(owner.id)
        recorder.record(ctx, rotated(session, previous.value.toString()))
        return SignInResult.SignedIn(session)
    }

    private suspend fun refuse(
        ctx: ActorContext,
        previous: CredentialId,
    ): SignInResult {
        recorder.record(ctx, replayed(RejectionReason.REVOKED, previous.value.toString()))
        return SignInResult.Rejected(RejectionReason.REVOKED)
    }
}

private fun rotated(
    session: Session,
    previous: String,
): AuditEntry =
    AuditEntry(
        action = ROTATED,
        entityType = SESSION_ENTITY,
        entityId = session.refreshCredential.id.value,
        diff = AuditDiff(before = mapOf("credential" to previous)),
    )

/**
 * A refresh token presented twice is the most diagnostic event this package produces.
 *
 * @param previous the credential that was rotated away, where the caller knows it — the race path
 *   does, the verification path only knows that some row refused.
 */
private fun replayed(
    reason: RejectionReason,
    previous: String?,
): AuditEntry =
    AuditEntry(
        action = ROTATED,
        entityType = SESSION_ENTITY,
        diff = AuditDiff(before = mapOf("credential" to previous, "reason" to reason.name.lowercase())),
        outcome = AuditOutcome.DENIED,
    )
