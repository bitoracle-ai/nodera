package ai.nodera.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCaseOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.SQLException
import java.util.UUID

private data class ScopedTable(
    val table: String,
    val countQuery: String,
    val key: (ProjectRows) -> UUID,
)

/**
 * The fourteen tables `V4` puts behind row-level security, each probed by primary key against a row
 * that exists in a second project.
 */
private val SCOPED_TABLES =
    listOf(
        ScopedTable("project", "select count(*) from project where id = ?") { it.projectId },
        ScopedTable("project_membership", "select count(*) from project_membership where project_id = ?") {
            it.projectId
        },
        ScopedTable("capability_grant", "select count(*) from capability_grant where id = ?") {
            it.capabilityGrantId
        },
        ScopedTable("ticket", "select count(*) from ticket where id = ?") { it.ticketId },
        ScopedTable("ticket_sequence", "select count(*) from ticket_sequence where project_id = ?") {
            it.projectId
        },
        ScopedTable("label", "select count(*) from label where id = ?") { it.labelId },
        ScopedTable("audit_event", "select count(*) from audit_event where request_id = ?") {
            it.auditRequestId
        },
        ScopedTable("acceptance_criterion", "select count(*) from acceptance_criterion where id = ?") {
            it.criterionId
        },
        ScopedTable("ticket_dependency", "select count(*) from ticket_dependency where ticket_id = ?") {
            it.ticketId
        },
        ScopedTable("ticket_label", "select count(*) from ticket_label where ticket_id = ?") { it.ticketId },
        ScopedTable("comment", "select count(*) from comment where id = ?") { it.commentId },
        ScopedTable("comment_mention", "select count(*) from comment_mention where comment_id = ?") {
            it.commentId
        },
        ScopedTable("review", "select count(*) from review where id = ?") { it.reviewId },
        ScopedTable("review_finding", "select count(*) from review_finding where id = ?") { it.findingId },
    )

private const val DEPLOYMENT_AUDIT_ROW =
    "insert into audit_event (actor_id, actor_kind, surface, action, entity_type, request_id) " +
        "values (?, 'human', 'system', 'schema.migrated', 'deployment', ?)"

private const val NEW_PROJECT = "insert into project (id, key, name) values (?, ?, 'Fixture project')"

private const val INSUFFICIENT_PRIVILEGE = "42501"

private const val RLS_TABLES =
    "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
        "where n.nspname = 'public' and c.relkind = 'r' and c.relrowsecurity order by c.relname"

private const val POLICIES_ON =
    "select policyname from pg_policies where schemaname = 'public' and tablename = ?"

private fun disableRls(table: String) = listOf("alter table " + table + " disable row level security")

/** Keeps row-level security on and replaces only the predicate, so the predicate is what is on trial. */
private fun permitEverything(table: String) = listOf("alter policy " + table + "_visible on " + table + " using (true)")

/**
 * Invariant #5 — scoping is server-side and RLS is the floor.
 *
 * Every case is asked as `nodera_app`, and each table's guard is removed two ways — row-level
 * security off, and the policy's predicate replaced with `true` — because they answer different
 * questions: whether the mechanism filters, and whether this predicate does.
 *
 * Neither is `drop policy`. An RLS-enabled table with no policy denies everything, so a
 * dropped-policy probe reads zero rows and would leave a negative test that passes with the guard
 * gone. That behaviour is pinned separately below rather than used as the negative.
 */
class RowLevelSecurityTest : StringSpec() {
    // Pinned rather than inherited. The closing integrity test in this spec only proves anything if
    // it runs after the probes, and Kotest's declaration order is a default, not a guarantee.
    override fun testCaseOrder(): TestCaseOrder = TestCaseOrder.Sequential

    init {
        val mine = SchemaFixture.asOwner { it.seedProject() }
        val theirs = SchemaFixture.asOwner { it.seedProject() }

        SCOPED_TABLES.forEach { scoped ->
            "${scoped.table}: another project's row is invisible, and visible with either guard removed" {
                SchemaFixture.asApp(listOf(mine.projectId)) { connection ->
                    connection.countBy(scoped.countQuery, scoped.key(mine)) shouldBe 1L
                    connection.countBy(scoped.countQuery, scoped.key(theirs)) shouldBe 0L
                }

                SchemaFixture.withGuardsDisabled(emptyList(), listOf(mine.projectId)) { connection ->
                    connection.countBy(scoped.countQuery, scoped.key(theirs))
                } shouldBe 0L

                SchemaFixture.withGuardsDisabled(disableRls(scoped.table), listOf(mine.projectId)) { connection ->
                    connection.countBy(scoped.countQuery, scoped.key(theirs))
                } shouldBe 1L

                SchemaFixture.withGuardsDisabled(permitEverything(scoped.table), listOf(mine.projectId)) {
                    it.countBy(scoped.countQuery, scoped.key(theirs))
                } shouldBe 1L
            }
        }

        // Completeness, so this file cannot fall behind the schema in silence: a table that gains
        // row-level security without gaining a case above fails here rather than going unproved.
        "the tables covered here are exactly the tables that have row-level security" {
            SchemaFixture.asOwner { connection ->
                connection.prepareStatement(RLS_TABLES).use { statement ->
                    statement.executeQuery().use { rows ->
                        val enabled = buildList { while (rows.next()) add(rows.getString(1)) }
                        enabled shouldBe SCOPED_TABLES.map { it.table }.sorted()
                    }
                }
            }
        }

        "each of those tables carries exactly the one policy the negative above replaces" {
            SchemaFixture.asOwner { connection ->
                SCOPED_TABLES.forEach { scoped ->
                    connection.prepareStatement(POLICIES_ON).use { statement ->
                        statement.setString(1, scoped.table)
                        statement.executeQuery().use { rows ->
                            val policies = buildList { while (rows.next()) add(rows.getString(1)) }
                            policies shouldBe listOf(scoped.table + "_visible")
                        }
                    }
                }
            }
        }

        // The polarity criterion. An error would tell the caller the row exists.
        "a cross-project read returns zero rows rather than raising" {
            SchemaFixture.asApp(listOf(mine.projectId)) { connection ->
                connection.prepareStatement("select id from ticket where project_id = ?").use { statement ->
                    statement.setObject(1, theirs.projectId)
                    statement.executeQuery().use { rows -> rows.next() shouldBe false }
                }
            }
        }

        // Both branches of current_project_ids(): the setting absent, and the setting blank. They
        // share one result today, and a later edit that splits them would otherwise split silently.
        "with set_config never called, every project-scoped table reads zero rows" {
            SchemaFixture.asAppUnscoped { connection ->
                SCOPED_TABLES.forEach { scoped ->
                    connection.countBy(scoped.countQuery, scoped.key(mine)) shouldBe 0L
                }
            }
        }

        "with the context set but empty, every project-scoped table reads zero rows" {
            SchemaFixture.asApp { connection ->
                SCOPED_TABLES.forEach { scoped ->
                    connection.countBy(scoped.countQuery, scoped.key(mine)) shouldBe 0L
                }
            }
        }

        // Why the negatives above disable RLS instead of dropping the policy.
        "with its only policy dropped, ticket denies the caller its own project's rows too" {
            SchemaFixture.withGuardsDisabled(
                listOf("drop policy ticket_visible on ticket"),
                listOf(mine.projectId),
            ) { connection ->
                connection.countBy("select count(*) from ticket where id = ?", mine.ticketId)
            } shouldBe 0L
        }

        // V4 states this as deliberate: deployment-level events carry no project and stay readable.
        // Recorded here so it is a decision on the record rather than a surprise to CORE-02.
        "an audit row with no project is visible from any project context" {
            val requestId = UUID.randomUUID()
            SchemaFixture.asOwner { it.runSql(DEPLOYMENT_AUDIT_ROW, mine.humanActorId, requestId) }

            SchemaFixture.asApp(listOf(theirs.projectId)) { connection ->
                connection.countBy("select count(*) from audit_event where request_id = ?", requestId)
            } shouldBe 1L
        }

        // `project_visible` has no `with check` of its own, so the using expression governs insert:
        // a project can only be created by a caller whose context already names it.
        "the application role cannot create a project outside its own context" {
            val id = UUID.randomUUID()
            val failure =
                shouldThrow<SQLException> {
                    SchemaFixture.asApp(listOf(mine.projectId)) { connection ->
                        connection.runSql(NEW_PROJECT, id, "p" + id.toString().replace("-", "").take(20))
                    }
                }
            // 42501, not 23505 or 23514: refused by the policy, not by a malformed key.
            failure.sqlState shouldBe INSUFFICIENT_PRIVILEGE
            failure.message.orEmpty() shouldContain "row-level security policy"
        }

        "the application role can create a project its context already names" {
            val id = UUID.randomUUID()
            SchemaFixture.asApp(listOf(id)) { connection ->
                connection.runSql(NEW_PROJECT, id, "p" + id.toString().replace("-", "").take(20))
            } shouldBe 1
        }

        // Last, deliberately: every case above removed a guard inside a transaction and relied on
        // the rollback to put it back. If any of those rollbacks did not, this reads another
        // project's rows through a real application connection.
        "the boundary is intact after every guard-removed probe has run" {
            SchemaFixture.asApp(listOf(mine.projectId)) { connection ->
                SCOPED_TABLES.forEach { scoped ->
                    connection.countBy(scoped.countQuery, scoped.key(theirs)) shouldBe 0L
                }
            }
        }
    }
}
