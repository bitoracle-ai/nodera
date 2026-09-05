package ai.nodera.application.identity

import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialToken
import ai.nodera.domain.identity.PresentedToken

/**
 * The one place a credential becomes an [ActorContext]. Both surfaces call this object; a second
 * authentication path would be the drift invariant #2 forbids for permissions, one layer earlier.
 * It takes no context — producing one is what it is for — so it sits beside `usecase/`, like
 * `PermissionService`.
 *
 * **Equivalence is structural rather than asserted.** Both accepted shapes end at
 * [ActorPrincipal.contextOn], which fills every field from the actor's own row; the caller
 * contributes the surface and the request id and nothing else.
 *
 * `last_used_at` stays unwritten: a write on the read path owes the trail exactly one audit event
 * (invariant #3), and auditing every authenticated request is a separate decision.
 */
public class CredentialAuthenticator(
    private val unitOfWork: UnitOfWork,
    private val verifier: CredentialVerifier,
    private val actors: ActorDirectory,
    private val accessTokens: AccessTokens,
) {
    /** A refusal that reads no row opens no transaction: `Bearer garbage` costs no pooled connection. */
    public suspend fun authenticate(
        presented: String,
        surface: Surface,
        requestId: RequestId,
    ): AuthenticationResult {
        val token = CredentialToken.parse(presented)
        if (token != null) {
            // A refresh token is spent at RefreshSession and refused here — `docs/plan/SEC-01.md` § 6.1.
            if (token.kind == CredentialKind.SESSION) return AuthenticationResult.Rejected(RejectionReason.WRONG_KIND)
            return unitOfWork.inTransaction { byToken(token, surface, requestId) }
        }

        val actorId =
            accessTokens.verify(presented)
                ?: return AuthenticationResult.Rejected(RejectionReason.MALFORMED)
        return unitOfWork.inTransaction { byActorId(actorId, surface, requestId) }
    }

    private suspend fun byToken(
        token: PresentedToken,
        surface: Surface,
        requestId: RequestId,
    ): AuthenticationResult =
        when (val verification = verifier.verify(token)) {
            is CredentialVerification.Refused -> AuthenticationResult.Rejected(verification.reason)
            is CredentialVerification.Verified ->
                AuthenticationResult.Authenticated(verification.stored.owner.contextOn(surface, requestId))
        }

    /** The row is read on every request, so a suspension takes effect at once, not in fifteen minutes. */
    private suspend fun byActorId(
        actorId: ActorId,
        surface: Surface,
        requestId: RequestId,
    ): AuthenticationResult {
        val principal =
            actors.byId(actorId)
                ?: return AuthenticationResult.Rejected(RejectionReason.UNKNOWN)

        return principal.rejectionIfUnusable()?.let(AuthenticationResult::Rejected)
            ?: AuthenticationResult.Authenticated(principal.contextOn(surface, requestId))
    }
}
