package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.TokenPlaintext

/**
 * Why a credential was refused.
 *
 * An unknown selector and a wrong secret share [UNKNOWN] deliberately: two answers would tell a
 * caller which half of the token it got right, and turn the lookup into an oracle. Revocation and
 * expiry are told apart because the caller already holds that credential.
 */
public enum class RejectionReason(
    public val detail: String,
) {
    MALFORMED("the presented credential is not in a form this deployment issues"),
    WRONG_KIND("the presented credential is not the kind this operation accepts"),
    UNKNOWN("no live credential matches the presented value"),
    REVOKED("the credential was revoked"),
    EXPIRED("the credential has expired"),
    INACTIVE_ACTOR("the actor this credential belongs to is not active"),
}

public sealed interface AuthenticationResult {
    /** The one thing authentication produces. Identical in shape whichever credential arrived. */
    public data class Authenticated(
        public val context: ActorContext,
    ) : AuthenticationResult

    public data class Rejected(
        public val reason: RejectionReason,
    ) : AuthenticationResult
}

/** [plaintext] is the single exposure a token ever gets (invariant CR1), and cannot print itself. */
public data class IssuedCredential(
    public val credential: Credential,
    public val plaintext: TokenPlaintext,
)

public sealed interface RevokeCredentialResult {
    public data class Revoked(
        public val credential: Credential,
    ) : RevokeCredentialResult

    /** Absent, or another actor's — indistinguishable on purpose, as elsewhere in the contract. */
    public data object NotFound : RevokeCredentialResult
}

/**
 * A signed-in session: a short-lived access token, and the opaque token that rotates it.
 *
 * It carries no [ActorContext] deliberately — a context is produced by authenticating, so the
 * sign-in path cannot become a second way to decide who somebody is.
 */
public data class Session(
    public val accessToken: AccessToken,
    public val refreshToken: TokenPlaintext,
    public val refreshCredential: Credential,
)

public sealed interface SignInResult {
    public data class SignedIn(
        public val session: Session,
    ) : SignInResult

    public data class Rejected(
        public val reason: RejectionReason,
    ) : SignInResult
}
