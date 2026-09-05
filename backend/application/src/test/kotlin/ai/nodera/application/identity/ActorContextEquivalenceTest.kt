package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.Surface
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

private suspend fun World.contextOf(
    presented: String,
    surface: Surface,
): ActorContext =
    authenticator
        .authenticate(presented, surface, REQUEST)
        .shouldBeInstanceOf<AuthenticationResult.Authenticated>()
        .context

/**
 * SEC-01's fifth acceptance criterion, as assertions rather than as a comment.
 *
 * The claim is narrow and total: two credential shapes produce contexts that differ **only** in the
 * surface the request arrived on and in which actor was identified. Every test here is written as
 * an equality after normalising exactly those fields, so any other divergence — a field set on one
 * path and not the other, a default that differs — fails it.
 */
class ActorContextEquivalenceTest :
    StringSpec({

        "one actor with a personal access token and a session gets the identical context" {
            val world = World()
            val human = actor(1).also { world.actors.add(it, ActorKind.HUMAN, email = ADDRESS) }
            val token = world.tokenFor(human)

            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
            val code =
                world.delivery.delivered
                    .single()
                    .second
            val session =
                world.redeemSignInCode
                    .redeem(ADDRESS, code, Surface.WEB, REQUEST)
                    .shouldBeInstanceOf<SignInResult.SignedIn>()
                    .session

            val byToken = world.contextOf(token.value, Surface.REST)
            val bySession = world.contextOf(session.accessToken.value, Surface.REST)

            byToken shouldBe bySession
            byToken.actorId shouldBe human
        }

        "the same credential on two surfaces differs in the surface and in nothing else" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)
            val token = world.tokenFor(agent)

            val onRest = world.contextOf(token.value, Surface.REST)
            val onMcp = world.contextOf(token.value, Surface.MCP)

            onRest shouldNotBe onMcp
            onRest.copy(surface = Surface.MCP) shouldBe onMcp
        }

        /*
         * Invariant #1, stated as an equality: an agent and a person reach the same context through
         * the same path, holding the same shape of credential. Nothing about a personal access token
         * is agent-only, and nothing about the context varies with what kind of actor holds it.
         */
        "an agent and a person authenticating the same way differ only in who they are" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)
            val human = world.withActor(2, ActorKind.HUMAN)

            val byAgent = world.contextOf(world.tokenFor(agent).value, Surface.REST)
            val byHuman = world.contextOf(world.tokenFor(human).value, Surface.REST)

            byAgent.copy(actorId = human, kind = ActorKind.HUMAN) shouldBe byHuman
        }
    })
