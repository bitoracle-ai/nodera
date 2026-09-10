package ai.nodera.persistence.identity

import ai.nodera.application.identity.usecase.WhoAmIResult
import ai.nodera.domain.actor.ActorKind
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.insertAgent
import ai.nodera.persistence.insertHuman
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.Connection
import java.util.UUID
import kotlin.uuid.toKotlinUuid

private const val NAME_OF = "update actor set display_name = ?, handle = ? where id = ?::uuid"

/**
 * The read behind `GET /api/v1/me`, against a real Postgres.
 *
 * `actor` carries no `project_id` and no row-level security, so this read answers with no project
 * context established — which is what lets an actor learn who it is before any project is resolved.
 * The suite establishes none, deliberately.
 */
class ActorProfileTest :
    StringSpec({

        "an agent is answered with its own handle, its kind, and the owner it is accountable to" {
            val owner = SchemaFixture.asOwner { it.insertHuman(UUID.randomUUID()) }
            val agent = SchemaFixture.asOwner { it.insertAgent(UUID.randomUUID(), owner) }
            SchemaFixture.asOwner { it.rename(owner, "Anna Weber", "anna") }
            SchemaFixture.asOwner { it.rename(agent, "Release Bot", "release-bot") }

            val result = Identity().whoAmI.of(contextFor(agent, UUID.randomUUID()))

            val profile = result.shouldBeInstanceOf<WhoAmIResult.Known>().profile
            profile.actor.id.value shouldBe agent.toKotlinUuid()
            profile.actor.kind shouldBe ActorKind.AGENT
            profile.actor.handle.value shouldBe "release-bot"
            profile.actor.displayName.value shouldBe "Release Bot"
            profile.owner?.handle?.value shouldBe "anna"
            profile.owner?.kind shouldBe ActorKind.HUMAN
        }

        // The left join is on `agent_actor`, which is the table an owner column lives in. A human has
        // no row there, so the absence is the schema's answer rather than a branch on actor kind —
        // and an inner join here would make /me answer 404 for every person.
        "a human is answered with no owner at all" {
            val human = SchemaFixture.asOwner { it.insertHuman(UUID.randomUUID()) }

            val result = Identity().whoAmI.of(contextFor(human, UUID.randomUUID(), ActorKind.HUMAN))

            val profile = result.shouldBeInstanceOf<WhoAmIResult.Known>().profile
            profile.actor.kind shouldBe ActorKind.HUMAN
            profile.owner shouldBe null
        }

        "an actor that is not there is Unknown, never an answer assembled from the context" {
            val result = Identity().whoAmI.of(contextFor(UUID.randomUUID(), UUID.randomUUID()))

            result shouldBe WhoAmIResult.Unknown
        }
    })

private fun Connection.rename(
    id: UUID,
    displayName: String,
    handle: String,
) {
    prepareStatement(NAME_OF).use { statement ->
        statement.setString(1, displayName)
        statement.setString(2, handle)
        statement.setObject(3, id.toString())
        statement.executeUpdate()
    }
}
