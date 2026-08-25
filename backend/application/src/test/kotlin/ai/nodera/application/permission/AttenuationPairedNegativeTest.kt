package ai.nodera.application.permission

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.permission.defaultCapabilities
import ai.nodera.domain.project.ProjectId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock

private val ANNA = actor(1)
private val AGENT = actor(2)

/**
 * The engine as it would be **without the grantor chain**: role defaults, plus grants, minus
 * denials. Nothing walks upward.
 *
 * This exists because a permission test that has never been seen to fail proves only that the code
 * runs. The ticket asks for the attenuation test to be run once with the check disabled, to confirm
 * it goes red — and a flag on [PermissionService] that disables attenuation is refused outright:
 * invariant #2 names "a bypass flag for tests that then leaks" precisely, and a switch that turns
 * off a security check is that flag whatever it is called.
 *
 * So the disabled version lives here, in the test source set, where it can never be reached from
 * production code. The test below asserts the two engines **disagree**. Delete the attenuation from
 * the engine's `step` and they agree, and the test goes red — which is the guarantee the criterion
 * actually wants, checked on every run rather than by hand.
 */
private class UnattenuatedReference(
    private val directory: PermissionDirectory,
    private val clock: Clock,
) {
    suspend fun effectiveCapabilities(
        actorId: ActorId,
        projectId: ProjectId,
    ): Set<Capability> {
        val now = clock.now()
        val membership = directory.membership(projectId, actorId)
        if (membership == null || membership.isExpiredAt(now)) return emptySet()

        val (additions, denials) =
            directory
                .capabilityGrants(projectId, actorId)
                .filterNot { it.isExpiredAt(now) }
                .partition { it.granted }

        return (membership.role.defaultCapabilities() + additions.map { it.capability }) -
            denials.map { it.capability }.toSet()
    }
}

class AttenuationPairedNegativeTest :
    StringSpec({

        // The fixture is the acceptance criterion's: Anna owns the project, the agent is a maintainer
        // holding through her, and then Anna loses `ticket.close`. Nothing on the agent's rows changes.
        fun revokedFixture(): Directory =
            Directory()
                .member(ANNA, ProjectRole.OWNER)
                .member(AGENT, ProjectRole.MAINTAINER, grantedBy = ANNA)
                .grant(ANNA, Capability.TICKET_CLOSE, grantedBy = ANNA, granted = false)

        "without the grantor chain the revoked capability survives on the grantee" {
            val directory = revokedFixture()

            UnattenuatedReference(directory, FixedClock(NOW))
                .effectiveCapabilities(AGENT, PROJECT) shouldContain Capability.TICKET_CLOSE
        }

        "with it, the capability is gone — so the assertion above discriminates" {
            val directory = revokedFixture()

            engine(directory).effectiveCapabilities(AGENT, PROJECT) shouldNotContain Capability.TICKET_CLOSE
        }

        "the two engines disagree on exactly the revoked capability, and on nothing else" {
            val directory = revokedFixture()

            val attenuated = engine(directory).effectiveCapabilities(AGENT, PROJECT)
            val unattenuated =
                UnattenuatedReference(directory, FixedClock(NOW)).effectiveCapabilities(AGENT, PROJECT)

            attenuated shouldNotBe unattenuated
            unattenuated - attenuated shouldBe setOf(Capability.TICKET_CLOSE)
            attenuated - unattenuated shouldBe emptySet()
        }
    })
