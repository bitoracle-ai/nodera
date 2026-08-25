package ai.nodera.domain.permission

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// The check constraint on capability_grant.capability in V1, character for character. A capability
// the engine can express but the database would reject is a defect that must not wait for an insert
// to surface — and this repository has no lane that would notice.
private val COLUMN_CONSTRAINT = Regex("^[a-z_]+\\.[a-z_]+$")

class CapabilityTest :
    StringSpec({

        // Guard: the verb strings themselves. Rename one to camelCase and this goes red here rather
        // than in a failing insert months later.
        "every verb satisfies the check constraint the column carries" {
            Capability.entries.forEach { capability ->
                withClue(capability.name) {
                    COLUMN_CONSTRAINT.matches(capability.verb) shouldBe true
                }
            }
        }

        "verbs are unique, so the column round-trips to exactly one capability" {
            Capability.entries.map { it.verb }.toSet() shouldHaveSize Capability.entries.size
        }

        "fromVerb round-trips every capability" {
            Capability.entries.forEach { capability ->
                Capability.fromVerb(capability.verb) shouldBe capability
            }
        }

        // Guard: fromVerb returning null rather than a default. A row written by a newer version must
        // grant nothing here — the fail-closed direction.
        "an unknown verb resolves to null rather than to something permissive" {
            Capability.fromVerb("ticket.detonate").shouldBeNull()
            Capability.fromVerb("").shouldBeNull()
            Capability.fromVerb("TICKET_READ").shouldBeNull()
        }
    })
