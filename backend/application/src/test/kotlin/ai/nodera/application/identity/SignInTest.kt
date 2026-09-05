package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.actor.Email
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.identity.SIGN_IN_CODE_MAX_ATTEMPTS
import ai.nodera.domain.identity.SignInCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes

private val STRANGER = Email("nobody@example.test")
private val OTHER_ADDRESS = Email("bruno@example.test")
private val WRONG_CODE = SignInCode("00000000")

private fun SignInResult.reason(): RejectionReason = shouldBeInstanceOf<SignInResult.Rejected>().reason

private fun World.withHuman() = actor(1).also { actors.add(it, ActorKind.HUMAN, email = ADDRESS) }

class SignInTest :
    StringSpec({

        "delivers a code to an address that belongs to somebody, and records one event" {
            val world = World()
            world.withHuman()

            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)

            world.delivery.delivered
                .single()
                .first shouldBe ADDRESS
            world.audit.events
                .single()
                .entry.action.value shouldBe "sign_in.requested"
        }

        // Guard: the null check on the resolved actor. Answer differently for an unknown address —
        // deliver, throw, or record anything — and this goes red. It is the enumeration oracle.
        "does nothing at all for an address that belongs to nobody, and says nothing either" {
            val world = World()
            world.withHuman()

            world.requestSignInCode.request(STRANGER, Surface.WEB, REQUEST)

            world.delivery.delivered.shouldBeEmpty()
            world.audit.events.shouldBeEmpty()
            world.codeRecords.shouldBeEmpty()
        }

        "refuses to start a sign-in for a suspended actor" {
            val world = World()
            val human = world.withHuman()
            world.actors.setStatus(human, ActorStatus.SUSPENDED)

            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)

            world.delivery.delivered.shouldBeEmpty()
            world.codeRecords.shouldBeEmpty()
        }

        // Guard: the recorder.record on the unusable branch of RequestSignInCode.mintFor. Fold that
        // branch back into a silent `if (usable)` and this goes red — invariant #3 covers attempts,
        // and here the actor is known, so nothing stops the refusal being recorded.
        "records the refusal when the address is known and the actor may not sign in" {
            val world = World()
            val human = world.withHuman()
            world.actors.setStatus(human, ActorStatus.SUSPENDED)

            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)

            val event = world.audit.events.single()
            event.entry.action.value shouldBe "sign_in.requested"
            event.entry.outcome shouldBe AuditOutcome.DENIED
            event.context.actorId shouldBe human
        }

        "signs in with the delivered code and mints a session" {
            val world = World()
            val human = world.withHuman()
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

            session.refreshCredential.actorId shouldBe human
            session.refreshToken.value.startsWith("nod_ref_") shouldBe true
            world.audit.events
                .last()
                .entry.action.value shouldBe "sign_in.redeemed"
        }

        // Guard: SignInCodeStore.consume's conditional write, which is the half this is red for.
        // The CONSUMED branch beside it is defence in depth: with it gone the claim refuses first.
        "refuses a code that has already been redeemed" {
            val world = World()
            world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
            val code =
                world.delivery.delivered
                    .single()
                    .second

            world.redeemSignInCode.redeem(ADDRESS, code, Surface.WEB, REQUEST)

            world.redeemSignInCode.redeem(ADDRESS, code, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN
        }

        // Guard: supersession in SignInCodeStore.issue. Without it both rows stay live and the count
        // below goes red. It is not what bounds redeemability: redemption reads the newest row only.
        "invalidates the previous code when a second one is requested" {
            val world = World()
            world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
            val first =
                world.delivery.delivered
                    .single()
                    .second
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)

            world.redeemSignInCode.redeem(ADDRESS, first, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN
            world.codeRecords.count { it.consumedAt == null } shouldBe 1
        }

        "refuses a code past its expiry" {
            val world = World()
            world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
            val code =
                world.delivery.delivered
                    .single()
                    .second

            world.clock.advance(CODE_TTL + 1.minutes)

            world.redeemSignInCode.redeem(ADDRESS, code, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN
        }

        // Guard: the attempt claim, which is the half this is red for — the counter, not the length,
        // is what makes eight digits safe. The EXHAUSTED branch beside it is defence in depth.
        "stops accepting the right code once the wrong one has been tried too often" {
            val world = World()
            world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
            val code =
                world.delivery.delivered
                    .single()
                    .second

            repeat(SIGN_IN_CODE_MAX_ATTEMPTS) {
                world.redeemSignInCode.redeem(ADDRESS, WRONG_CODE, Surface.WEB, REQUEST).reason() shouldBe
                    RejectionReason.UNKNOWN
            }

            world.codeRecords.single().attempts shouldBe SIGN_IN_CODE_MAX_ATTEMPTS
            world.redeemSignInCode.redeem(ADDRESS, code, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN
        }

        "records a refusal on the trail once the actor is known, with the outcome saying so" {
            val world = World()
            world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)

            world.redeemSignInCode.redeem(ADDRESS, WRONG_CODE, Surface.WEB, REQUEST)

            world.audit.events
                .last()
                .entry.outcome shouldBe AuditOutcome.DENIED
        }

        "answers an address that belongs to nobody the same way it answers a wrong code" {
            val world = World()
            world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)

            val stranger = world.redeemSignInCode.redeem(STRANGER, WRONG_CODE, Surface.WEB, REQUEST).reason()
            val wrong = world.redeemSignInCode.redeem(ADDRESS, WRONG_CODE, Surface.WEB, REQUEST).reason()

            stranger shouldBe wrong
        }

        /*
         * Guard: every codes.absorb call — the two in SignInCodes.redeem's refusal paths and the one
         * on RedeemSignInCode's unusable branch. Remove any of them and this goes red.
         *
         * The oracle is not "unknown address versus known one", it is *any* pair of branches that
         * differ. A live code exists only for the minutes after somebody asks for one, so the
         * ordinary state of a registered address is a branch with no stored hash to reach — and a
         * fix that levelled only the unknown-address branch would make a registered address the
         * fast one, which is the same question answered backwards.
         */
        "spends exactly one verification on every path a redemption can take" {
            val world = World()
            val human = world.withHuman()
            val suspended = actor(9).also { world.actors.add(it, ActorKind.HUMAN, email = OTHER_ADDRESS) }
            world.actors.setStatus(suspended, ActorStatus.SUSPENDED)

            val paths =
                listOf<Pair<String, suspend () -> Unit>>(
                    "an address that resolves to nobody" to {
                        world.redeemSignInCode.redeem(STRANGER, WRONG_CODE, Surface.WEB, REQUEST)
                    },
                    "a known address whose actor may not sign in" to {
                        world.redeemSignInCode.redeem(OTHER_ADDRESS, WRONG_CODE, Surface.WEB, REQUEST)
                    },
                    "a known address with no code outstanding" to {
                        world.redeemSignInCode.redeem(ADDRESS, WRONG_CODE, Surface.WEB, REQUEST)
                    },
                    "a known address with a live code and a wrong guess" to {
                        world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
                        world.redeemSignInCode.redeem(ADDRESS, WRONG_CODE, Surface.WEB, REQUEST)
                    },
                    "a known address redeeming the code it was sent" to {
                        world.redeemSignInCode.redeem(
                            ADDRESS,
                            world.delivery.delivered
                                .last()
                                .second,
                            Surface.WEB,
                            REQUEST,
                        )
                    },
                    "a known address whose code was already consumed" to {
                        world.redeemSignInCode.redeem(ADDRESS, WRONG_CODE, Surface.WEB, REQUEST)
                    },
                )

            paths.forEach { (path, redeem) ->
                val before = world.hasher.verifications
                redeem()
                withClue(path) { world.hasher.verifications - before shouldBe 1 }
            }
            world.actors.require(human).status shouldBe ActorStatus.ACTIVE
        }

        // Guard: the uniform RejectionReason.UNKNOWN in RedeemSignInCode.refuse. Return the cause
        // instead and this goes red — EXPIRED for a code that ran out says the address is
        // registered, which is a slower version of the same oracle the clock guard above closes.
        "answers every refusal the same way, whatever the cause the trail records" {
            val world = World()
            val human = world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
            world.clock.advance(CODE_TTL + 1.minutes)

            world.redeemSignInCode.redeem(ADDRESS, WRONG_CODE, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN

            val event = world.audit.events.last()
            event.entry.diff.after["reason"] shouldBe "expired"
            event.context.actorId shouldBe human
        }

        // Guard: the claimAttempt call and its result in SignInCodes.redeem. Count the guess after
        // the verification instead — an unconditional increment whose result nobody reads, which is
        // how it reads naturally — and this goes red. Invariant CR5 says why.
        "refuses the guess whose claim found no attempt left, however live the code looked" {
            val world = World(wrapCodes = ::AlreadyExhausted)
            world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
            val code =
                world.delivery.delivered
                    .single()
                    .second

            val before = world.hasher.verifications
            world.redeemSignInCode.redeem(ADDRESS, code, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN

            world.codeRecords.single().attempts shouldBe SIGN_IN_CODE_MAX_ATTEMPTS
            // The seventh path, and the only one a plain World cannot reach: it costs one
            // verification like the other six, so the claim does not become its own oracle.
            world.hasher.verifications - before shouldBe 1
            world.audit.events
                .last()
                .entry.diff.after["reason"] shouldBe "attempts_spent"
        }

        // Guard: the store.consume result in SignInCodes.redeem. Ignore it — call consume as a bare
        // statement and return Redeemed — and this goes red: two redemptions of one code both mint a
        // session, because both read the row before either wrote it.
        "refuses the redemption whose consume matched no row, because another one already won" {
            val world = World(wrapCodes = ::AlreadyConsumed)
            world.withHuman()
            world.requestSignInCode.request(ADDRESS, Surface.WEB, REQUEST)
            val code =
                world.delivery.delivered
                    .single()
                    .second

            world.redeemSignInCode.redeem(ADDRESS, code, Surface.WEB, REQUEST).reason() shouldBe
                RejectionReason.UNKNOWN
            world.audit.events
                .last()
                .entry.outcome shouldBe AuditOutcome.DENIED
        }
    })
