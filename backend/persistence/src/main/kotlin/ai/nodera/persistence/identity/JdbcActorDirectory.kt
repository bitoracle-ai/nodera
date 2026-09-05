package ai.nodera.persistence.identity

import ai.nodera.application.identity.ActorDirectory
import ai.nodera.application.identity.ActorPrincipal
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.Email
import ai.nodera.persistence.currentConnection
import java.sql.Connection

private const val PROJECTED = "a.id as actor_id, a.kind as actor_kind, a.status as actor_status"

private const val BY_ID = "select $PROJECTED from actor a where a.id = ?"

// Through human_actor because that is where an address lives. An agent has no e-mail column, so it
// cannot be reached this way — a property of the schema, not a branch on what kind an actor is.
private const val BY_EMAIL =
    "select $PROJECTED from actor a join human_actor h on h.actor_id = a.id where h.email = ?::citext"

/** The two reads authentication makes about an actor. Neither is project-scoped. */
public class JdbcActorDirectory : ActorDirectory {
    override suspend fun byId(actorId: ActorId): ActorPrincipal? =
        connection()
            .rows(BY_ID, { it.uuid(actorId.value) }) { it.toPrincipal() }
            .singleOrNull()

    override suspend fun byEmail(email: Email): ActorPrincipal? =
        connection()
            .rows(BY_EMAIL, { it.text(email.value) }) { it.toPrincipal() }
            .singleOrNull()

    private suspend fun connection(): Connection = currentConnection() ?: error(NO_TRANSACTION)
}
