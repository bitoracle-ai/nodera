package ai.nodera.persistence

import io.kotest.assertions.fail
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

private class NoderaPostgres : PostgreSQLContainer<NoderaPostgres>(DockerImageName.parse("postgres:16-alpine"))

private const val APP_PASSWORD = "app-role-password-for-tests-only"
private const val OWNER_ROLE = "nodera_owner"
private const val OWNER_PASSWORD = "owner-password-for-tests-only"

private const val BECOME_APP_ROLE = "set role nodera_app"
private const val SET_CONTEXT_SESSION = "select set_config('nodera.project_ids', ?, false)"
private const val SET_CONTEXT_LOCAL = "select set_config('nodera.project_ids', ?, true)"

/**
 * A real Postgres 16 with the migrations applied, and the two ways to reach it.
 *
 * Everything a guard is asked to refuse is asked as the application role. The owner here is a
 * superuser, so a suite written against it would pass with every policy deleted.
 */
internal object SchemaFixture {
    private val container: NoderaPostgres by lazy {
        NoderaPostgres()
            .withDatabaseName("nodera")
            .withUsername(OWNER_ROLE)
            .withPassword(OWNER_PASSWORD)
            .also {
                it.start()
                migrate(it.jdbcUrl)
            }
    }

    /** The production path: a real login as the application role, with the grants it actually holds. */
    fun <T> asApp(
        projectIds: List<UUID> = emptyList(),
        block: (Connection) -> T,
    ): T =
        asAppUnscoped { connection ->
            connection.setProjectContext(projectIds, SET_CONTEXT_SESSION)
            block(connection)
        }

    /**
     * The application role with `set_config` never called at all.
     *
     * Not the same as an empty context: `current_project_ids()` has a branch for the setting being
     * absent and a branch for it being blank, and only this reaches the first — which is the path a
     * caller that forgot to establish context actually takes.
     */
    fun <T> asAppUnscoped(block: (Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, "nodera_app", APP_PASSWORD).use(block)

    /**
     * The application role, in a transaction that is rolled back.
     *
     * For probes whose refusal arrives mid-sequence: an actor row inserted before the statement the
     * trigger refuses would otherwise commit on its own and leave an actor with no subtype row —
     * the state `db/checks/schema_integrity.sql` exists to reject.
     */
    fun <T> asAppRolledBack(
        projectIds: List<UUID> = emptyList(),
        block: (Connection) -> T,
    ): T {
        val connection = DriverManager.getConnection(container.jdbcUrl, "nodera_app", APP_PASSWORD)
        return try {
            connection.autoCommit = false
            connection.setProjectContext(projectIds, SET_CONTEXT_LOCAL)
            block(connection)
        } finally {
            connection.rollback()
            connection.close()
        }
    }

    /** Seeding and inspection. Superuser, so RLS does not apply — never used to prove a guard. */
    fun <T> asOwner(block: (Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, OWNER_ROLE, OWNER_PASSWORD).use(block)

    /**
     * Runs [block] as the application role with [disable] applied first, then rolls everything back.
     *
     * Postgres makes DDL transactional, so a policy or trigger can be removed, the probe run, and the
     * removal undone on one connection — which is the only way to do it. A second connection would
     * block on the access-exclusive lock `alter table` holds, and could not see the uncommitted
     * change anyway.
     *
     * An empty [disable] is the control run. The row-level-security cases use it and need it —
     * whether RLS applies at all depends on the effective role, so the control is what separates
     * "the policy refused" from "`set role` never subjected the session to it". The trigger cases
     * do not: a trigger and a privilege check bind any session that reaches the statement, and
     * their pairing is the `asApp` refusal against the guard-disabled acceptance.
     */
    fun <T> withGuardsDisabled(
        disable: List<String>,
        projectIds: List<UUID> = emptyList(),
        block: (Connection) -> T,
    ): T {
        val connection = DriverManager.getConnection(container.jdbcUrl, OWNER_ROLE, OWNER_PASSWORD)
        return try {
            connection.autoCommit = false
            disable.forEach { connection.runSql(it) }
            connection.runSql(BECOME_APP_ROLE)
            connection.setProjectContext(projectIds, SET_CONTEXT_LOCAL)
            block(connection)
        } finally {
            connection.rollback()
            connection.close()
        }
    }

    private fun migrate(url: String) {
        val outcome = Migrator(DatabaseSettings(url, OWNER_ROLE, OWNER_PASSWORD)).apply(APP_PASSWORD)
        if (outcome !is MigrationOutcome.Applied) {
            fail("the schema under test could not be applied: $outcome")
        }
    }
}

internal fun Connection.setProjectContext(
    projectIds: List<UUID>,
    statement: String,
) {
    prepareStatement(statement).use { prepared ->
        prepared.setString(1, projectIds.joinToString(",") { it.toString() })
        prepared.executeQuery().use { it.next() }
    }
}

internal fun Connection.runSql(
    sql: String,
    vararg params: Any?,
): Int =
    prepareStatement(sql).use { statement ->
        params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

/** `select count(*) …` with a single key, so that "invisible" comes back as zero rather than as an error. */
internal fun Connection.countBy(
    sql: String,
    key: UUID,
): Long =
    prepareStatement(sql).use { statement ->
        statement.setObject(1, key)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }
