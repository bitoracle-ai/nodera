package ai.nodera.persistence

import ai.nodera.application.transaction.UnitOfWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** The transaction in progress, carried so that `:application` never has to see a [Connection]. */
internal class TransactionScope(
    val connection: Connection,
) : AbstractCoroutineContextElement(TransactionScope) {
    companion object Key : CoroutineContext.Key<TransactionScope>
}

/** The connection of the transaction in progress, or `null` when none is open. */
internal suspend fun currentConnection(): Connection? = coroutineContext[TransactionScope]?.connection

/**
 * One JDBC transaction per use case, on one connection.
 *
 * The connection rides in the coroutine context rather than in every port signature, which is what
 * lets `AuditEventRepository` write into the caller's transaction without `:application` naming a
 * JDBC type. `ActorContext` stays an explicit parameter regardless: the unit of work is ambient,
 * who is acting is not.
 *
 * **Unfinished seam:** nothing here establishes `nodera.project_ids` from the authenticated context
 * (invariant #5). Until the package that authenticates does, a project-scoped read returns nothing
 * and an audit row carrying a `project_id` is refused by `V4`'s policy, aborting the transaction.
 * Fail-closed in both directions, but a failing write rather than an empty read.
 */
public class JdbcUnitOfWork(
    private val dataSource: DataSource,
) : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        if (coroutineContext[TransactionScope] == null) newTransaction(block) else block()

    private suspend fun <T> newTransaction(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                var committed = false
                try {
                    val result = withContext(TransactionScope(connection)) { block() }
                    connection.commit()
                    committed = true
                    result
                } finally {
                    if (!committed) connection.rollback()
                }
            }
        }
}
