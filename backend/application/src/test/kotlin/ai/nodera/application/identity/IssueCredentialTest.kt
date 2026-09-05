package ai.nodera.application.identity

import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialLabel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class IssueCredentialTest :
    StringSpec({

        "issues a token for the acting actor and stores only its hash and its selector" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)

            val issued =
                world.issueToken.issue(
                    world.contextFor(agent),
                    CredentialTerms(CredentialLabel("release bot, staging"), null),
                )

            val stored = world.credentials.stored.single()
            stored.actorId shouldBe agent
            stored.kind shouldBe CredentialKind.PERSONAL_ACCESS_TOKEN
            issued.plaintext.value shouldContain stored.selector.value
            issued.plaintext.value shouldNotContain stored.secretHash.value
        }

        // Guard: invariant CR1's whole point. The row must not carry anything that reconstructs the
        // token — the selector addresses it, the hash proves it, and neither is the verifier.
        "keeps the verifier out of the row, so the plaintext cannot be read back" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)

            val issued =
                world.issueToken.issue(world.contextFor(agent), CredentialTerms(CredentialLabel("a token"), null))
            val verifier = issued.plaintext.value.substringAfterLast('_')

            world.credentials.stored
                .single()
                .toString() shouldNotContain verifier
        }

        "records the issuance with the label and never with the token" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)

            val issued =
                world.issueToken.issue(world.contextFor(agent), CredentialTerms(CredentialLabel("ci runner"), null))

            val event = world.audit.events.single()
            event.entry.action.value shouldBe "credential.issued"
            event.entry.diff.after["label"] shouldBe "ci runner"
            event.toString() shouldNotContain issued.plaintext.value
        }

        "the issued token authenticates as the actor it was issued for" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)

            val issued =
                world.issueToken.issue(world.contextFor(agent), CredentialTerms(CredentialLabel("a token"), null))
            val result = world.authenticator.authenticate(issued.plaintext.value, Surface.MCP, REQUEST)

            result.shouldBeInstanceOf<AuthenticationResult.Authenticated>().context.actorId shouldBe agent
        }

        // Guard: the actor_id condition in CredentialStore.revoke. Drop it and any authenticated
        // caller can revoke anybody's credential by guessing an id.
        "refuses to revoke a credential that belongs to another actor, reporting it as absent" {
            val world = World()
            val owner = world.withActor(1, ActorKind.AGENT)
            val other = world.withActor(2, ActorKind.HUMAN)
            world.tokenFor(owner)
            val credential = world.credentials.stored.single()

            world.revokeCredential.revoke(world.contextFor(other), credential.id) shouldBe
                RevokeCredentialResult.NotFound
            world.credentials.stored
                .single()
                .revokedAt shouldBe null
        }

        // Guard: the recorder.record on the NotFound branch. Remove it and an actor probing other
        // actors' credential ids leaves no trace at all — invariant #3 covers attempts, not successes.
        "records a refused revocation, so the trail answers what was attempted" {
            val world = World()
            val owner = world.withActor(1, ActorKind.AGENT)
            val other = world.withActor(2, ActorKind.HUMAN)
            world.tokenFor(owner)
            val credential = world.credentials.stored.single()

            world.revokeCredential.revoke(world.contextFor(other), credential.id)

            val event = world.audit.events.last()
            event.entry.action.value shouldBe "credential.revoked"
            event.entry.outcome shouldBe AuditOutcome.DENIED
            event.context.actorId shouldBe other
        }

        "revoking twice reports the second attempt as absent rather than revoking again" {
            val world = World()
            val agent = world.withActor(1, ActorKind.AGENT)
            world.tokenFor(agent)
            val credential = world.credentials.stored.single()

            world.revokeCredential
                .revoke(world.contextFor(agent), credential.id)
                .shouldBeInstanceOf<RevokeCredentialResult.Revoked>()

            world.revokeCredential.revoke(world.contextFor(agent), credential.id) shouldBe
                RevokeCredentialResult.NotFound
        }
    })
