package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialLabel
import ai.nodera.domain.identity.CredentialToken
import kotlin.time.Clock
import kotlin.time.Duration

private val SESSION_LABEL = CredentialLabel("session")

/**
 * Mints a session: an access token, and the credential row behind the refresh token that replaces
 * it.
 *
 * The refresh token is opaque and shares the token grammar with a personal access token — the same
 * selector-and-verifier split, the same Argon2id hash, the same revocation — so a deployment has
 * one credential mechanism rather than two.
 *
 * The access token's own lifetime belongs to [AccessTokens], which signs it into the token. Keeping
 * a second copy of that number here is how the `exp` a client reads and the one a server enforces
 * come to disagree.
 */
public class SessionIssuer(
    private val credentials: CredentialStore,
    private val accessTokens: AccessTokens,
    private val secrets: Secrets,
    private val refreshTtl: Duration,
    private val clock: Clock,
) {
    public suspend fun issue(actorId: ActorId): Session {
        val now = clock.now()
        val selector = secrets.generator.selector()
        val secret = secrets.generator.tokenSecret()
        val credential =
            credentials.insert(
                actorId = actorId,
                kind = CredentialKind.SESSION,
                selector = selector,
                secretHash = secrets.hasher.hash(secret),
                terms = CredentialTerms(SESSION_LABEL, now + refreshTtl),
            )

        return Session(
            accessToken = accessTokens.issue(actorId, now),
            refreshToken = CredentialToken.render(CredentialKind.SESSION, selector, secret),
            refreshCredential = credential,
        )
    }
}
