package ai.nodera.application.permission

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.permission.CapabilityGrant
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.permission.defaultCapabilities
import ai.nodera.domain.project.ProjectId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours

private val FOUNDER = actor(1)
private val AGENT = actor(2)
private val OUTSIDER = actor(3)

class PermissionServiceTest :
    StringSpec({

        "a self-granted owner membership is the root of the chain and holds everything" {
            val directory = Directory().member(FOUNDER, ProjectRole.OWNER)

            engine(directory).effectiveCapabilities(FOUNDER, PROJECT) shouldBe
                Capability.entries.toSet()
        }

        // THE acceptance criterion. Nothing about the agent's own rows changes; the founder's set
        // shrinks, and the agent's shrinks with it, in the same call.
        // Guard: the `base intersect ceiling` in `step`. Delete it and this goes red.
        "revoking a capability from the grantor removes it from the grantee, without re-granting" {
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.MAINTAINER, grantedBy = FOUNDER)

            engine(directory).effectiveCapabilities(AGENT, PROJECT) shouldContain Capability.TICKET_CLOSE

            directory.grant(FOUNDER, Capability.TICKET_CLOSE, grantedBy = FOUNDER, granted = false)

            engine(directory).effectiveCapabilities(FOUNDER, PROJECT) shouldNotContain Capability.TICKET_CLOSE
            engine(directory).effectiveCapabilities(AGENT, PROJECT) shouldNotContain Capability.TICKET_CLOSE
            // Still a maintainer in every other respect — attenuation removes a verb, not a role.
            engine(directory).effectiveCapabilities(AGENT, PROJECT) shouldContain Capability.REVIEW_SUBMIT
        }

        "demoting the grantor's role attenuates the grantee to what the grantor now holds" {
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.MAINTAINER, grantedBy = FOUNDER)

            directory.member(FOUNDER, ProjectRole.CONTRIBUTOR)

            engine(directory).effectiveCapabilities(AGENT, PROJECT) shouldBe
                ProjectRole.CONTRIBUTOR.defaultCapabilities()
        }

        "a grantor with no membership in the project is a break, and the grantee gets nothing" {
            val directory =
                Directory()
                    .outsider(OUTSIDER)
                    .member(AGENT, ProjectRole.OWNER, grantedBy = OUTSIDER)

            engine(directory).effectiveCapabilities(AGENT, PROJECT).shouldBeEmpty()
        }

        // A self-granted MEMBERSHIP is the founding act; a cycle of two is laundering, and collapses.
        "a cycle of length two collapses to nothing for both actors" {
            val a = actor(10)
            val b = actor(11)
            val directory =
                Directory()
                    .member(a, ProjectRole.OWNER, grantedBy = b)
                    .member(b, ProjectRole.OWNER, grantedBy = a)

            engine(directory).effectiveCapabilities(a, PROJECT).shouldBeEmpty()
            engine(directory).effectiveCapabilities(b, PROJECT).shouldBeEmpty()
        }

        "a chain of sixteen hops resolves and a chain of seventeen does not" {
            val directory = Directory().member(actor(100), ProjectRole.OWNER)
            (1..17).forEach { hop ->
                directory.member(actor(100 + hop), ProjectRole.OWNER, grantedBy = actor(100 + hop - 1))
            }

            engine(directory).effectiveCapabilities(actor(116), PROJECT).shouldNotBeEmpty()
            engine(directory).effectiveCapabilities(actor(117), PROJECT).shouldBeEmpty()
        }

        // A wide graph resolves on its merits rather than on its size. The first version of this
        // engine walked the chain depth-first under a shared work budget, and the budget was spent in
        // row order: breaking G1's membership freed enough of it to reach G2, so the subject GAINED
        // ticket.close by losing a grantor. Found in phase-4 review; this is that graph.
        "breaking one grantor never grants a capability through another" {
            val subject = actor(200)
            val weak = actor(201)
            val strong = actor(202)

            // `weak` is listed before `strong` among the subject's grants, so any engine that spends
            // a shared budget in row order meets this subtree first. Sixteen grantors with three of
            // their own is 65 actors — past the 64 the first implementation allowed itself.
            val padding = listOf(Capability.TICKET_READ, Capability.TICKET_CREATE, Capability.TICKET_UPDATE)

            fun graph(weakIsAMember: Boolean): Directory {
                val directory =
                    Directory()
                        .member(subject, ProjectRole.OBSERVER)
                        .member(strong, ProjectRole.OWNER)
                        .grant(subject, Capability.AUDIT_READ, grantedBy = weak)
                        .grant(subject, Capability.TICKET_CLOSE, grantedBy = strong)
                if (!weakIsAMember) {
                    return directory.outsider(weak)
                }

                directory.member(weak, ProjectRole.CONTRIBUTOR)
                Capability.entries.forEachIndexed { i, capability ->
                    val filler = actor(300 + i)
                    directory.member(filler, ProjectRole.CONTRIBUTOR)
                    directory.grant(weak, capability, grantedBy = filler)
                    padding.forEachIndexed { j, padded ->
                        val leaf = actor(400 + i * padding.size + j)
                        directory.member(leaf, ProjectRole.CONTRIBUTOR)
                        directory.grant(filler, padded, grantedBy = leaf)
                    }
                }
                return directory
            }

            val intact = engine(graph(weakIsAMember = true)).effectiveCapabilities(subject, PROJECT)
            val broken = engine(graph(weakIsAMember = false)).effectiveCapabilities(subject, PROJECT)

            intact shouldContainAll broken
            // `weak` is a contributor and never holds audit.read, so neither side gets it.
            intact shouldNotContain Capability.AUDIT_READ
            intact shouldContain Capability.TICKET_CLOSE
        }

        // Guard: `frontier = next - closure.keys`. The property the test above depends on — cost
        // follows the graph, not the paths through it — is what makes a work budget unnecessary, and
        // a budget is what made a break grant capability. Re-read an actor and this goes red.
        "every actor in the grantor closure is read exactly once" {
            val root = actor(220)
            val short = actor(221)
            val long = actor(222)
            val middle = actor(223)
            val subject = actor(224)
            val directory =
                CountingDirectory(
                    Directory()
                        .member(root, ProjectRole.OWNER)
                        // `root` is reachable at depth 2 through `short` and again at depth 3
                        // through `long` -> `middle`. The two lengths are the point: a diamond
                        // whose sides are equal puts `root` in one breadth-first layer, where a
                        // plain set already deduplicates it and the subtraction guards nothing.
                        .member(short, ProjectRole.MAINTAINER, grantedBy = root)
                        .member(middle, ProjectRole.MAINTAINER, grantedBy = root)
                        .member(long, ProjectRole.MAINTAINER, grantedBy = middle)
                        .member(subject, ProjectRole.OBSERVER, grantedBy = short)
                        .grant(subject, Capability.TICKET_CLOSE, grantedBy = long),
                )

            engine(directory).effectiveCapabilities(subject, PROJECT) shouldContain Capability.TICKET_CLOSE

            directory.reads.values.max() shouldBe 1
        }

        "an expired membership holds nothing, at the instant it expires" {
            val directory = Directory().member(FOUNDER, ProjectRole.OWNER, expiresAt = NOW)

            engine(directory).effectiveCapabilities(FOUNDER, PROJECT).shouldBeEmpty()
            engine(directory, now = NOW - 1.hours).effectiveCapabilities(FOUNDER, PROJECT).shouldNotBeEmpty()
        }

        "an expired grant stops adding, and an expired denial stops subtracting" {
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.OBSERVER, grantedBy = FOUNDER)
                    .grant(AGENT, Capability.TICKET_CREATE, grantedBy = FOUNDER, expiresAt = NOW)
                    .grant(AGENT, Capability.TICKET_READ, grantedBy = FOUNDER, granted = false, expiresAt = NOW)

            val past = engine(directory, now = NOW - 1.hours).effectiveCapabilities(AGENT, PROJECT)
            past shouldContain Capability.TICKET_CREATE
            past shouldNotContain Capability.TICKET_READ

            val present = engine(directory).effectiveCapabilities(AGENT, PROJECT)
            present shouldNotContain Capability.TICKET_CREATE
            present shouldContain Capability.TICKET_READ
        }

        "a denial overrides the role default" {
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.MAINTAINER, grantedBy = FOUNDER)
                    .grant(AGENT, Capability.TICKET_CLOSE, grantedBy = FOUNDER, granted = false)

            engine(directory).effectiveCapabilities(AGENT, PROJECT) shouldNotContain Capability.TICKET_CLOSE
        }

        // Guard: applying denials LAST. Apply them before the additions and this goes red.
        //
        // `capability_grant` is unique on (project_id, actor_id, capability), so no database can hold
        // both rows and the fake refuses to as well — the state is reachable only through a directory
        // implementation that returns them anyway, which the port's type permits. Constructed here
        // deliberately, because "denials win" is a claim in the engine's KDoc and an unreachable claim
        // is still a claim somebody will rely on.
        "a denial wins over a positive grant even if a directory returns both rows, in either order" {
            val base =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.MAINTAINER, grantedBy = FOUNDER)
            val bothRows =
                listOf(
                    CapabilityGrant(PROJECT, AGENT, Capability.TICKET_CLOSE, true, FOUNDER, null),
                    CapabilityGrant(PROJECT, AGENT, Capability.TICKET_CLOSE, false, FOUNDER, null),
                )
            val directory =
                object : PermissionDirectory by base {
                    override suspend fun capabilityGrants(
                        projectId: ProjectId,
                        actorId: ActorId,
                    ): List<CapabilityGrant> =
                        if (actorId == AGENT) bothRows else base.capabilityGrants(projectId, actorId)
                }

            engine(directory).effectiveCapabilities(AGENT, PROJECT) shouldNotContain Capability.TICKET_CLOSE
            // Both orders, because this is the one state in which row order could decide the answer:
            // two rows touching the same verb. Everywhere else the port's rows fold into sets and
            // order cannot matter by construction, so reversing them would prove nothing.
            engine(ReversedGrants(directory)).effectiveCapabilities(AGENT, PROJECT) shouldNotContain
                Capability.TICKET_CLOSE
        }

        // Invariant C3's first half, and it needs no branch of its own: the solver starts everyone
        // at the empty set, so a self-grant has nothing to lift itself off.
        "an actor cannot grant itself a capability it does not already hold" {
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.OBSERVER, grantedBy = FOUNDER)
                    .grant(AGENT, Capability.MEMBER_GRANT, grantedBy = AGENT)

            engine(directory).effectiveCapabilities(AGENT, PROJECT) shouldNotContain Capability.MEMBER_GRANT
        }

        "a grant of a verb its grantor does not hold is inert" {
            val weakGrantor = actor(20)
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(weakGrantor, ProjectRole.CONTRIBUTOR, grantedBy = FOUNDER)
                    .member(AGENT, ProjectRole.OBSERVER, grantedBy = FOUNDER)
                    .grant(AGENT, Capability.TICKET_CLOSE, grantedBy = weakGrantor)
                    .grant(AGENT, Capability.TICKET_CREATE, grantedBy = weakGrantor)

            val capabilities = engine(directory).effectiveCapabilities(AGENT, PROJECT)
            capabilities shouldNotContain Capability.TICKET_CLOSE
            capabilities shouldContain Capability.TICKET_CREATE
        }

        "a suspended or retired actor holds nothing, whatever its role says" {
            ActorStatus.entries.filter { it != ActorStatus.ACTIVE }.forEach { status ->
                val directory = Directory().member(FOUNDER, ProjectRole.OWNER, status = status)
                engine(directory).effectiveCapabilities(FOUNDER, PROJECT).shouldBeEmpty()
            }
        }

        // Invariant #4's own worked example: Anna leaves, and every agent holding through her stops
        // in the same instant — without anyone remembering to touch the agent.
        "suspending the grantor empties the grantee in the same call" {
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.MAINTAINER, grantedBy = FOUNDER)

            engine(directory).effectiveCapabilities(AGENT, PROJECT).shouldNotBeEmpty()

            directory.member(FOUNDER, ProjectRole.OWNER, status = ActorStatus.SUSPENDED)

            engine(directory).effectiveCapabilities(AGENT, PROJECT).shouldBeEmpty()
        }

        "an actor the directory does not know holds nothing" {
            engine(Directory()).effectiveCapabilities(FOUNDER, PROJECT).shouldBeEmpty()
        }

        "capabilities are scoped to one project and never leak into another" {
            val directory = Directory().member(FOUNDER, ProjectRole.OWNER)

            engine(directory).effectiveCapabilities(FOUNDER, OTHER_PROJECT).shouldBeEmpty()
        }

        "require answers with the missing capability rather than a bare refusal" {
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.CONTRIBUTOR, grantedBy = FOUNDER)
            val permissions = engine(directory)

            permissions.require(context(AGENT), PROJECT, Capability.TICKET_UPDATE) shouldBe
                PermissionDecision.Permitted
            permissions.require(context(AGENT), PROJECT, Capability.TICKET_CLOSE) shouldBe
                PermissionDecision.Denied(AGENT, PROJECT, Capability.TICKET_CLOSE)
        }

        // Invariant #1, as an assertion rather than a claim: the same rows, the same answer, and the
        // only thing that differs is the kind the audit trail will record.
        "the answer does not depend on the acting actor's kind" {
            val directory =
                Directory()
                    .member(FOUNDER, ProjectRole.OWNER)
                    .member(AGENT, ProjectRole.MAINTAINER, grantedBy = FOUNDER)
            val permissions = engine(directory)

            permissions.require(context(AGENT, ActorKind.AGENT), PROJECT, Capability.TICKET_CLOSE) shouldBe
                permissions.require(context(AGENT, ActorKind.HUMAN), PROJECT, Capability.TICKET_CLOSE)
        }

        // The committed seed, resolved. Its comment claims the agent "may comment and update, but
        // explicitly NOT transition"; this is that claim checked rather than trusted.
        "the development seed's arrangement resolves to what the seed says it does" {
            val anna = actor(1)
            val triage = actor(2)
            val directory =
                Directory()
                    .member(anna, ProjectRole.OWNER)
                    .member(triage, ProjectRole.CONTRIBUTOR, grantedBy = anna)
                    .grant(triage, Capability.TICKET_TRANSITION, grantedBy = anna, granted = false)

            val agentCapabilities = engine(directory).effectiveCapabilities(triage, PROJECT)
            val ownerCapabilities = engine(directory).effectiveCapabilities(anna, PROJECT)

            agentCapabilities shouldContainAll setOf(Capability.COMMENT_CREATE, Capability.TICKET_UPDATE)
            agentCapabilities shouldNotContain Capability.TICKET_TRANSITION
            ownerCapabilities shouldContainAll agentCapabilities
            (ownerCapabilities - agentCapabilities).isEmpty() shouldBe false
        }
    })
