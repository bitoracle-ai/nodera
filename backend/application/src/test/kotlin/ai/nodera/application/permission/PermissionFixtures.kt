package ai.nodera.application.permission

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.ActorStatus
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.permission.Capability
import ai.nodera.domain.permission.CapabilityGrant
import ai.nodera.domain.permission.ProjectMembership
import ai.nodera.domain.permission.ProjectRole
import ai.nodera.domain.project.ProjectId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val PROJECT: ProjectId = ProjectId(Uuid.parse("33333333-3333-4333-8333-333333333333"))
internal val OTHER_PROJECT: ProjectId = ProjectId(Uuid.parse("44444444-4444-4444-8444-444444444444"))
internal val NOW: Instant = Instant.parse("2026-08-25T12:00:00Z")

/** Distinct, readable, and stable across runs — a failing assertion names the same actor every time. */
internal fun actor(seed: Int): ActorId =
    ActorId(Uuid.parse("00000000-0000-4000-8000-" + seed.toString().padStart(12, '0')))

internal class FixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

/**
 * An in-memory [PermissionDirectory].
 *
 * The whole point of the port returning stored rows rather than "active" ones: every temporal and
 * structural rule below is exercised here, with no database, no container and no Docker daemon.
 */
internal class Directory : PermissionDirectory {
    private val memberships = mutableMapOf<Pair<ProjectId, ActorId>, ProjectMembership>()
    private val grants = mutableMapOf<Pair<ProjectId, ActorId>, MutableList<CapabilityGrant>>()
    private val statuses = mutableMapOf<ActorId, ActorStatus>()

    /** Defaults to a self-granted membership — the founding shape `db/seed/dev-seed.sql` uses. */
    fun member(
        actor: ActorId,
        role: ProjectRole,
        grantedBy: ActorId = actor,
        expiresAt: Instant? = null,
        status: ActorStatus = ActorStatus.ACTIVE,
        project: ProjectId = PROJECT,
    ): Directory {
        memberships[project to actor] = ProjectMembership(project, actor, role, grantedBy, expiresAt)
        statuses[actor] = status
        return this
    }

    /** Registers an actor that exists but holds no membership in the project. */
    fun outsider(
        actor: ActorId,
        status: ActorStatus = ActorStatus.ACTIVE,
    ): Directory {
        statuses[actor] = status
        return this
    }

    /**
     * Replaces on `(project, actor, capability)`, because `capability_grant` is unique on exactly
     * that. A fake that accepts two rows for one verb lets a test demonstrate behaviour on a state
     * the database cannot hold — which is a test that proves nothing about the running system.
     */
    fun grant(
        actor: ActorId,
        capability: Capability,
        grantedBy: ActorId,
        granted: Boolean = true,
        expiresAt: Instant? = null,
    ): Directory {
        val rows = grants.getOrPut(PROJECT to actor) { mutableListOf() }
        rows.removeAll { it.capability == capability }
        rows += CapabilityGrant(PROJECT, actor, capability, granted, grantedBy, expiresAt)
        return this
    }

    override suspend fun membership(
        projectId: ProjectId,
        actorId: ActorId,
    ): ProjectMembership? = memberships[projectId to actorId]

    override suspend fun capabilityGrants(
        projectId: ProjectId,
        actorId: ActorId,
    ): List<CapabilityGrant> = grants[projectId to actorId].orEmpty()

    override suspend fun actorStatus(actorId: ActorId): ActorStatus? = statuses[actorId]
}

internal fun engine(
    directory: PermissionDirectory,
    now: Instant = NOW,
): PermissionService = PermissionService(directory, FixedClock(now))

/**
 * The same rows, handed back in the opposite order.
 *
 * `PermissionDirectory` promises no ordering, and a real SQL adapter will not guarantee one either.
 * An engine whose answer moves with the order is an engine whose answer depends on data unrelated to
 * the question, so every ordering-sensitive test runs through this as well as through the fake.
 */
internal class ReversedGrants(
    private val inner: PermissionDirectory,
) : PermissionDirectory by inner {
    override suspend fun capabilityGrants(
        projectId: ProjectId,
        actorId: ActorId,
    ): List<CapabilityGrant> = inner.capabilityGrants(projectId, actorId).reversed()
}

/** Counts how often each actor is looked up, so a test can assert the cost of one question. */
internal class CountingDirectory(
    private val inner: PermissionDirectory,
) : PermissionDirectory by inner {
    val reads: MutableMap<ActorId, Int> = mutableMapOf()

    override suspend fun membership(
        projectId: ProjectId,
        actorId: ActorId,
    ): ProjectMembership? {
        reads[actorId] = (reads[actorId] ?: 0) + 1
        return inner.membership(projectId, actorId)
    }
}

/**
 * [kind] is set here because [ActorContext] carries it for audit. Nothing in the engine reads it —
 * the tests that matter set it to `AGENT` and expect exactly the human's answer.
 */
internal fun context(
    actorId: ActorId,
    kind: ActorKind = ActorKind.AGENT,
): ActorContext =
    ActorContext(
        actorId = actorId,
        kind = kind,
        surface = Surface.MCP,
        onBehalfOf = null,
        requestId = RequestId("test-request"),
    )
