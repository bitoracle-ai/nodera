package ai.nodera.api.rest

import ai.nodera.application.identity.RefreshSession
import ai.nodera.application.identity.usecase.IssuePersonalAccessToken
import ai.nodera.application.identity.usecase.RevokeCredential
import ai.nodera.application.identity.usecase.WhoAmI
import kotlin.time.Clock

/**
 * The use cases the identity surface hosts, grouped so [noderaApiRoutes] keeps three parameters.
 *
 * A holder rather than a builder: it composes nothing and decides nothing, and the composition root
 * is still the only place any of these are constructed.
 */
public class IdentitySurface(
    public val refreshSession: RefreshSession,
    public val whoAmI: WhoAmI,
    public val issueToken: IssuePersonalAccessToken,
    public val revokeCredential: RevokeCredential,
    public val clock: Clock,
)
