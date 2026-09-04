package ai.nodera.domain.ticket

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

// Written out rather than read from the production map: a matrix that borrows the implementation's
// own table agrees with it by construction and proves nothing. docs/DOMAIN_MODEL.md § 5.1.
private val SPECIFIED_EDGES =
    setOf(
        TicketStatus.OPEN to TicketStatus.IN_PROGRESS,
        TicketStatus.OPEN to TicketStatus.CLOSED,
        TicketStatus.IN_PROGRESS to TicketStatus.IN_REVIEW,
        TicketStatus.IN_PROGRESS to TicketStatus.OPEN,
        TicketStatus.IN_PROGRESS to TicketStatus.BLOCKED,
        TicketStatus.IN_REVIEW to TicketStatus.CLOSED,
        TicketStatus.IN_REVIEW to TicketStatus.OPEN,
        TicketStatus.IN_REVIEW to TicketStatus.BLOCKED,
        TicketStatus.BLOCKED to TicketStatus.OPEN,
        TicketStatus.BLOCKED to TicketStatus.CLOSED,
    )

class TicketStatusTest :
    StringSpec({

        TicketStatus.entries.forEach { from ->
            TicketStatus.entries.forEach { to ->
                val specified = (from to to) in SPECIFIED_EDGES
                val verb = if (specified) "is allowed" else "is refused as an unknown edge"

                "$from to $to $verb" {
                    // wont_do is the one resolution every closing edge accepts, so the matrix tests
                    // the edge rather than the resolution rules, which have their own cases below.
                    val resolution = if (to == TicketStatus.CLOSED) TicketResolution.WONT_DO else null
                    val outcome = transition(from, to, resolution)

                    if (specified) {
                        outcome shouldBe TransitionOutcome.Permitted
                    } else {
                        outcome shouldBe TransitionOutcome.Refused(TransitionRefusal.UnknownEdge(from, to))
                    }
                }
            }
        }

        "closing as done from in_review is permitted only if the closure gate passes" {
            transition(TicketStatus.IN_REVIEW, TicketStatus.CLOSED, TicketResolution.DONE) shouldBe
                TransitionOutcome.PermittedIfClosureGatePasses
        }

        "closing without a resolution is refused" {
            transition(TicketStatus.IN_REVIEW, TicketStatus.CLOSED, null) shouldBe
                TransitionOutcome.Refused(TransitionRefusal.ResolutionRequired)
        }

        "a resolution on a transition that does not close is refused" {
            transition(TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketResolution.DONE) shouldBe
                TransitionOutcome.Refused(TransitionRefusal.ResolutionNotAllowed(TicketStatus.IN_PROGRESS))
        }

        TicketResolution.entries.forEach { resolution ->
            val permitted = resolution == TicketResolution.WONT_DO
            val verb = if (permitted) "is permitted" else "is refused"

            "closing a blocked ticket as $resolution $verb" {
                val outcome = transition(TicketStatus.BLOCKED, TicketStatus.CLOSED, resolution)

                if (permitted) {
                    outcome shouldBe TransitionOutcome.Permitted
                } else {
                    outcome shouldBe
                        TransitionOutcome.Refused(
                            TransitionRefusal.ResolutionNotPermittedFrom(TicketStatus.BLOCKED, resolution),
                        )
                }
            }
        }

        // The direct edge and its one refused resolution: docs/DOMAIN_MODEL.md § 5.1.
        TicketResolution.entries.forEach { resolution ->
            val permitted = resolution != TicketResolution.DONE
            val verb = if (permitted) "is permitted" else "is refused"

            "closing an open ticket directly as $resolution $verb" {
                val outcome = transition(TicketStatus.OPEN, TicketStatus.CLOSED, resolution)

                if (permitted) {
                    outcome shouldBe TransitionOutcome.Permitted
                } else {
                    outcome shouldBe
                        TransitionOutcome.Refused(
                            TransitionRefusal.ResolutionNotPermittedFrom(TicketStatus.OPEN, resolution),
                        )
                }
            }
        }

        "a state carries a resolution exactly when it is closed" {
            TicketState(TicketStatus.CLOSED, TicketResolution.DONE).resolution shouldBe TicketResolution.DONE
            TicketState(TicketStatus.OPEN).resolution shouldBe null

            shouldThrow<IllegalArgumentException> { TicketState(TicketStatus.CLOSED) }
            shouldThrow<IllegalArgumentException> { TicketState(TicketStatus.OPEN, TicketResolution.DONE) }
        }

        // The refusal has to say which edge was asked for, or a surface can only answer "no".
        "a refusal names the edge it refused" {
            val outcome = transition(TicketStatus.OPEN, TicketStatus.IN_REVIEW, null)

            outcome
                .shouldBeInstanceOf<TransitionOutcome.Refused>()
                .refusal
                .shouldBeInstanceOf<TransitionRefusal.UnknownEdge>()
                .to shouldBe TicketStatus.IN_REVIEW
        }
    })
