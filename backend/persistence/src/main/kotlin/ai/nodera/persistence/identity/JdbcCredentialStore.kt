package ai.nodera.persistence.identity

import ai.nodera.application.identity.CredentialStore
import ai.nodera.application.identity.CredentialTerms
import ai.nodera.application.identity.StoredCredential
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.CredentialId
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialSelector
import ai.nodera.domain.identity.SecretHash
import ai.nodera.persistence.currentConnection
import ai.nodera.persistence.label
import java.sql.Connection
import kotlin.time.Instant

private const val COLUMNS =
    "id, actor_id, kind as credential_kind, selector, token_hash, label, expires_at, revoked_at"

private const val RETURNED =
    "c.id, c.actor_id, c.kind as credential_kind, c.selector, c.token_hash, c.label, " +
        "c.expires_at, c.revoked_at"

// The selector is the lookup key; the verifier is checked against token_hash afterwards, by the
// service, in constant time. A query cannot do that comparison — which is the whole reason a
// credential is two parts.
private const val BY_SELECTOR =
    "select $RETURNED, a.kind as actor_kind, a.status as actor_status " +
        "from credential c join actor a on a.id = c.actor_id where c.selector = ?"

private const val INSERT =
    "insert into credential (actor_id, kind, selector, token_hash, label, expires_at) " +
        "values (?, ?::credential_kind, ?, ?, ?, ?) returning $COLUMNS"

// Conditional on the owner and on still being live, in the statement rather than after a read: two
// statements leave a window, and a caller must never be able to revoke another actor's credential.
private const val REVOKE =
    "update credential set revoked_at = ? where id = ? and actor_id = ? and revoked_at is null " +
        "returning $COLUMNS"

/** Reads and writes `credential`. It decides nothing. */
public class JdbcCredentialStore : CredentialStore {
    override suspend fun bySelector(selector: CredentialSelector): StoredCredential? =
        connection()
            .rows(BY_SELECTOR, { it.text(selector.value) }) {
                StoredCredential(it.toCredential(), it.toPrincipal())
            }.singleOrNull()

    override suspend fun insert(
        actorId: ActorId,
        kind: CredentialKind,
        selector: CredentialSelector,
        secretHash: SecretHash,
        terms: CredentialTerms,
    ): Credential =
        connection()
            .rows(INSERT, {
                it.uuid(actorId.value)
                it.text(kind.label)
                it.text(selector.value)
                it.text(secretHash.value)
                it.text(terms.label.value)
                it.instant(terms.expiresAt)
            }) { row -> row.toCredential() }
            .singleOrNull() ?: error(INSERT_RETURNED_NOTHING)

    override suspend fun revoke(
        actorId: ActorId,
        id: CredentialId,
        at: Instant,
    ): Credential? =
        connection()
            .rows(REVOKE, {
                it.instant(at)
                it.uuid(id.value)
                it.uuid(actorId.value)
            }) { row -> row.toCredential() }
            .singleOrNull()

    private suspend fun connection(): Connection = currentConnection() ?: error(NO_TRANSACTION)
}
