package ai.nodera.application.identity.usecase

import ai.nodera.application.identity.ActorProfile
import ai.nodera.application.identity.ActorProfiles
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext

public sealed interface WhoAmIResult {
    public data class Known(
        public val profile: ActorProfile,
    ) : WhoAmIResult

    /** The credential resolved and the actor did not. Fail closed rather than answer from the context. */
    public data object Unknown : WhoAmIResult
}

/**
 * Who the caller is, read from the actor's own row.
 *
 * It re-reads rather than rendering [ActorContext], which carries an id and a kind and would be the
 * cheaper answer. The context is what authentication concluded; the row is what is true now, and a
 * handle or a display name is exactly the kind of field that changes between the two.
 *
 * No capability is required and none is checked. A capability is held *in a project* and this
 * resolves none — there is nothing here for [ai.nodera.application.permission.PermissionService] to
 * decide, and inventing a verb to have something to check would put a grant between an actor and
 * its own name.
 */
public class WhoAmI(
    private val unitOfWork: UnitOfWork,
    private val profiles: ActorProfiles,
) {
    public suspend fun of(ctx: ActorContext): WhoAmIResult =
        unitOfWork.inTransaction {
            profiles.profile(ctx.actorId)?.let(WhoAmIResult::Known) ?: WhoAmIResult.Unknown
        }
}
