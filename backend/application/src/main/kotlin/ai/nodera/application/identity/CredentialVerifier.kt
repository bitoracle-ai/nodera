package ai.nodera.application.identity

import ai.nodera.domain.identity.CredentialState
import ai.nodera.domain.identity.PresentedToken
import ai.nodera.domain.identity.stateAt
import kotlin.time.Clock

public sealed interface CredentialVerification {
    public data class Verified(
        public val stored: StoredCredential,
    ) : CredentialVerification

    /**
     * @param owner known whenever the selector addressed a row, a wrong verifier included, and
     *   `null` only where there is no actor to name. A caller that audits its refusals needs it.
     */
    public data class Refused(
        public val reason: RejectionReason,
        public val owner: ActorPrincipal? = null,
    ) : CredentialVerification
}

/**
 * Turns a presented token into the row it addresses, or the reason it may not be used. Shared by
 * authentication and by session rotation: two implementations of "is this token still good" drift,
 * and the one that drifts is the one with fewer readers.
 */
public class CredentialVerifier(
    private val credentials: CredentialStore,
    private val hasher: SecretHasher,
    private val clock: Clock,
) {
    /**
     * **The kind is checked against the row, never against the prefix that arrived** — a selector is
     * unique across the table, so both callers' prefix gates would otherwise gate on
     * attacker-controlled input — `docs/plan/SEC-01.md` § 6.1 records it. An unknown selector
     * returns without hashing, which § 9.4 argues separately.
     */
    public suspend fun verify(token: PresentedToken): CredentialVerification {
        val stored =
            credentials.bySelector(token.selector)
                ?: return CredentialVerification.Refused(RejectionReason.UNKNOWN)

        if (!hasher.verify(stored.credential.secretHash, token.secret)) {
            return CredentialVerification.Refused(RejectionReason.UNKNOWN, stored.owner)
        }

        val refusal =
            if (stored.credential.kind != token.kind) {
                RejectionReason.UNKNOWN
            } else {
                when (stored.credential.stateAt(clock.now())) {
                    CredentialState.REVOKED -> RejectionReason.REVOKED
                    CredentialState.EXPIRED -> RejectionReason.EXPIRED
                    CredentialState.LIVE -> stored.owner.rejectionIfUnusable()
                }
            }

        return refusal
            ?.let { CredentialVerification.Refused(it, stored.owner) }
            ?: CredentialVerification.Verified(stored)
    }
}
