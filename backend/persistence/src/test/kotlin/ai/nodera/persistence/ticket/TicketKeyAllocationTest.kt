package ai.nodera.persistence.ticket

import ai.nodera.application.ticket.CreateTicketResult
import ai.nodera.application.ticket.TicketKeyAllocator
import ai.nodera.domain.project.ProjectId
import ai.nodera.domain.ticket.TicketNumber
import ai.nodera.domain.ticket.TicketPrefix
import ai.nodera.domain.ticket.TicketResolution
import ai.nodera.domain.ticket.TicketStatus
import ai.nodera.persistence.Binding
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.audit.context
import ai.nodera.persistence.currentConnection
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val CONCURRENCY = listOf(2, 8)

private val SEQUENCE_STATE = listOf("a prefix used for the first time" to false, "an existing sequence row" to true)

private const val SEEDED_NEXT_NUMBER = 7

// The contender is known to have arrived when its backend is waiting on a lock, so the holder never
// commits early on a slow machine. Only a contender that never blocks at all trips the bound, and
// that is the defect this is watching for rather than a property of the hardware.
private val ARRIVAL_TIMEOUT = 30.seconds
private val ARRIVAL_POLL = 20.milliseconds

private const val WAITING_ON_SEQUENCE =
    "select query from pg_stat_activity where datname = current_database() " +
        "and wait_event_type = 'Lock' and query like '%ticket_sequence%'"

private const val NO_CONTENDER =
    "the second transaction finished without ever waiting, so this race proves nothing"

private const val WRONG_WAIT =
    "no backend blocked on the statement this race is about. Waiting on some other statement that " +
        "touches ticket_sequence releases the holder before the contender has read, which is how a " +
        "race silently stops testing the lock. Expected a wait on: "

/**
 * The **seeded-row path** of the allocator, with the row lock removed.
 *
 * It models that path and no other — there is no create-row branch — so it belongs to the race below
 * and nowhere else. Committed rather than run once by hand: a lock nobody has watched fail is an
 * assertion about a lock. Driven through the same race as the real allocator, it issues one number
 * twice, every time.
 */
private class UnlockedTicketSequence : TicketKeyAllocator {
    override suspend fun allocate(
        projectId: ProjectId,
        prefix: TicketPrefix,
    ): TicketNumber {
        val connection = requireNotNull(currentConnection()) { "no transaction is open" }
        val next =
            connection.bound(
                "select next_number from ticket_sequence where project_id = ? and prefix = ?::citext",
                projectId,
                prefix,
            ) { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "this stand-in models the seeded-row path only" }
                    rows.getInt(1)
                }
            }
        connection.bound(
            "update ticket_sequence set next_number = next_number + 1 where project_id = ? and prefix = ?::citext",
            projectId,
            prefix,
        ) { it.executeUpdate() }
        return TicketNumber(next)
    }
}

private fun <T> Connection.bound(
    sql: String,
    projectId: ProjectId,
    prefix: TicketPrefix,
    read: (java.sql.PreparedStatement) -> T,
): T =
    prepareStatement(sql).use { statement ->
        val bind = Binding(statement)
        bind.uuid(projectId.value)
        bind.text(prefix.value)
        read(statement)
    }

private fun Connection.statementsWaitingOnSequence(): List<String> =
    prepareStatement(WAITING_ON_SEQUENCE).use { statement ->
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }

/**
 * Two transactions contending for one sequence row, the second starting while the first is open.
 *
 * Deterministic in both directions: the contender starts only once the holder has allocated, and
 * the holder commits only once the database itself reports a backend waiting on the row. Neither
 * step is a sleep, so a slow machine changes how long this takes and not what it proves.
 */
private suspend fun race(
    project: TicketProject,
    allocator: TicketKeyAllocator,
    blocksOn: String,
): Pair<Int, Int> =
    coroutineScope {
        val useCases = TicketUseCases(project.scope, project.actor, allocator = allocator)
        val allocated = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holder =
            async(Dispatchers.IO) {
                useCases.allocating(project, allocator) {
                    allocated.complete(Unit)
                    release.await()
                }
            }
        allocated.await()

        val contender = async(Dispatchers.IO) { useCases.allocating(project, allocator) }

        awaitContention(contender, blocksOn)
        release.complete(Unit)

        holder.await() to contender.await()
    }

/** One audited transaction that allocates, then runs [inside] before committing. */
private suspend fun TicketUseCases.allocating(
    project: TicketProject,
    allocator: TicketKeyAllocator,
    inside: suspend () -> Unit = {},
): Int =
    unitOfWork.inTransaction {
        val number = allocator.allocate(project.id, project.prefix).value
        recorder.record(context(project.actorId, UUID.randomUUID()), sequenceEntry(project.projectId))
        inside()
        number
    }

/**
 * Releases the holder only once a backend is blocked on the statement named by [blocksOn].
 *
 * Which statement matters, not merely that something waited: an allocator that touched
 * `ticket_sequence` earlier would block there instead, and releasing on that wait lets the contender
 * do its read after the holder has committed — which is how this race would sign off on an allocator
 * with no lock at all.
 */
private suspend fun awaitContention(
    contender: Deferred<Int>,
    blocksOn: String,
) {
    withTimeoutOrNull(ARRIVAL_TIMEOUT) {
        while (SchemaFixture.asOwner { it.statementsWaitingOnSequence() }.none { it.contains(blocksOn) }) {
            if (contender.isCompleted) {
                // Rethrows if it failed; otherwise it finished without ever contending.
                contender.await()
                error(NO_CONTENDER)
            }
            delay(ARRIVAL_POLL)
        }
    } ?: error(WRONG_WAIT + blocksOn)
}

class TicketKeyAllocationTest :
    StringSpec({

        // Both allocation paths, because they contend on different things: a prefix used for the
        // first time races on the sequence row's unique index before it ever reaches the lock, and
        // an established one races on the lock alone.
        CONCURRENCY.forEach { parallelism ->
            SEQUENCE_STATE.forEach { (state, seeded) ->
                "$parallelism concurrent creates against $state produce $parallelism distinct keys" {
                    val project = seedTicketProject()
                    if (seeded) {
                        SchemaFixture.asOwner { it.seedSequence(project.projectId, project.prefix, 1) }
                    }
                    val useCases = TicketUseCases(project.scope, project.actor)
                    // Every coroutine leaves the gate together, so the allocations genuinely overlap.
                    val gate = CyclicBarrier(parallelism)

                    val created =
                        coroutineScope {
                            (1..parallelism)
                                .map { attempt ->
                                    async(Dispatchers.IO) {
                                        gate.await()
                                        useCases.create.create(
                                            context(project.actorId, UUID.randomUUID()),
                                            project.id,
                                            draft(project.prefix, "concurrent $attempt"),
                                        )
                                    }
                                }.awaitAll()
                        }

                    val numbers =
                        created.map {
                            it
                                .shouldBeInstanceOf<CreateTicketResult.Created>()
                                .ticket.key.number.value
                        }

                    numbers.toSet() shouldHaveSize parallelism
                    numbers.sorted() shouldBe (1..parallelism).toList()
                    SchemaFixture.asOwner { it.ticketKeys(project.projectId) } shouldHaveSize parallelism
                }
            }
        }

        // The lock, put on trial. The sequence row is seeded and committed first, so what the
        // contender waits for is `select … for update` rather than an uncommitted insert.
        "an allocation that races an open transaction gets the next number, not the same one" {
            val project = seedTicketProject()
            SchemaFixture.asOwner { it.seedSequence(project.projectId, project.prefix, SEEDED_NEXT_NUMBER) }

            val (first, second) = race(project, JdbcTicketSequence(), blocksOn = "for update")

            first shouldBe SEEDED_NEXT_NUMBER
            second shouldBe SEEDED_NEXT_NUMBER + 1
            first shouldNotBe second
            SchemaFixture.asOwner { it.nextNumber(project.projectId, project.prefix) } shouldBe SEEDED_NEXT_NUMBER + 2
        }

        // The paired negative for the line above. Same race, same fixture, no `for update`: the
        // contender reads a value the holder has not committed away yet, and it is issued twice.
        "the same race without the row lock issues one number twice" {
            val project = seedTicketProject()
            SchemaFixture.asOwner { it.seedSequence(project.projectId, project.prefix, SEEDED_NEXT_NUMBER) }

            val (first, second) = race(project, UnlockedTicketSequence(), blocksOn = "update ticket_sequence set")

            first shouldBe SEEDED_NEXT_NUMBER
            second shouldBe first
        }

        // Invariant #10, at the point where "highest open key + 1" breaks: the closed tickets are
        // exactly the ones whose numbers a naive allocator would hand out again.
        "a ticket key is never reissued after closure, wont_do and duplicate included" {
            val project = seedTicketProject()
            val useCases = TicketUseCases(project.scope, project.actor)
            val ctx = context(project.actorId, UUID.randomUUID())

            repeat(3) { useCases.create.create(ctx, project.id, draft(project.prefix, "ticket $it")) }
            val keys = SchemaFixture.asOwner { it.ticketKeys(project.projectId) }

            useCases.closeAs(project, 1, TicketResolution.WONT_DO)
            useCases.closeAs(project, 2, TicketResolution.DUPLICATE)

            val fourth = useCases.create.create(ctx, project.id, draft(project.prefix, "after the closures"))

            fourth
                .shouldBeInstanceOf<CreateTicketResult.Created>()
                .ticket.key.number.value shouldBe 4
            SchemaFixture.asOwner { it.ticketKeys(project.projectId) } shouldContainExactly
                keys + "${project.prefix.value}-4"
        }

        // An unscoped read returns zero rows, and zero rows must never read as "this prefix has never
        // been used" — that reissues keys over tickets that already hold them. One predicate governs
        // both ends of `ticket_sequence`, so the row an unscoped caller cannot see is a row it cannot
        // create either, and the refusal arrives from the policy rather than from application code.
        "allocation without a project context is refused by the boundary, and the sequence stands" {
            val project = seedTicketProject()
            SchemaFixture.asOwner { it.seedSequence(project.projectId, project.prefix, SEEDED_NEXT_NUMBER) }
            val unscoped = TicketUseCases(emptyList(), project.actor)

            val failure =
                shouldThrowAny {
                    unscoped.create.create(
                        context(project.actorId, UUID.randomUUID()),
                        project.id,
                        draft(project.prefix),
                    )
                }

            failure.message.orEmpty() shouldContain "row-level security policy"
            failure.message.orEmpty() shouldContain "ticket_sequence"
            SchemaFixture.asOwner { it.nextNumber(project.projectId, project.prefix) } shouldBe SEEDED_NEXT_NUMBER
            SchemaFixture.asOwner { it.ticketKeys(project.projectId) }.shouldHaveSize(0)
        }
    })

/** open -> in_progress -> in_review -> closed, which is the only path the machine offers. */
private suspend fun TicketUseCases.closeAs(
    project: TicketProject,
    number: Int,
    resolution: TicketResolution,
) {
    val key = ticketKey(project.prefix, number)
    val ctx = context(project.actorId, UUID.randomUUID())
    transition.transition(ctx, project.id, key, TicketStatus.IN_PROGRESS, null)
    transition.transition(ctx, project.id, key, TicketStatus.IN_REVIEW, null)
    transition.transition(ctx, project.id, key, TicketStatus.CLOSED, resolution)
}
