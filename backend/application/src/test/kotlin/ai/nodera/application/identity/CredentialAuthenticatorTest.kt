package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.actor.Surface
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours

private const val UNKNOWN_TOKEN =
    "nod_pat_6f1c9a4b2e8d70a3c5f2b1e4_" +
        "9c2e4a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"

private const val SECRET_DIGITS = 64

private fun AuthenticationResult.reason(): RejectionReason = shouldBeInstanceOf<AuthenticationResult.Rejected>().reason

private fun AuthenticationResult.context(): ActorContext =
    shouldBeInstanceOf<AuthenticationResult.Authenticated>().context

private suspend fun World.signIn(): Session {
    requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
    val code = delivery.delivered.last().second
    val result = redeemSignInCode.redeem(ADDRESS, code, Surface.WEB, REQUEST)
    return result.shouldBeInstanceOf<SignInResult.SignedIn>().session
}

class CredentialAuthenticatorTest :
    StringSpec({

        "accepts a live personal access token and names the actor it belongs to" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)
            val token = world.tokenFor(agent)

            val context = world.authenticator.authenticate(token.value, Surface.MCP, REQUEST).context()

            context.actorId shouldBe agent
            context.surface shouldBe Surface.MCP
            context.requestId shouldBe REQUEST
        }

        // Guard: the REVOKED branch in CredentialVerifier. Treat it as live and this goes red.
        "refuses a revoked credential, so revocation takes effect on the next request" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)
            val token = world.tokenFor(agent)
            val credential = world.credentials.stored.single()

            world.revokeCredential.revoke(world.contextFor(agent), credential.id)

            world.authenticator.authenticate(token.value, Surface.REST, REQUEST).reason() shouldBe
                RejectionReason.REVOKED
        }

        // Guard: the EXPIRED branch in CredentialVerifier. Treat it as live and this goes red.
        "refuses an expired credential once its instant has passed, having accepted it before" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)
            val token = world.tokenFor(agent, expiresAt = START + 1.hours)

            world.authenticator
                .authenticate(token.value, Surface.REST, REQUEST)
                .context()
                .actorId shouldBe agent

            world.clock.advance(2.hours)

            world.authenticator.authenticate(token.value, Surface.REST, REQUEST).reason() shouldBe
                RejectionReason.EXPIRED
        }

        // Guard: ActorPrincipal.rejectionIfUnusable. Remove it and a suspended actor's tokens work on.
        "refuses a live credential whose actor was suspended" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)
            val token = world.tokenFor(agent)

            world.actors.setStatus(agent, ActorStatus.SUSPENDED)

            world.authenticator.authenticate(token.value, Surface.REST, REQUEST).reason() shouldBe
                RejectionReason.INACTIVE_ACTOR
        }

        "refuses a well-formed token that addresses no credential" {
            World().authenticator.authenticate(UNKNOWN_TOKEN, Surface.REST, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN
        }

        // Two different answers would tell a caller which half of the token it got right.
        "answers a wrong secret exactly as it answers a selector that addresses nothing" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)
            world.tokenFor(agent)
            val selector =
                world.credentials.stored
                    .single()
                    .selector
            val guessed = "nod_pat_${selector.value}_${"0".repeat(SECRET_DIGITS)}"

            val wrongSecret = world.authenticator.authenticate(guessed, Surface.REST, REQUEST).reason()
            val unknownSelector = world.authenticator.authenticate(UNKNOWN_TOKEN, Surface.REST, REQUEST).reason()

            wrongSecret shouldBe unknownSelector
        }

        "refuses anything that is neither a token this deployment issues nor one it signed" {
            val world = World()

            world.authenticator.authenticate("not-a-credential", Surface.REST, REQUEST).reason() shouldBe
                RejectionReason.MALFORMED
            world.authenticator.authenticate("", Surface.REST, REQUEST).reason() shouldBe
                RejectionReason.MALFORMED
        }

        "accepts the access token a completed sign-in minted" {
            val world = World()
            val human = actor(2).also { world.actors.add(it, ActorKind.HUMAN, email = ADDRESS) }
            val session = world.signIn()

            val context = world.authenticator.authenticate(session.accessToken.value, Surface.WEB, REQUEST).context()

            context.actorId shouldBe human
            session.accessToken.expiresAt shouldBe START + ACCESS_TTL
        }

        // Guard: the SESSION check in CredentialAuthenticator.byToken. Remove it and the refresh
        // token authenticates like any other credential — so a captured one serves requests for its
        // full thirty days without ever rotating, and the rotation that detects the replay, the
        // fifteen-minute access token and the whole point of the split are gone together.
        "refuses a refresh token, which is spent at the rotation path and nowhere else" {
            val world = World()
            val human = actor(2).also { world.actors.add(it, ActorKind.HUMAN, email = ADDRESS) }
            val session = world.signIn()

            world.authenticator.authenticate(session.refreshToken.value, Surface.REST, REQUEST).reason() shouldBe
                RejectionReason.WRONG_KIND

            // The credential behind it is live: the refusal is about which door it opens.
            world.refreshSession
                .refresh(session.refreshToken.value, Surface.WEB, REQUEST)
                .shouldBeInstanceOf<SignInResult.SignedIn>()
                .session.refreshCredential.actorId shouldBe human
        }

        /*
         * Guard: the kind comparison against the *row* in CredentialVerifier. Gate on the presented
         * prefix alone — which is what the two callers read — and this goes red while every other
         * spec stays green: a selector is unique across the whole table, so swapping three
         * characters of the prefix addresses the same row with a different claim about what it is,
         * and the refresh-token refusal above becomes a formality anyone can step around.
         */
        "refuses a refresh token re-labelled as a personal access token" {
            val world = World()
            actor(2).also { world.actors.add(it, ActorKind.HUMAN, email = ADDRESS) }
            val session = world.signIn()
            val relabelled = session.refreshToken.value.replace("nod_ref_", "nod_pat_")

            world.authenticator.authenticate(relabelled, Surface.REST, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN
        }

        // Guard: the directory read on the access-token path. Trust the token alone and this goes
        // red — a suspended actor would keep working for the rest of its fifteen minutes.
        "refuses a still-valid access token once its actor is suspended" {
            val world = World()
            val human = actor(2).also { world.actors.add(it, ActorKind.HUMAN, email = ADDRESS) }
            val session = world.signIn()

            world.actors.setStatus(human, ActorStatus.SUSPENDED)

            world.authenticator.authenticate(session.accessToken.value, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.INACTIVE_ACTOR
        }
    })
