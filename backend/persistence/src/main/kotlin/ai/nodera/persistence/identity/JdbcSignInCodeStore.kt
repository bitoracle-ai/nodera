package ai.nodera.persistence.identity

import ai.nodera.application.identity.SignInCodeStore
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCodeId
import ai.nodera.domain.identity.SignInCodeRecord
import ai.nodera.persistence.currentConnection
import java.sql.Connection
import kotlin.time.Instant

private const val COLUMNS = "id, actor_id, code_hash, attempts, expires_at, consumed_at"

// Every live code for this actor is consumed before a new one is written. Two concurrent issues can
// still leave two live rows — neither sees the other's insert under READ COMMITTED — so what keeps
// at most one redeemable is MOST_RECENT returning exactly one row, not this statement.
private const val SUPERSEDE =
    "update sign_in_code set consumed_at = now() where actor_id = ? and consumed_at is null"

private const val INSERT =
    "insert into sign_in_code (actor_id, code_hash, expires_at) values (?, ?, ?) returning $COLUMNS"

// One row, which is what makes at most one code redeemable. The consumed_at tiebreaker is
// defensive: nothing here writes two rows with the same created_at, and if something ever does,
// the live one is the one to return.
private const val MOST_RECENT =
    "select $COLUMNS from sign_in_code where actor_id = ? " +
        "order by created_at desc, consumed_at asc nulls first limit 1"

// Postgres re-evaluates the predicate against the row it waited for, so the caller that lifts the
// count to the limit is the last one to match. Why it is conditional: SignInCodeStore.claimAttempt.
private const val CLAIM_ATTEMPT =
    "update sign_in_code set attempts = attempts + 1 " +
        "where id = ? and consumed_at is null and attempts < ?"

private const val CONSUME = "update sign_in_code set consumed_at = ? where id = ? and consumed_at is null"

/** Reads and writes `sign_in_code`. Every rule about when a code may be used lives above it. */
public class JdbcSignInCodeStore : SignInCodeStore {
    override suspend fun issue(
        actorId: ActorId,
        codeHash: SecretHash,
        expiresAt: Instant,
    ): SignInCodeRecord {
        val connection = connection()
        connection.execute(SUPERSEDE) { it.uuid(actorId.value) }

        return connection
            .rows(INSERT, {
                it.uuid(actorId.value)
                it.text(codeHash.value)
                it.instant(expiresAt)
            }) { row -> row.toSignInCode() }
            .singleOrNull() ?: error(INSERT_RETURNED_NOTHING)
    }

    override suspend fun mostRecent(actorId: ActorId): SignInCodeRecord? =
        connection()
            .rows(MOST_RECENT, { it.uuid(actorId.value) }) { it.toSignInCode() }
            .singleOrNull()

    override suspend fun claimAttempt(
        id: SignInCodeId,
        limit: Int,
    ): Boolean =
        connection().execute(CLAIM_ATTEMPT) {
            it.uuid(id.value)
            it.int(limit)
        } == 1

    override suspend fun consume(
        id: SignInCodeId,
        at: Instant,
    ): Boolean =
        connection().execute(CONSUME) {
            it.instant(at)
            it.uuid(id.value)
        } == 1

    private suspend fun connection(): Connection = currentConnection() ?: error(NO_TRANSACTION)
}
