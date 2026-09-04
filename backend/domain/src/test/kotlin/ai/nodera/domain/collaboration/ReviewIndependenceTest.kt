package ai.nodera.domain.collaboration

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.ticket.FindingSeverity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

private fun actor(): ActorId = ActorId(Uuid.random())

class ReviewIndependenceTest :
    StringSpec({

        // Invariant #9 / R1, and V3's trigger states it exactly this way.
        "the assignee cannot review the work they are assigned" {
            val assignee = actor()

            reviewerIndependence(reviewer = assignee, reporter = actor(), assignee = assignee) shouldBe
                ReviewerCheck.Refused(ReviewRefusal.ReviewerIsAssignee)
        }

        "the reporter of an unassigned ticket cannot review it" {
            val reporter = actor()

            reviewerIndependence(reviewer = reporter, reporter = reporter, assignee = null) shouldBe
                ReviewerCheck.Refused(ReviewRefusal.ReviewerIsReporterOfUnassigned)
        }

        // The asymmetry, and it is deliberate: once somebody else holds the ticket they are the
        // author of the work, and the reporter is an independent reviewer.
        "the reporter may review a ticket assigned to someone else" {
            val reporter = actor()

            reviewerIndependence(reviewer = reporter, reporter = reporter, assignee = actor()) shouldBe
                ReviewerCheck.Independent
        }

        "a third actor is independent of both" {
            reviewerIndependence(reviewer = actor(), reporter = actor(), assignee = actor()) shouldBe
                ReviewerCheck.Independent
        }

        // V3: `check (round > 0)`.
        "a review round is numbered from one" {
            ReviewRound(1).value shouldBe 1
            listOf(0, -1).forEach { shouldThrow<IllegalArgumentException> { ReviewRound(it) } }
        }

        // V3: `check (length(trim(title)) > 0)`.
        "a finding needs a title" {
            shouldThrow<IllegalArgumentException> { FindingDraft(FindingSeverity.BLOCKING, "  ") }
        }
    })
