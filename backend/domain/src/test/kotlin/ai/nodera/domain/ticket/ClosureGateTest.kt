package ai.nodera.domain.ticket

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.uuid.Uuid

private fun criterion(
    ordinal: Int,
    met: Boolean,
) = AcceptanceCriterion(ordinal, "criterion $ordinal", met)

private fun finding(
    severity: FindingSeverity,
    resolved: Boolean,
) = ReviewFinding(Uuid.random(), "$severity finding", severity, resolved)

private fun unmetOf(verdict: ClosureVerdict) = verdict.shouldBeInstanceOf<ClosureVerdict.Unmet>().requirements

class ClosureGateTest :
    StringSpec({

        "a reviewed ticket with every criterion met and no unresolved blocking finding closes" {
            val facts =
                ClosureFacts(
                    criteria = listOf(criterion(1, met = true), criterion(2, met = true)),
                    findings = listOf(finding(FindingSeverity.BLOCKING, resolved = true)),
                    reviewCount = 1,
                )

            ClosureGate.evaluate(facts) shouldBe ClosureVerdict.Satisfied
        }

        "an unmet acceptance criterion refuses closure on its own" {
            val facts = ClosureFacts(listOf(criterion(1, met = false)), emptyList(), reviewCount = 1)

            val unmet = unmetOf(ClosureGate.evaluate(facts))

            unmet.acceptanceCriteria.map { it.ordinal } shouldContainExactly listOf(1)
            unmet.unresolvedBlockingFindings.shouldBeEmpty()
            unmet.reviews shouldBe ReviewRequirement.PRESENT
        }

        "an unresolved blocking finding refuses closure on its own" {
            val open = finding(FindingSeverity.BLOCKING, resolved = false)
            val facts = ClosureFacts(listOf(criterion(1, met = true)), listOf(open), reviewCount = 1)

            val unmet = unmetOf(ClosureGate.evaluate(facts))

            unmet.unresolvedBlockingFindings shouldContainExactly listOf(open)
            unmet.acceptanceCriteria.shouldBeEmpty()
            unmet.reviews shouldBe ReviewRequirement.PRESENT
        }

        "no review at all refuses closure on its own" {
            val facts = ClosureFacts(listOf(criterion(1, met = true)), emptyList(), reviewCount = 0)

            val unmet = unmetOf(ClosureGate.evaluate(facts))

            unmet.reviews shouldBe ReviewRequirement.ABSENT
            unmet.acceptanceCriteria.shouldBeEmpty()
            unmet.unresolvedBlockingFindings.shouldBeEmpty()
        }

        // Severity and resolution are both read. A gate that looked only at "any open finding" would
        // hold closure on a non-blocking one, and one that ignored resolution would never release it.
        "a resolved blocking finding and an unresolved non-blocking one do not refuse closure" {
            val facts =
                ClosureFacts(
                    criteria = emptyList(),
                    findings =
                        listOf(
                            finding(FindingSeverity.BLOCKING, resolved = true),
                            finding(FindingSeverity.NON_BLOCKING, resolved = false),
                        ),
                    reviewCount = 2,
                )

            ClosureGate.evaluate(facts) shouldBe ClosureVerdict.Satisfied
        }

        // THE criterion: the refusal names every missing item, not just the first. A gate that
        // short-circuits passes every single-condition case above and fails only here.
        "a refusal names every missing item across all three conditions" {
            val blocking = finding(FindingSeverity.BLOCKING, resolved = false)
            val facts =
                ClosureFacts(
                    criteria = listOf(criterion(1, met = false), criterion(2, met = true), criterion(3, met = false)),
                    findings = listOf(blocking, finding(FindingSeverity.NON_BLOCKING, resolved = false)),
                    reviewCount = 0,
                )

            val unmet = unmetOf(ClosureGate.evaluate(facts))

            unmet.acceptanceCriteria.map { it.ordinal } shouldContainExactly listOf(1, 3)
            unmet.unresolvedBlockingFindings shouldContainExactly listOf(blocking)
            unmet.reviews shouldBe ReviewRequirement.ABSENT
        }

        // An empty read is what row-level security answers an unscoped query with, and "nothing is
        // unmet" must not be how the gate reads it. The review condition is what catches it.
        "a ticket with no criteria, no findings and no reviews is refused rather than closed" {
            val unmet = unmetOf(ClosureGate.evaluate(ClosureFacts(emptyList(), emptyList(), reviewCount = 0)))

            unmet.reviews shouldBe ReviewRequirement.ABSENT
        }

        "a negative review count cannot be described" {
            shouldThrow<IllegalArgumentException> { ClosureFacts(emptyList(), emptyList(), reviewCount = -1) }
        }

        "a refusal that names nothing cannot be constructed" {
            shouldThrow<IllegalArgumentException> {
                UnmetClosureRequirements(emptyList(), emptyList(), ReviewRequirement.PRESENT)
            }
        }

        "a refusal cannot claim a met criterion or a resolved finding" {
            shouldThrow<IllegalArgumentException> {
                UnmetClosureRequirements(listOf(criterion(1, met = true)), emptyList(), ReviewRequirement.PRESENT)
            }
            shouldThrow<IllegalArgumentException> {
                UnmetClosureRequirements(
                    emptyList(),
                    listOf(finding(FindingSeverity.BLOCKING, resolved = true)),
                    ReviewRequirement.PRESENT,
                )
            }
            shouldThrow<IllegalArgumentException> {
                UnmetClosureRequirements(
                    emptyList(),
                    listOf(finding(FindingSeverity.NON_BLOCKING, resolved = false)),
                    ReviewRequirement.PRESENT,
                )
            }
        }
    })
