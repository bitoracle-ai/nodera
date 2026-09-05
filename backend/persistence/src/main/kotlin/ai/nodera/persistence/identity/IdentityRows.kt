package ai.nodera.persistence.identity

import ai.nodera.application.identity.ActorPrincipal
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.CredentialId
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialLabel
import ai.nodera.domain.identity.CredentialSelector
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCodeId
import ai.nodera.domain.identity.SignInCodeRecord
import ai.nodera.persistence.Binding
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlin.time.Instant
import kotlin.uuid.toKotlinUuid

internal const val NO_TRANSACTION =
    "identity rows may only be read or written inside the caller's own transaction; " +
        "no transaction is open on this coroutine"

internal const val INSERT_RETURNED_NOTHING = "the insert returned no row"

internal fun <T> Connection.rows(
    sql: String,
    bind: (Binding) -> Unit,
    read: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        bind(Binding(statement))
        statement.executeQuery().use { results ->
            buildList { while (results.next()) add(read(results)) }
        }
    }

/** Returns the rows the statement matched, which is what a conditional update has to be judged on. */
internal fun Connection.execute(
    sql: String,
    bind: (Binding) -> Unit,
): Int =
    prepareStatement(sql).use { statement ->
        bind(Binding(statement))
        statement.executeUpdate()
    }

internal fun ResultSet.toCredential(): Credential =
    Credential(
        id = CredentialId(uuidAt("id")),
        actorId = ActorId(uuidAt("actor_id")),
        kind = CredentialKind.valueOf(getString("credential_kind").uppercase()),
        selector = CredentialSelector(getString("selector")),
        secretHash = SecretHash(getString("token_hash")),
        label = CredentialLabel(getString("label")),
        expiresAt = optionalInstantAt("expires_at"),
        revokedAt = optionalInstantAt("revoked_at"),
    )

/** `kind` here is the actor's, carried for the audit trail. Nothing reads it to decide anything. */
internal fun ResultSet.toPrincipal(): ActorPrincipal =
    ActorPrincipal(
        id = ActorId(uuidAt("actor_id")),
        kind = ActorKind.valueOf(getString("actor_kind").uppercase()),
        status = ActorStatus.valueOf(getString("actor_status").uppercase()),
    )

internal fun ResultSet.toSignInCode(): SignInCodeRecord =
    SignInCodeRecord(
        id = SignInCodeId(uuidAt("id")),
        actorId = ActorId(uuidAt("actor_id")),
        codeHash = SecretHash(getString("code_hash")),
        attempts = getInt("attempts"),
        expiresAt = instantAt("expires_at"),
        consumedAt = optionalInstantAt("consumed_at"),
    )

internal fun ResultSet.uuidAt(column: String): kotlin.uuid.Uuid = getObject(column, UUID::class.java).toKotlinUuid()

internal fun ResultSet.instantAt(column: String): Instant =
    checkNotNull(optionalInstantAt(column)) { "$column is not null in the schema but came back null" }

internal fun ResultSet.optionalInstantAt(column: String): Instant? =
    getTimestamp(column)?.toInstant()?.let { Instant.fromEpochSeconds(it.epochSecond, it.nano) }
