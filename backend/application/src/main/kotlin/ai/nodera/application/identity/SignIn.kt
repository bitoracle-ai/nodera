package ai.nodera.application.identity

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.Email
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.identity.SignInCode
import ai.nodera.domain.identity.SignInCodeId

internal const val SIGN_IN_ENTITY: String = "sign_in_code"

private val REQUESTED = AuditAction("sign_in.requested")
private val REDEEMED = AuditAction("sign_in.redeemed")

/**
 * Sends a one-time code to the address, if the address belongs to anybody.
 *
 * It returns nothing, and that is the whole design: an answer that varied with whether the address
 * is known would be an account-enumeration oracle on an unauthenticated endpoint. An unknown
 * address performs no mutation, which is also what keeps it out of the audit trail — every row
 * there names an actor, and there is none. A **known** address that may not sign in is a different
 * case: the actor exists, so its refusal is recorded (invariant #3).
 *
 * Delivery happens after the transaction has committed. Sending an e-mail from inside one holds a
 * pooled connection for a network round trip, and delivers a code for a transaction that may still
 * roll back.
 */
public class RequestSignInCode(
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val actors: ActorDirectory,
    private val codes: SignInCodes,
    private val delivery: SignInCodeDelivery,
) {
    public suspend fun request(
        email: Email,
        surface: Surface,
        requestId: RequestId,
    ) {
        val minted =
            unitOfWork.inTransaction {
                actors.byEmail(email)?.let { principal -> mintFor(principal.contextOn(surface, requestId), principal) }
            }
        minted?.let { delivery.deliver(email, it.code) }
    }

    private suspend fun mintFor(
        ctx: ActorContext,
        principal: ActorPrincipal,
    ): MintedSignInCode? {
        val unusable = principal.rejectionIfUnusable()
        if (unusable != null) {
            recorder.record(ctx, requestRefused(unusable))
            return null
        }

        return codes.mint(principal.id).also { recorder.record(ctx, requested(it.id)) }
    }
}

/**
 * Redeems a code and mints the session behind it.
 *
 * The refusal path is audited whenever an actor is known, which for this operation is whenever the
 * address resolved. A refusal for an address that resolves to nobody writes nothing, because
 * `audit_event.actor_id` is `not null` and inventing an actor to satisfy it would put a claim in
 * the trail that is not true.
 *
 * **Every refusal is answered `UNKNOWN` and recorded with the cause the code actually had.** The
 * trail needs the distinction — a burned-through attempt limit and a single wrong guess are not the
 * same event — and the caller must not have it: an answer that varied would say whether the address
 * is registered. The clock is levelled the same way, by [SignInCodes.absorb].
 */
public class RedeemSignInCode(
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val actors: ActorDirectory,
    private val codes: SignInCodes,
    private val sessions: SessionIssuer,
) {
    public suspend fun redeem(
        email: Email,
        code: SignInCode,
        surface: Surface,
        requestId: RequestId,
    ): SignInResult =
        unitOfWork.inTransaction {
            val principal = actors.byEmail(email)
            if (principal == null) {
                codes.absorb(code)
                SignInResult.Rejected(RejectionReason.UNKNOWN)
            } else {
                redeemFor(principal.contextOn(surface, requestId), principal, code)
            }
        }

    private suspend fun redeemFor(
        ctx: ActorContext,
        principal: ActorPrincipal,
        code: SignInCode,
    ): SignInResult {
        val unusable = principal.rejectionIfUnusable()
        if (unusable != null) {
            // The one refusal that never reaches SignInCodes, so it levels the clock itself.
            codes.absorb(code)
            return refuse(ctx, unusable.name.lowercase())
        }

        return when (val redemption = codes.redeem(principal.id, code)) {
            is CodeRedemption.Refused -> refuse(ctx, redemption.failure.name.lowercase())
            CodeRedemption.Redeemed -> {
                val session = sessions.issue(principal.id)
                recorder.record(ctx, redeemed(session))
                SignInResult.SignedIn(session)
            }
        }
    }

    private suspend fun refuse(
        ctx: ActorContext,
        cause: String,
    ): SignInResult {
        recorder.record(ctx, refused(cause))
        return SignInResult.Rejected(RejectionReason.UNKNOWN)
    }
}

private fun requested(id: SignInCodeId): AuditEntry =
    AuditEntry(
        action = REQUESTED,
        entityType = SIGN_IN_ENTITY,
        entityId = id.value,
    )

private fun requestRefused(reason: RejectionReason): AuditEntry =
    AuditEntry(
        action = REQUESTED,
        entityType = SIGN_IN_ENTITY,
        diff = AuditDiff(after = mapOf("reason" to reason.name.lowercase())),
        outcome = AuditOutcome.DENIED,
    )

private fun redeemed(session: Session): AuditEntry {
    val credential = session.refreshCredential.id.value
    return AuditEntry(
        action = REDEEMED,
        entityType = SIGN_IN_ENTITY,
        diff = AuditDiff(after = mapOf("session_credential" to credential.toString())),
    )
}

/** The cause, never the value that was presented — a refusal message is read by whoever gets it. */
private fun refused(cause: String): AuditEntry =
    AuditEntry(
        action = REDEEMED,
        entityType = SIGN_IN_ENTITY,
        diff = AuditDiff(after = mapOf("reason" to cause)),
        outcome = AuditOutcome.DENIED,
    )
