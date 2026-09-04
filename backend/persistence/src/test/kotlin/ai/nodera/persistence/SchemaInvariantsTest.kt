package ai.nodera.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCaseOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.SQLException
import java.util.UUID

private const val CHECK_VIOLATION = "23514"
private const val FOREIGN_KEY_VIOLATION = "23503"

private const val CHANGE_KIND = "update actor set kind = 'agent' where id = ?"
private const val RENAME_ACTOR = "update actor set display_name = 'Renamed' where id = ?"
private const val REASSIGN_OWNER = "update agent_actor set owner_actor_id = ? where actor_id = ?"
private const val ORPHAN_ACTOR =
    "insert into actor (id, kind, handle, display_name) values (?, 'agent', ?, 'No subtype row')"
private const val ADD_DEPENDENCY = "insert into ticket_dependency (ticket_id, depends_on_ticket_id) values (?, ?)"
private const val ADD_LABEL = "insert into ticket_label (ticket_id, label_id) values (?, ?)"
private const val ADD_REVIEW =
    "insert into review (id, ticket_id, reviewer_actor_id, round, verdict) values (?, ?, ?, ?, 'approved')"
private const val EDIT_REVIEW = "update review set summary = 'rewritten' where id = ?"
private const val DELETE_REVIEW = "delete from review where id = ?"
private const val REPLY =
    "insert into comment (id, ticket_id, author_actor_id, body, in_reply_to_comment_id) " +
        "values (?, ?, ?, 'Fixture reply', ?)"

private fun handleFor(id: UUID) = "h" + id.toString().replace("-", "").take(24)

/**
 * The triggers `V1`–`V3` carry, each reached as `nodera_app` and each paired with a run in which the
 * trigger is disabled.
 *
 * The pairing is per trigger rather than per branch: the branches are one guard reached by different
 * inputs, and disabling it once is what shows the guard is what refuses them.
 */
class SchemaInvariantsTest : StringSpec() {
    // Pinned rather than inherited: several cases below commit rows later ones depend on, and
    // Kotest's declaration order is a default, not a guarantee.
    override fun testCaseOrder(): TestCaseOrder = TestCaseOrder.Sequential

    init {
        val seeded = SchemaFixture.asOwner { it.seedProject() }
        val other = SchemaFixture.asOwner { it.seedProject() }
        val context = listOf(seeded.projectId)

        // ---------------------------------------------------------------- actor kind (V1)

        "an actor's kind cannot be changed" {
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asApp { it.runSql(CHANGE_KIND, seeded.humanActorId) }
                }
            failure.sqlState shouldBe CHECK_VIOLATION
            failure.message.orEmpty() shouldContain "actor.kind is immutable"
        }

        "another column on the same row still updates, so the trigger refuses the change and not the row" {
            SchemaFixture.asApp { it.runSql(RENAME_ACTOR, seeded.humanActorId) } shouldBe 1
        }

        "an actor's kind changes once the immutability trigger is disabled" {
            SchemaFixture.withGuardsDisabled(
                listOf("alter table actor disable trigger actor_kind_immutable"),
            ) { it.runSql(CHANGE_KIND, seeded.humanActorId) } shouldBe 1
        }

        // ---------------------------------------------------------- agent ownership chain (V1)

        "an ownership chain through another agent to a human is accepted" {
            val grandchild = UUID.randomUUID()
            SchemaFixture.asApp { it.insertAgent(grandchild, seeded.agentActorId) }

            SchemaFixture.asApp {
                it.countBy("select count(*) from agent_actor where actor_id = ?", grandchild)
            } shouldBe 1L
        }

        "an ownership cycle is refused" {
            val human = UUID.randomUUID()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            SchemaFixture.asOwner { connection ->
                connection.insertHuman(human)
                connection.insertAgent(first, human)
                connection.insertAgent(second, first)
            }

            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asApp { it.runSql(REASSIGN_OWNER, second, first) }
                }
            failure.sqlState shouldBe CHECK_VIOLATION
            failure.message.orEmpty() shouldContain "ownership cycle"

            SchemaFixture.withGuardsDisabled(
                listOf("alter table agent_actor disable trigger agent_owner_chain_valid"),
            ) { it.runSql(REASSIGN_OWNER, second, first) } shouldBe 1
        }

        // Rolled back, here and below: insertAgent writes the actor row before the agent_actor row
        // the trigger refuses, and on autocommit that leaves an actor with no subtype row — exactly
        // what db/checks/schema_integrity.sql exists to reject.
        "a chain that never reaches a human is refused" {
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asAppRolledBack { connection ->
                        val orphan = UUID.randomUUID()
                        connection.runSql(ORPHAN_ACTOR, orphan, handleFor(orphan))
                        connection.insertAgent(UUID.randomUUID(), orphan)
                    }
                }
            failure.message.orEmpty() shouldContain "does not terminate at a human"
        }

        "an owner that does not exist is refused before the foreign key is reached" {
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asAppRolledBack { it.insertAgent(UUID.randomUUID(), UUID.randomUUID()) }
                }
            failure.sqlState shouldBe FOREIGN_KEY_VIOLATION
            failure.message.orEmpty() shouldContain "does not exist"
        }

        // The bound is in the schema, not in this test: the trigger raises at hops > 16, so the
        // write is refused. It binds the written row's own chain — the trigger fires on insert and
        // on update of owner_actor_id — so a re-point higher up does not re-validate descendants;
        // the next write beneath one still fails closed. Recorded in docs/plan/DB-01.md § 4.5.
        "a written chain deeper than sixteen hops is refused" {
            val deepest =
                SchemaFixture.asOwner { connection ->
                    var previous = connection.insertHuman(UUID.randomUUID())
                    repeat(17) { previous = connection.insertAgent(UUID.randomUUID(), previous) }
                    previous
                }

            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asAppRolledBack { it.insertAgent(UUID.randomUUID(), deepest) }
                }
            failure.message.orEmpty() shouldContain "exceeds 16 hops"
        }

        // -------------------------------------------------------------- ticket dependency (V2)

        "a dependency cycle between two tickets is refused" {
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asApp(context) {
                        it.runSql(ADD_DEPENDENCY, seeded.dependencyTicketId, seeded.ticketId)
                    }
                }
            failure.sqlState shouldBe CHECK_VIOLATION
            failure.message.orEmpty() shouldContain "dependency cycle"

            SchemaFixture.withGuardsDisabled(
                listOf("alter table ticket_dependency disable trigger ticket_dependency_acyclic"),
                context,
            ) { it.runSql(ADD_DEPENDENCY, seeded.dependencyTicketId, seeded.ticketId) } shouldBe 1
        }

        "a dependency cycle through a third ticket is refused" {
            val third = UUID.randomUUID()
            SchemaFixture.asApp(context) { connection ->
                connection.insertTicket(seeded, third, 3, null)
                connection.runSql(ADD_DEPENDENCY, seeded.dependencyTicketId, third)
            }

            shouldThrow<SQLException> {
                SchemaFixture.asApp(context) { it.runSql(ADD_DEPENDENCY, third, seeded.ticketId) }
            }.message.orEmpty() shouldContain "dependency cycle"
        }

        // The policy on ticket_dependency scopes ticket_id only; this end is held by the trigger
        // alone, which is worth knowing before anyone decides the trigger is redundant.
        "a dependency on another project's ticket is refused, and accepted once its trigger is disabled" {
            shouldThrow<SQLException> {
                SchemaFixture.asApp(context) { it.runSql(ADD_DEPENDENCY, seeded.ticketId, other.ticketId) }
            }.message.orEmpty() shouldContain "cross-project ticket dependency"

            SchemaFixture.withGuardsDisabled(
                listOf("alter table ticket_dependency disable trigger ticket_dependency_project_scoped"),
                context,
            ) { it.runSql(ADD_DEPENDENCY, seeded.ticketId, other.ticketId) } shouldBe 1
        }

        // ------------------------------------------------------------------ ticket label (V2/V6)

        // V4's policy scopes ticket_id only and foreign keys bypass RLS, so before V6 this insert
        // was accepted and a project A ticket could wear a project B label.
        "a label from another project cannot be attached to a ticket" {
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asApp(context) { it.runSql(ADD_LABEL, seeded.ticketId, other.labelId) }
                }
            failure.sqlState shouldBe CHECK_VIOLATION
            failure.message.orEmpty() shouldContain "cross-project ticket label"

            SchemaFixture.withGuardsDisabled(
                listOf("alter table ticket_label disable trigger ticket_label_project_scoped"),
                context,
            ) { it.runSql(ADD_LABEL, seeded.ticketId, other.labelId) } shouldBe 1
        }

        // The case above leaves the label invisible, so the trigger compares a project against null.
        // An actor holding both projects sees both, and the comparison is uuid against uuid — the
        // branch the migration is actually named for.
        "a label from another project is refused even when the caller holds both projects" {
            shouldThrow<SQLException> {
                SchemaFixture.asApp(listOf(seeded.projectId, other.projectId)) {
                    it.runSql(ADD_LABEL, seeded.ticketId, other.labelId)
                }
            }.message.orEmpty() shouldContain "cross-project ticket label"
        }

        "a label from the ticket's own project is still accepted" {
            val third = UUID.randomUUID()
            SchemaFixture.asApp(context) { connection ->
                connection.insertTicket(seeded, third, 4, null)
                connection.runSql(ADD_LABEL, third, seeded.labelId)
            } shouldBe 1
        }

        // ------------------------------------------------------------------------ review (V3)

        "the assignee cannot review their own ticket" {
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asApp(context) {
                        it.runSql(ADD_REVIEW, UUID.randomUUID(), seeded.ticketId, seeded.agentActorId, 8)
                    }
                }
            failure.sqlState shouldBe CHECK_VIOLATION
            failure.message.orEmpty() shouldContain "is the assignee of ticket"

            SchemaFixture.withGuardsDisabled(
                listOf("alter table review disable trigger review_independent"),
                context,
            ) {
                it.runSql(ADD_REVIEW, UUID.randomUUID(), seeded.ticketId, seeded.agentActorId, 9)
            } shouldBe 1
        }

        "the reporter cannot review an unassigned ticket" {
            shouldThrow<SQLException> {
                SchemaFixture.asApp(context) {
                    it.runSql(ADD_REVIEW, UUID.randomUUID(), seeded.dependencyTicketId, seeded.humanActorId, 1)
                }
            }.message.orEmpty() shouldContain "is the reporter of unassigned ticket"
        }

        // The trigger refuses the reporter only while the ticket is unassigned. Once someone else
        // holds it, they are the author of the work and the reporter is an independent reviewer.
        "the reporter may review a ticket assigned to someone else" {
            SchemaFixture.asApp(context) {
                it.runSql(ADD_REVIEW, UUID.randomUUID(), seeded.ticketId, seeded.humanActorId, 2)
            } shouldBe 1
        }

        // Invariant #1 seen from the database: nothing here can refuse a reviewer for being an agent.
        "an agent may review a human's ticket" {
            SchemaFixture.asApp(context) {
                it.runSql(ADD_REVIEW, UUID.randomUUID(), seeded.dependencyTicketId, seeded.agentActorId, 1)
            } shouldBe 1
        }

        "a review cannot be edited or withdrawn" {
            shouldThrow<SQLException> {
                SchemaFixture.asApp(context) { it.runSql(EDIT_REVIEW, seeded.reviewId) }
            }.message.orEmpty() shouldContain "append-only"

            shouldThrow<SQLException> {
                SchemaFixture.asApp(context) { it.runSql(DELETE_REVIEW, seeded.reviewId) }
            }.message.orEmpty() shouldContain "append-only"

            SchemaFixture.withGuardsDisabled(
                listOf(
                    "alter table review disable trigger review_no_update",
                    "alter table review disable trigger review_no_delete",
                ),
                context,
            ) { connection ->
                connection.runSql(EDIT_REVIEW, seeded.reviewId) shouldBe 1
                connection.runSql(DELETE_REVIEW, seeded.reviewId)
            } shouldBe 1
        }

        // ----------------------------------------------------------------------- comment (V3)

        "a reply cannot cross into another ticket's thread" {
            shouldThrow<SQLException> {
                SchemaFixture.asApp(context) {
                    it.runSql(
                        REPLY,
                        UUID.randomUUID(),
                        seeded.dependencyTicketId,
                        seeded.humanActorId,
                        seeded.commentId,
                    )
                }
            }.message.orEmpty() shouldContain "replies to a comment on a different ticket"

            SchemaFixture.withGuardsDisabled(
                listOf("alter table comment disable trigger comment_reply_scoped"),
                context,
            ) {
                it.runSql(
                    REPLY,
                    UUID.randomUUID(),
                    seeded.dependencyTicketId,
                    seeded.humanActorId,
                    seeded.commentId,
                )
            } shouldBe 1
        }
    }
}
