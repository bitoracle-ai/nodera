package ai.nodera.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Where Flyway finds the migration files.
 *
 * The build packages `db/migrations` onto the classpath, so this is the same location in a
 * development checkout and inside the image. Two locations would mean two answers to "which
 * migration actually ran", and that question only ever gets asked during an incident.
 */
private const val MIGRATION_LOCATION = "classpath:db/migration"

/**
 * The placeholder `V4` substitutes when it creates the application role. It is a credential: it is
 * never logged, never echoed and never part of a value this file returns.
 */
private const val APP_PASSWORD_PLACEHOLDER = "nodera_app_password"

private const val REDACTED = "***"

/**
 * Removes a secret from a message before it can reach a log or a terminal.
 *
 * Defence in depth, and honestly labelled as such: Flyway Core's default message says
 * "Run Flyway with -X option to see the actual statement" rather than quoting it, so the
 * statement text this scrubs is normally absent. It is present with debug logging on, which is
 * exactly the configuration someone reaches for while a migration is failing — the moment the
 * `create role … password '…'` in V4 would otherwise be echoed into a terminal or a log
 * aggregator. Top-level and `internal` so the guard is reachable from a test rather than only
 * from a code path that has to fail first.
 */
internal fun redactSecret(
    message: String,
    secret: String,
): String = if (secret.isEmpty()) message else message.replace(secret, REDACTED)

/** No parameters and no interpolation: `current_user` is resolved by the server, not by us. */
private const val CREATE_PRIVILEGE_QUERY =
    "select has_schema_privilege(current_user, 'public', 'create')"

private sealed interface PrivilegeCheck {
    data object Granted : PrivilegeCheck

    data object Denied : PrivilegeCheck

    data class Unavailable(
        val category: String,
    ) : PrivilegeCheck
}

/** Connection settings for one database. Held only as long as a command needs them. */
public data class DatabaseSettings(
    val url: String,
    val user: String,
    val password: String,
)

/** The result of applying migrations — a value, because the caller is a command, not a stack. */
public sealed interface MigrationOutcome {
    public data class Applied(
        val count: Int,
    ) : MigrationOutcome

    /** @param reason safe to print: the role password has been redacted out of it. */
    public data class Failed(
        val reason: String,
    ) : MigrationOutcome
}

/**
 * The schema state as a reader holding only `select` on the history table can determine it.
 *
 * `Unreachable` carries a category, never the underlying message: this value is rendered on an
 * unauthenticated health endpoint, and a driver exception string routinely contains the host, the
 * port and the connecting user.
 */
public sealed interface SchemaState {
    /** Every migration in this build has been applied. */
    public data object UpToDate : SchemaState

    /** The database is readable and [count] migrations from this build have not been applied. */
    public data class Pending(
        val count: Int,
    ) : SchemaState

    /** The database could not be read at all. Fail closed: this is never treated as up to date. */
    public data class Unreachable(
        val category: String,
    ) : SchemaState
}

/**
 * Applies the migrations, and answers whether any are outstanding.
 *
 * Applying and asking are deliberately separate operations with separate credentials. Migrations run
 * as the schema owner; the readiness probe asks as `nodera_app`, which holds `select` on the history
 * table (`V5`) and no data-definition rights at all. A single object holding owner credentials for
 * the lifetime of the server is exactly the privilege that split exists to avoid.
 *
 * Flyway types do not cross this boundary. `:app` does not depend on Flyway, and a message leaving
 * here goes through [redactSecret] first — see that function for what redaction does and does not
 * buy, since Flyway Core does not quote the failing statement by default.
 */
public class Migrator(
    private val settings: DatabaseSettings,
) {
    /**
     * Applies every outstanding migration, after checking that this role is allowed to.
     *
     * The privilege check is not belt and braces. Flyway needs no data-definition rights when the
     * schema is already current, so `migrate` run with the **application** role's credentials
     * against an up-to-date database exits zero and looks fine — and the mistake then surfaces
     * mid-upgrade at the next release, which is the moment ADR-0006 exists to keep migrations away
     * from. Asking first turns that into a refusal on the very first run.
     *
     * @param appRolePassword substituted into `V4`'s `create role`. Required, because a role created
     *   with a guessable password is the failure mode the placeholder exists to prevent.
     */
    public fun apply(appRolePassword: String): MigrationOutcome =
        when (val privilege = canCreateObjects()) {
            is PrivilegeCheck.Granted -> runMigrations(appRolePassword)

            is PrivilegeCheck.Denied ->
                MigrationOutcome.Failed(
                    "The role '${settings.user}' cannot create objects in schema public, so it " +
                        "cannot apply migrations. Run migrate as the schema owner — the " +
                        "application role is deliberately unable to change the schema.",
                )

            is PrivilegeCheck.Unavailable ->
                MigrationOutcome.Failed("Could not reach the database: ${privilege.category}")
        }

    /**
     * Reports whether migrations are outstanding, without needing the placeholder or any write
     * privilege.
     *
     * Every failure collapses to [SchemaState.Unreachable]. That is the fail-closed polarity: a probe
     * that cannot read the history table does not know the schema is current, and "unknown" must
     * never render as "ready".
     */
    public fun state(): SchemaState =
        try {
            val pending =
                flyway()
                    .load()
                    .info()
                    .pending()
                    .size
            if (pending == 0) SchemaState.UpToDate else SchemaState.Pending(pending)
        } catch (e: FlywayException) {
            SchemaState.Unreachable(category = e.javaClass.simpleName)
        }

    private fun runMigrations(appRolePassword: String): MigrationOutcome =
        try {
            val executed =
                flyway()
                    .placeholders(mapOf(APP_PASSWORD_PLACEHOLDER to appRolePassword))
                    // Forward-only: a checksum mismatch is a defect corrected by a new migration,
                    // never repaired in place. So validation stays on and `clean` stays unreachable.
                    .validateOnMigrate(true)
                    .load()
                    .migrate()
                    .migrationsExecuted
            MigrationOutcome.Applied(executed)
        } catch (e: FlywayException) {
            MigrationOutcome.Failed(
                redactSecret(e.message ?: e.javaClass.simpleName, appRolePassword),
            )
        }

    private fun flyway() =
        Flyway
            .configure()
            .dataSource(settings.url, settings.user, settings.password)
            .locations(MIGRATION_LOCATION)
            .cleanDisabled(true)

    private fun canCreateObjects(): PrivilegeCheck =
        try {
            if (queryCreatePrivilege()) PrivilegeCheck.Granted else PrivilegeCheck.Denied
        } catch (e: SQLException) {
            PrivilegeCheck.Unavailable(e.javaClass.simpleName)
        }

    private fun queryCreatePrivilege(): Boolean =
        DriverManager.getConnection(settings.url, settings.user, settings.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(CREATE_PRIVILEGE_QUERY).use { rows ->
                    rows.next() && rows.getBoolean(1)
                }
            }
        }
}
