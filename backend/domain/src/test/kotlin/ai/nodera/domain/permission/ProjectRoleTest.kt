package ai.nodera.domain.permission

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class ProjectRoleTest :
    StringSpec({

        // Guard: monotonicity. It is what lets attenuation intersect a grantee's defaults with a
        // grantor's effective set without a higher role ever narrowing a lower one. Move a verb down a
        // row and this goes red.
        "the four roles are strictly nested: observer < contributor < maintainer < owner" {
            val observer = ProjectRole.OBSERVER.defaultCapabilities()
            val contributor = ProjectRole.CONTRIBUTOR.defaultCapabilities()
            val maintainer = ProjectRole.MAINTAINER.defaultCapabilities()
            val owner = ProjectRole.OWNER.defaultCapabilities()

            contributor shouldContainAll observer
            maintainer shouldContainAll contributor
            owner shouldContainAll maintainer

            (contributor - observer).isEmpty() shouldBe false
            (maintainer - contributor).isEmpty() shouldBe false
            (owner - maintainer).isEmpty() shouldBe false
        }

        "owner is everything, so a new capability is an owner capability without a second edit" {
            ProjectRole.OWNER.defaultCapabilities() shouldBe Capability.entries.toSet()
        }

        // The § 4 table's own words: contributor may transition, but not close.
        "contributor may transition a ticket and may not close one" {
            val contributor = ProjectRole.CONTRIBUTOR.defaultCapabilities()

            contributor shouldContainAll
                setOf(
                    Capability.TICKET_READ,
                    Capability.TICKET_CREATE,
                    Capability.TICKET_UPDATE,
                    Capability.TICKET_TRANSITION,
                    Capability.COMMENT_CREATE,
                )
            contributor shouldNotContain Capability.TICKET_CLOSE
        }

        "only owner administers the project or grants membership" {
            ProjectRole.entries
                .filter { it != ProjectRole.OWNER }
                .forEach { role ->
                    role.defaultCapabilities() shouldNotContain Capability.PROJECT_ADMIN
                    role.defaultCapabilities() shouldNotContain Capability.MEMBER_GRANT
                }
        }

        "an observer reads and does nothing else" {
            ProjectRole.OBSERVER.defaultCapabilities() shouldBe
                setOf(
                    Capability.PROJECT_READ,
                    Capability.ACTOR_READ,
                    Capability.TICKET_READ,
                    Capability.COMMENT_READ,
                )
        }
    })
