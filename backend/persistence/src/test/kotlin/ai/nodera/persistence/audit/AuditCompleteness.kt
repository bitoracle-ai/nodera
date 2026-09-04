package ai.nodera.persistence.audit

import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.persistence.JdbcUnitOfWork
import ai.nodera.persistence.SchemaFixture
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import java.util.UUID
import java.util.logging.Logger
import javax.sql.DataSource

private const val AUDIT_TABLE = "audit_event"

private val EXECUTING =
    setOf("execute", "executeUpdate", "executeLargeUpdate", "executeQuery", "addBatch")

// One left-to-right pass, so whichever comes first wins: `'a--b'` is a literal, `-- don't` is a
// comment. Two sequential replaces get one of those two cases wrong whichever order they run in.
// The locking clause goes too, or `select … for update of ticket_sequence` — the shape key
// allocation writes — reads as a mutation of a table called `of`.
private val NOISE =
    Regex(
        """'(?:[^']|'')*'|--[^\n]*|/\*.*?\*/|\bfor\s+(no\s+key\s+)?(update|share|key\s+share)\b""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

// Unanchored, and every match counted. Anchoring at `^` misses a mutation inside a common table
// expression and one behind a leading comment, which are the two shapes a real use case writes.
private val MUTATING =
    Regex(
        """\b(insert\s+into|update|delete\s+from|merge\s+into|copy)\s+([a-z_."]+)""",
        RegexOption.IGNORE_CASE,
    )

private val WHITESPACE = Regex("""\s+""")

private const val UNWRAPPED = "the audit harness wraps this connection and cannot be unwrapped"

/**
 * Connection methods whose real return value carries a route back to the raw, unwatched one.
 * `Statement.getMetaData` is not among them: it returns `ResultSetMetaData`, which reaches nothing.
 */
private val CONNECTION_ESCAPES = setOf("unwrap", "getMetaData")

private class Mutation(
    val verb: String,
    val table: String,
) {
    val isAuditRow: Boolean get() = verb == "insert" && table == AUDIT_TABLE

    override fun toString(): String = "$verb $table"
}

private fun String.mutations(): List<Mutation> =
    MUTATING
        .findAll(replace(NOISE, " "))
        .map {
            Mutation(
                verb =
                    it.groupValues[1]
                        .split(WHITESPACE)
                        .first()
                        .lowercase(),
                // Schema-qualified and quoted forms collapse to the bare name, or
                // `insert into public.audit_event` reads as a mutation of a table called `public`.
                table =
                    it.groupValues[2]
                        .replace("\"", "")
                        .substringAfterLast('.')
                        .lowercase(),
            )
        }.toList()

/**
 * A JDBC connection that refuses to commit a transaction whose mutations were not audited.
 *
 * Enforcing invariant #3 by review means noticing an absence, which is what review is worst at. This
 * watches the statements that actually executed, so bypassing `AuditRecorder`, hand-writing the SQL
 * and simply forgetting are all caught the same way. The rule at `commit()`: a transaction that
 * mutates any table other than `audit_event` carries exactly one `insert into audit_event`. An audit
 * row with no mutation is accepted — that is a denial.
 *
 * Test-time only. In the serving path this would be a per-statement cost and a second failure mode,
 * and the database already holds the append-only half through `V4`'s privileges and triggers.
 *
 * The routes back to the raw connection are refused (`unwrap`, `getMetaData`, `isWrapperFor`, and
 * `Statement.getConnection`, which returns the proxy). One remains: a `ResultSet` is not proxied, so
 * `executeQuery().statement.connection` still reaches it. Closing that needs a third proxy layer for
 * a shape no caller has; it is stated here rather than left to be discovered.
 */
internal class AuditCompleteness(
    private val target: Connection,
) {
    private val executed = mutableListOf<Mutation>()
    private lateinit var proxy: Connection

    fun watched(): Connection {
        proxy =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args -> intercept(method, args) } as Connection
        return proxy
    }

    private fun intercept(
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        when {
            commits(method, args) -> {
                verify()
                call(target, method, args).also { executed.clear() }
            }

            method.name == "rollback" -> {
                if (args.isNullOrEmpty()) executed.clear()
                call(target, method, args)
            }

            method.name == "prepareStatement" ->
                watch(
                    call(target, method, args) as PreparedStatement,
                    args?.firstOrNull() as? String,
                    PreparedStatement::class.java,
                )

            method.name == "prepareCall" ->
                watch(
                    call(target, method, args) as CallableStatement,
                    args?.firstOrNull() as? String,
                    CallableStatement::class.java,
                )

            method.name == "createStatement" -> watch(call(target, method, args) as Statement)

            // Both hand back the raw connection, and everything issued on it is invisible here.
            // WatchedDataSource closes the same door one level up.
            method.name in CONNECTION_ESCAPES -> throw SQLFeatureNotSupportedException(UNWRAPPED)
            method.name == "isWrapperFor" -> false

            else -> call(target, method, args)
        }

    /** JDBC commits an open transaction through either door, so both are checked. */
    private fun commits(
        method: Method,
        args: Array<out Any?>?,
    ): Boolean = method.name == "commit" || (method.name == "setAutoCommit" && args?.firstOrNull() == true)

    /** [type] is the interface the caller assigns to, so a `CallableStatement` stays one. */
    private fun <T : PreparedStatement> watch(
        statement: T,
        sql: String?,
        type: Class<T>,
    ): PreparedStatement =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            if (method.name in EXECUTING) record(sql ?: args?.firstOrNull() as? String)
            unwatchable(method) ?: call(statement, method, args)
        } as PreparedStatement

    private fun watch(statement: Statement): Statement =
        Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, args ->
            if (method.name in EXECUTING) record(args?.firstOrNull() as? String)
            unwatchable(method) ?: call(statement, method, args)
        } as Statement

    /** The ways a statement hands its caller back out of the harness. `null` means "not one of them". */
    private fun unwatchable(method: Method): Any? =
        when (method.name) {
            "getConnection" -> proxy
            "unwrap" -> throw SQLFeatureNotSupportedException(UNWRAPPED)
            "isWrapperFor" -> false
            else -> null
        }

    // Recorded when a statement executes, not when it is prepared: one may never run, and one may
    // run many times.
    private fun record(sql: String?) {
        sql?.let { executed += it.mutations() }
    }

    private fun verify() {
        val mutations = executed.filterNot { it.isAuditRow }
        if (mutations.isEmpty()) return

        val audited = executed.count { it.isAuditRow }
        if (audited != 1) {
            throw AssertionError(
                "this transaction mutated ${mutations.joinToString(", ")} and wrote $audited audit " +
                    "event(s). Invariant #3: every mutation writes exactly one, in the mutation's " +
                    "own transaction. Call AuditRecorder inside the same unit of work.",
            )
        }
    }
}

private fun call(
    target: Any,
    method: Method,
    args: Array<out Any?>?,
): Any? =
    try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (invocation: InvocationTargetException) {
        // Unwrapped, or every SQLException a test asserts on arrives as a reflection wrapper.
        throw invocation.targetException
    }

/**
 * The only way a test in this module opens a transaction.
 *
 * `scripts/lint_invariants.py` refuses a `JdbcUnitOfWork` built anywhere but the composition root
 * and this file, so a future use case cannot be tested through an unwatched transaction — an opt-in
 * completeness check is review attention wearing a harness.
 */
internal fun auditedUnitOfWork(projectIds: List<UUID>): UnitOfWork = JdbcUnitOfWork(WatchedDataSource(projectIds))

/** One watched connection per transaction, with the project context established at session level. */
private class WatchedDataSource(
    private val projectIds: List<UUID>,
) : DataSource {
    override fun getConnection(): Connection = AuditCompleteness(SchemaFixture.openApp(projectIds)).watched()

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = connection

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?): Unit = Unit

    override fun setLoginTimeout(seconds: Int): Unit = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getGlobal()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException(UNWRAPPED)

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
