package ai.nodera.persistence.identity

import ai.nodera.application.identity.ActorProfile
import ai.nodera.application.identity.ActorProfiles
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorSummary
import ai.nodera.domain.actor.DisplayName
import ai.nodera.domain.actor.Handle
import ai.nodera.persistence.currentConnection
import java.sql.Connection
import java.sql.ResultSet

// The owner is reached through agent_actor, which is where an owner column exists. A human actor
// has no such row, so the left join yields nulls — the distinction is the schema's, not a branch on
// what kind the actor is.
private const val PROFILE =
    """
    select a.id             as actor_id,
           a.kind           as actor_kind,
           a.handle         as actor_handle,
           a.display_name   as actor_display_name,
           o.id             as owner_id,
           o.kind           as owner_kind,
           o.handle         as owner_handle,
           o.display_name   as owner_display_name
      from actor a
      left join agent_actor ag on ag.actor_id = a.id
      left join actor o        on o.id = ag.owner_actor_id
     where a.id = ?
    """

/** `actor` carries no `project_id`, so this read is outside the project boundary by construction. */
public class JdbcActorProfiles : ActorProfiles {
    override suspend fun profile(actorId: ActorId): ActorProfile? =
        connection()
            .rows(PROFILE, { it.uuid(actorId.value) }) { it.toProfile() }
            .singleOrNull()

    private suspend fun connection(): Connection = currentConnection() ?: error(NO_TRANSACTION)
}

private fun ResultSet.toProfile(): ActorProfile =
    ActorProfile(
        actor = summaryAt("actor"),
        owner = if (getObject("owner_id") == null) null else summaryAt("owner"),
    )

/** Display only: the kind is read here so a reader is told, never so this code decides. */
private fun ResultSet.summaryAt(prefix: String): ActorSummary =
    ActorSummary(
        id = ActorId(uuidAt("${prefix}_id")),
        handle = Handle(getString("${prefix}_handle")),
        kind = ActorKind.valueOf(getString("${prefix}_kind").uppercase()),
        displayName = DisplayName(getString("${prefix}_display_name")),
    )
