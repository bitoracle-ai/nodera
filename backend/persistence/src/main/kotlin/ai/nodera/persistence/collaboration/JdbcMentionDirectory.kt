package ai.nodera.persistence.collaboration

import ai.nodera.application.collaboration.MentionDirectory
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.Handle
import ai.nodera.domain.project.ProjectId
import ai.nodera.persistence.currentConnection

// `distinct` is load-bearing rather than decorative: `actor.handle` is citext, so `@Anna` and
// `@anna` are two rows of the unnested array joining the SAME actor, and comment_mention is keyed
// (comment_id, actor_id). Without it that comment fails on the primary key.
//
// The membership join is the scope: a mention drives notification, and naming an actor who cannot
// open the ticket notifies a stranger. project_membership carries row-level security, so the
// boundary is the database's rather than a clause somebody has to remember.
private const val RESOLVE =
    "select distinct a.id from actor a " +
        "join project_membership m on m.actor_id = a.id " +
        "join unnest(?::text[]) as mentioned (handle) on a.handle = mentioned.handle::citext " +
        "where m.project_id = ?"

private const val NO_TRANSACTION =
    "mentions may only be resolved inside the comment's own transaction; " +
        "no transaction is open on this coroutine"

/** Handles to the actors they name inside one project. A handle that matches nobody yields nothing. */
public class JdbcMentionDirectory : MentionDirectory {
    override suspend fun resolve(
        projectId: ProjectId,
        handles: List<Handle>,
    ): List<ActorId> {
        val connection = currentConnection() ?: error(NO_TRANSACTION)
        return connection
            .rows(RESOLVE, {
                it.textArray(handles.map(Handle::value))
                it.uuid(projectId.value)
            }) { ActorId(it.uuidAt("id")) }
    }
}
