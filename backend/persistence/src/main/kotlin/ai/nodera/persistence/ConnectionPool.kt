package ai.nodera.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

private const val POOL_NAME = "nodera"

/**
 * The serving process's connections, as the application role.
 *
 * `initializationFailTimeout = -1` makes the pool lazy: Hikari otherwise opens a connection during
 * construction and fails if it cannot, which turns a briefly unreachable database into a container
 * that never comes up. `/health/ready` reports that state instead — report, do not crash. The size
 * is Hikari's default, because choosing one needs a measured workload and there is none yet.
 */
public class ConnectionPool(
    settings: DatabaseSettings,
) : AutoCloseable {
    private val pool =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.url
                username = settings.user
                password = settings.password
                poolName = POOL_NAME
                initializationFailTimeout = -1
                isAutoCommit = false
            },
        )

    public val dataSource: DataSource get() = pool

    override fun close(): Unit = pool.close()
}
