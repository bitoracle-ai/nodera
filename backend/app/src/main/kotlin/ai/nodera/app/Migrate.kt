package ai.nodera.app

import ai.nodera.persistence.DatabaseSettings
import ai.nodera.persistence.MigrationOutcome
import ai.nodera.persistence.Migrator
import java.io.PrintStream

/**
 * The `migrate` entrypoint: apply outstanding migrations as the schema owner, then exit.
 *
 * This is a separate command rather than a step inside `serve` because the application role cannot
 * run data-definition statements — and granting it those rights would let it remove its own
 * restrictions, which is the end of invariant AU1 (ADR-0006). Separate command, separate
 * credentials, and a non-zero exit an orchestrator can act on.
 */
internal fun runMigrate(
    config: MigrateConfig,
    out: PrintStream,
    err: PrintStream,
): Int {
    val migrator =
        Migrator(
            DatabaseSettings(
                url = config.database.url,
                user = config.database.user,
                password = config.database.password,
            ),
        )
    return when (val outcome = migrator.apply(config.appRolePassword)) {
        is MigrationOutcome.Applied -> {
            out.println("Applied ${outcome.count} migration(s); schema is current.")
            EXIT_OK
        }

        is MigrationOutcome.Failed -> {
            err.println("Migration failed: ${outcome.reason}")
            EXIT_FAILURE
        }
    }
}
