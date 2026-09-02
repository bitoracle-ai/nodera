package ai.nodera.domain.ticket

import ai.nodera.domain.actor.ActorId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ME = ActorId(Uuid.random())
private val SOMEBODY_ELSE = ActorId(Uuid.random())

private fun candidate(
    number: Int,
    status: TicketStatus = TicketStatus.OPEN,
    priority: TicketPriority = TicketPriority.P3,
    assignee: ActorId? = null,
    ageSeconds: Long = 0,
    dependsOn: Set<TicketId> = emptySet(),
): TicketCandidate =
    TicketCandidate(
        id = TicketId(Uuid.random()),
        key = TicketKey(TicketPrefix("core"), TicketNumber(number)),
        priority = priority,
        status = status,
        assignee = assignee,
        createdAt = Instant.fromEpochSeconds(ageSeconds),
        dependsOn = dependsOn,
    )

class WorkingOrderTest :
    StringSpec({

        "a ticket whose dependency is still open is not offered" {
            val blocker = candidate(1)
            val dependent = candidate(2, dependsOn = setOf(blocker.id))

            WorkingOrder.order(listOf(blocker, dependent), ME).map { it.candidate.id } shouldBe listOf(blocker.id)
        }

        // The rule is "closed", whatever the resolution: a dependency abandoned as wont_do would
        // otherwise leave the dependent permanently unstartable, with no way out but editing the
        // graph. The resolution is not a parameter here because a candidate does not carry one;
        // TicketLifecycleTest proves the wont_do case against a real row.
        "a dependency that is closed releases the ticket that depends on it" {
            val blocker = candidate(1, status = TicketStatus.CLOSED)
            val dependent = candidate(2, dependsOn = setOf(blocker.id))

            WorkingOrder.next(listOf(blocker, dependent), ME)?.candidate?.id shouldBe dependent.id
        }

        // Unknown is not satisfied: an id nobody returned may be anything, including open.
        "a dependency on a ticket that is not among the candidates blocks" {
            WorkingOrder.order(listOf(candidate(1, dependsOn = setOf(TicketId(Uuid.random())))), ME).shouldBeEmpty()
        }

        "only open tickets are offered" {
            val everyStatus = TicketStatus.entries.mapIndexed { index, status -> candidate(index + 1, status = status) }

            WorkingOrder.order(everyStatus, ME).map { it.candidate.status } shouldBe listOf(TicketStatus.OPEN)
        }

        "a ticket assigned to another actor is not offered" {
            val mine = candidate(1, assignee = ME)
            val theirs = candidate(2, assignee = SOMEBODY_ELSE)
            val free = candidate(3)

            WorkingOrder.order(listOf(theirs, free, mine), ME).map { it.candidate.id } shouldBe
                listOf(mine.id, free.id)
        }

        "work already assigned to the caller comes first, and says so" {
            val urgentAndFree = candidate(1, priority = TicketPriority.P1)
            val mine = candidate(2, priority = TicketPriority.P4, assignee = ME)

            val order = WorkingOrder.order(listOf(urgentAndFree, mine), ME)

            order.map { it.candidate.id } shouldBe listOf(mine.id, urgentAndFree.id)
            order.first().reason shouldBe SelectionReason.ALREADY_ASSIGNED_TO_YOU
            order.last().reason shouldBe SelectionReason.NEXT_BY_PRIORITY
        }

        "unassigned work is ordered by priority, then by age" {
            val oldP2 = candidate(1, priority = TicketPriority.P2, ageSeconds = 10)
            val newP2 = candidate(2, priority = TicketPriority.P2, ageSeconds = 20)
            val p1 = candidate(3, priority = TicketPriority.P1, ageSeconds = 30)

            WorkingOrder.order(listOf(newP2, p1, oldP2), ME).map { it.candidate.id } shouldBe
                listOf(p1.id, oldP2.id, newP2.id)
        }

        // Two tickets filed in the same second must still come back in one fixed order, or the
        // answer depends on the order the rows arrived in.
        "tickets of the same priority and age are ordered by key" {
            val second = candidate(10)
            val first = candidate(9)

            WorkingOrder.order(listOf(second, first), ME).map { it.candidate.key.number.value } shouldBe
                listOf(9, 10)
        }

        "an empty project offers nothing" {
            WorkingOrder.next(emptyList(), ME) shouldBe null
        }
    })
