package ai.nodera.persistence.identity

import ai.nodera.application.identity.AuthenticationResult
import ai.nodera.application.identity.CredentialTerms
import ai.nodera.application.identity.RejectionReason
import ai.nodera.application.identity.RevokeCredentialResult
import ai.nodera.application.identity.SignInResult
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.Email
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.identity.CredentialLabel
import ai.nodera.persistence.SchemaFixture
import ai.nodera.persistence.audit.COUNT_BY_REQUEST
import ai.nodera.persistence.countBy
import ai.nodera.persistence.insertAgent
import ai.nodera.persistence.insertHuman
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.Connection
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.toKotlinUuid

private const val HASH_OF = "select token_hash from credential where id = ?::uuid"
private const val EMAIL_OF = "select email from human_actor where actor_id = ?::uuid"
private const val SELECTORS = "select count(*) from credential where selector = ?"

private const val INSERT_DUPLICATE =
    "insert into credential (actor_id, kind, selector, token_hash, label) " +
        "values (?::uuid, 'personal_access_token', ?, 'x', 'y')"

private const val INSERT_WITHOUT_SELECTOR =
    "insert into credential (actor_id, kind, token_hash, label) " +
        "values (?::uuid, 'personal_access_token', 'x', 'y')"

/**
 * Issuance, authentication, revocation and sign-in against a real Postgres.
 *
 * The unit suites prove the rules; this proves the statements, the constraints and the grants that
 * carry them — and that every mutating transaction writes exactly one audit row, because the
 * harness refuses to commit one that does not.
 */
class CredentialLifecycleTest :
    StringSpec({

        "issues a token, stores only its hash, and authenticates it back to its actor" {
            val identity = Identity()
            val actor = agent()
            val request = UUID.randomUUID()

            val issued =
                identity.issueToken.issue(
                    contextFor(actor, request),
                    CredentialTerms(CredentialLabel("release bot"), null),
                )
            val verifier = issued.plaintext.value.substringAfterLast('_')
            val stored =
                SchemaFixture.asApp {
                    it.textOf(
                        HASH_OF,
                        issued.credential.id.value
                            .toString(),
                    )
                }

            stored shouldNotBe null
            stored.orEmpty() shouldNotContain verifier

            identity.authenticate(issued.plaintext.value).actorId() shouldBe actor
        }

        "writes exactly one audit row for an issuance, which the harness would refuse otherwise" {
            val identity = Identity()
            val request = UUID.randomUUID()

            identity.issueToken.issue(contextFor(agent(), request), CredentialTerms(CredentialLabel("ci"), null))

            SchemaFixture.asApp { it.countBy(COUNT_BY_REQUEST, request) } shouldBe 1
        }

        // Guard: the revoked_at write and the REVOKED branch. The refusal comes from reading the row
        // back through a fresh transaction, not from anything held in memory.
        "refuses the token once its row carries a revocation" {
            val identity = Identity()
            val actor = agent()
            val issued = identity.issue(actor)

            identity.revokeCredential
                .revoke(contextFor(actor, UUID.randomUUID()), issued.credential.id)
                .shouldBeInstanceOf<RevokeCredentialResult.Revoked>()

            identity.authenticate(issued.plaintext.value).reason() shouldBe RejectionReason.REVOKED
        }

        "refuses a token whose stored expiry has passed" {
            val identity = Identity()
            val issued = identity.issue(agent(), expiresAt = START + 1.hours)

            identity.clock.advance(2.hours)

            identity.authenticate(issued.plaintext.value).reason() shouldBe RejectionReason.EXPIRED
        }

        // Guard: the actor_id condition in the revoke statement. Drop it and any authenticated caller
        // can revoke a credential that is not theirs.
        "refuses to revoke another actor's credential, leaving the row usable" {
            val identity = Identity()
            val owner = agent()
            val issued = identity.issue(owner)

            identity.revokeCredential.revoke(contextFor(agent(), UUID.randomUUID()), issued.credential.id) shouldBe
                RevokeCredentialResult.NotFound

            identity.authenticate(issued.plaintext.value).actorId() shouldBe owner
        }

        "signs a human in by e-mail code and mints a session that authenticates" {
            val identity = Identity()
            val human = human()
            val session = identity.signIn(human)

            session.refreshCredential.actorId.value
                .toString() shouldBe human.toString()
            identity.authenticate(session.accessToken.value).actorId() shouldBe human
        }

        // Guard: the SESSION check in CredentialAuthenticator.byToken, against a row that really is
        // live in the database rather than against a fake. Remove it and thirty days of API access
        // travel in a token whose only job is to be exchanged.
        "refuses the refresh token as a bearer credential while it still rotates" {
            val identity = Identity()
            val session = identity.signIn(human())

            identity.authenticate(session.refreshToken.value).reason() shouldBe RejectionReason.WRONG_KIND

            identity.refreshSession
                .refresh(session.refreshToken.value, Surface.WEB, request())
                .shouldBeInstanceOf<SignInResult.SignedIn>()
        }

        // Guard: the revoke in RefreshSession.rotate, against the row rather than against a fake.
        "rotates a session and leaves the presented refresh token revoked in the database" {
            val identity = Identity()
            val first = identity.signIn(human())

            identity.refreshSession
                .refresh(first.refreshToken.value, Surface.WEB, request())
                .shouldBeInstanceOf<SignInResult.SignedIn>()

            identity.refreshSession
                .refresh(first.refreshToken.value, Surface.WEB, request())
                .shouldBeInstanceOf<SignInResult.Rejected>()
                .reason shouldBe RejectionReason.REVOKED
        }

        "a delivered code is redeemable once, and the second attempt reads the consumed row" {
            val identity = Identity()
            val human = human()
            val address = addressOf(human)

            identity.requestSignInCode.request(address, Surface.WEB, request())
            val code = identity.mailbox.delivered.single()

            identity.redeemSignInCode
                .redeem(address, code, Surface.WEB, request())
                .shouldBeInstanceOf<SignInResult.SignedIn>()

            identity.redeemSignInCode
                .redeem(address, code, Surface.WEB, request())
                .shouldBeInstanceOf<SignInResult.Rejected>()
                .reason shouldBe RejectionReason.UNKNOWN
        }

        // Guard: credential_selector_idx in V7. Drop it and two credentials share a lookup key, so
        // authentication has two rows to choose from and picks one by accident.
        "refuses a second credential under the same selector" {
            val identity = Identity()
            val actor = agent()
            val issued = identity.issue(actor)
            val selector = issued.credential.selector.value

            SchemaFixture.asApp { it.countOf(SELECTORS, selector) } shouldBe 1

            runCatching {
                SchemaFixture.asAppRolledBack { it.write(INSERT_DUPLICATE, actor.toString(), selector) }
            }.isFailure shouldBe true
        }

        // Guard: the not-null on credential.selector in V7. Drop it and a credential can be written
        // that nothing is able to look up — a row that authenticates nobody, forever.
        "refuses a credential with no selector at all" {
            runCatching {
                SchemaFixture.asAppRolledBack { it.write(INSERT_WITHOUT_SELECTOR, agent().toString()) }
            }.isFailure shouldBe true
        }

        /*
         * Invariant #1 at the persistence layer: one table, one statement, one authentication path,
         * whichever kind of actor holds the credential. A branch anywhere below would show up here as
         * one of the two failing.
         */
        "an agent and a person hold the same shape of credential, through the same statements" {
            val identity = Identity()

            listOf(agent() to ActorKind.AGENT, human() to ActorKind.HUMAN).forEach { (actor, kind) ->
                val issued =
                    identity.issueToken.issue(
                        contextFor(actor, UUID.randomUUID(), kind),
                        CredentialTerms(CredentialLabel("a token"), null),
                    )

                identity.authenticate(issued.plaintext.value).actorId() shouldBe actor
            }
        }
    })

private fun agent(): UUID {
    val owner = UUID.randomUUID()
    val agent = UUID.randomUUID()
    SchemaFixture.asOwner {
        it.insertHuman(owner)
        it.insertAgent(agent, owner)
    }
    return agent
}

private fun human(): UUID = UUID.randomUUID().also { id -> SchemaFixture.asOwner { it.insertHuman(id) } }

private fun request(): RequestId = RequestId(UUID.randomUUID().toKotlinUuid())

private suspend fun Identity.issue(
    actor: UUID,
    expiresAt: kotlin.time.Instant? = null,
) = issueToken.issue(contextFor(actor, UUID.randomUUID()), CredentialTerms(CredentialLabel("a token"), expiresAt))

private suspend fun Identity.authenticate(presented: String): AuthenticationResult =
    authenticator.authenticate(presented, Surface.REST, request())

private fun addressOf(actor: UUID): Email =
    Email(checkNotNull(SchemaFixture.asOwner { it.textOf(EMAIL_OF, actor.toString()) }))

private suspend fun Identity.signIn(actor: UUID) =
    addressOf(actor).let { address ->
        requestSignInCode.request(address, Surface.WEB, request())
        redeemSignInCode
            .redeem(address, mailbox.delivered.last(), Surface.WEB, request())
            .shouldBeInstanceOf<SignInResult.SignedIn>()
            .session
    }

private fun AuthenticationResult.actorId(): UUID =
    UUID.fromString(
        shouldBeInstanceOf<AuthenticationResult.Authenticated>()
            .context.actorId.value
            .toString(),
    )

private fun AuthenticationResult.reason(): RejectionReason = shouldBeInstanceOf<AuthenticationResult.Rejected>().reason

private fun Connection.countOf(
    sql: String,
    key: String,
): Long =
    prepareStatement(sql).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }

private fun Connection.textOf(
    sql: String,
    key: String,
): String? =
    prepareStatement(sql).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

private fun Connection.write(
    sql: String,
    vararg values: String,
) {
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeUpdate()
    }
}
