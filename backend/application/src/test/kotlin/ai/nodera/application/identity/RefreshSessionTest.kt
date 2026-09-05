package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.audit.AuditOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val UNKNOWN_REFRESH_TOKEN =
    "nod_ref_000000000000000000000000_" +
        "1111111111111111111111111111111111111111111111111111111111111111"

private const val SECRET_DIGITS = 64

private fun SignInResult.session(): Session = shouldBeInstanceOf<SignInResult.SignedIn>().session

private fun SignInResult.reason(): RejectionReason = shouldBeInstanceOf<SignInResult.Rejected>().reason

private suspend fun World.firstSession(): Session {
    actor(1).also { actors.add(it, ActorKind.HUMAN, email = ADDRESS) }
    requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
    val code = delivery.delivered.single().second
    return redeemSignInCode.redeem(ADDRESS, code, Surface.WEB, REQUEST).session()
}

/** A world in which another rotation of the same token always commits first. */
private fun racingWorld(): World = World(wrapCredentials = ::AlreadyRotated)

class RefreshSessionTest :
    StringSpec({

        "exchanges a refresh token for a new session" {
            val world = World()
            val first = world.firstSession()

            val second = world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST).session()

            second.refreshToken shouldNotBe first.refreshToken
            second.refreshCredential.actorId shouldBe first.refreshCredential.actorId
        }

        // Guard: the revoke() call in RefreshSession.rotate. Remove it and a captured refresh token
        // stays usable for thirty days — which is the whole reason rotation is worth doing.
        "revokes the token it was given, so a replay meets a revoked credential" {
            val world = World()
            val first = world.firstSession()

            world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST).session()

            world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.REVOKED
        }

        // Guard: the owner carried on CredentialVerification.Refused and the record in
        // refuseVerified. Drop either — return the reason alone, the way a refusal reads naturally —
        // and this goes red while the suite stays green everywhere else: the *ordinary* replay is
        // the one that writes nothing, and it is the attempt rotation exists to catch.
        "records the ordinary replay, not only the one that lost a race" {
            val world = World()
            val first = world.firstSession()
            world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST).session()
            val beforeReplay = world.audit.events.size

            world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST)

            world.audit.events.size shouldBe beforeReplay + 1
            val event = world.audit.events.last()
            event.entry.action.value shouldBe "session.rotated"
            event.entry.outcome shouldBe AuditOutcome.DENIED
            event.entry.diff.after["reason"] shouldBe null
            event.entry.diff.before["reason"] shouldBe "revoked"
            event.context.actorId shouldBe first.refreshCredential.actorId
        }

        // A selector that addresses no row names no actor, so there is nothing to write: every
        // audit row carries an actor_id, and inventing one would put a false claim on the trail.
        "writes nothing for a token that addresses no credential at all" {
            val world = World()
            world.firstSession()
            val before = world.audit.events.size

            world.refreshSession.refresh(UNKNOWN_REFRESH_TOKEN, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN

            world.audit.events.size shouldBe before
        }

        // Guard: the owner on CredentialVerifier's wrong-secret refusal — the branch its KDoc names
        // and nothing else covered. Return the reason alone and this goes red: a guess against a
        // real selector is the most diagnostic attempt this path sees, and it would write nothing.
        "records a wrong verifier presented against a selector that does address a row" {
            val world = World()
            val first = world.firstSession()
            val guessed = first.refreshToken.value.substringBeforeLast('_') + "_" + "0".repeat(SECRET_DIGITS)
            val before = world.audit.events.size

            world.refreshSession.refresh(guessed, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN

            world.audit.events.size shouldBe before + 1
            val event = world.audit.events.last()
            event.entry.outcome shouldBe AuditOutcome.DENIED
            event.context.actorId shouldBe first.refreshCredential.actorId
        }

        // Guard: the null check on credentials.revoke in RefreshSession.replace. Discard that result
        // — as a bare statement, the way it reads naturally — and this goes red: verification has
        // already passed, so the loser of the race mints a second live session from one token.
        "refuses the rotation whose revocation matched no row, because another one already won" {
            val world = racingWorld()
            val first = world.firstSession()

            world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.REVOKED
        }

        "records the losing rotation as a denial, which is what an incident review reads" {
            val world = racingWorld()
            val first = world.firstSession()

            world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST)

            val event = world.audit.events.last()
            event.entry.action.value shouldBe "session.rotated"
            event.entry.outcome shouldBe AuditOutcome.DENIED
            event.entry.diff.before["credential"] shouldBe
                first.refreshCredential.id.value
                    .toString()
        }

        "mints nothing when it loses the race, so one token never yields two sessions" {
            val world = racingWorld()
            val first = world.firstSession()

            world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST)

            world.credentials.stored.size shouldBe 1
        }

        // Guard: the kind check on the presented credential. Drop it and a personal access token
        // rotates itself into a session — a credential shape converting into another.
        "refuses a personal access token, which is a credential but not a session" {
            val world = World()
            val agent = world.withActor(2, ActorKind.AGENT)
            val token = world.tokenFor(agent)

            world.refreshSession.refresh(token.value, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.WRONG_KIND
        }

        // The other direction of the same guard: the prefix gate above reads what arrived, so it is
        // the row's kind in CredentialVerifier that stops a personal access token being rotated into
        // a session. Drop that comparison and this goes red — and a PAT converts into a credential
        // shape nobody granted, revoking itself on the way.
        "refuses a personal access token re-labelled as a refresh token, and mints nothing" {
            val world = World()
            val agent = world.withActor(2, ActorKind.AGENT)
            val relabelled = world.tokenFor(agent).value.replace("nod_pat_", "nod_ref_")

            world.refreshSession.refresh(relabelled, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN
            world.credentials.stored.size shouldBe 1
            world.credentials.stored
                .single()
                .revokedAt shouldBe null
        }

        "refuses anything that is not a token at all" {
            World().refreshSession.refresh("not-a-token", Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.MALFORMED
        }

        "records the rotation on the trail, naming the credential it replaced" {
            val world = World()
            val first = world.firstSession()

            world.refreshSession.refresh(first.refreshToken.value, Surface.WEB, REQUEST).session()

            val event = world.audit.events.last()
            event.entry.action.value shouldBe "session.rotated"
            event.entry.diff.before["credential"] shouldBe
                first.refreshCredential.id.value
                    .toString()
        }
    })
