---
id: CORE-01
title: Actor model and permission engine in the domain core
priority: P1
status: open
effort: ~3 d
depends_on: []
created: 2026-08-20
updated: 2026-08-20
note: Everything references this — nothing else starts before it is reviewed.
---

# CORE-01 · Actor model and permission engine in the domain core

**Priority:** P1
**Effort:** ~3 d

## Motivation / context

The whole product rests on one decision: an actor is a human or an agent, and nothing branches on
which. That has to exist in code before anything is built on top of it, because retrofitting means
touching every call site that ever took a `userId`. The permission engine belongs in the same work
package: `PermissionService` is the object both surfaces will share, and "one permission engine" is
only structurally true if the second surface never has the option of its own.

## Current state (honest)

Nothing exists in `backend/` beyond module structure and build files. The model is fully specified
in `docs/DOMAIN_MODEL.md` sections 2 and 4, and `db/migrations/V1` matches it, but no Kotlin has
been written.

## Approach

1. `:domain` — `Actor`, `ActorKind`, `ActorId`, `HumanActor`, `AgentActor`, `Capability`,
   `ProjectRole`, `CapabilityGrant`, `ActorContext`. Value classes for identifiers.
2. Role-to-capability defaults as a pure function the permission engine can be exhaustive over.
3. `PermissionService` in `:application` with `effectiveCapabilities(actorId, projectId)` and
   `require(ctx, projectId, capability)`. Attenuation resolved through the grantor chain **at call
   time**, bounded at 16 hops, collapsing to an empty set on any break.
4. `ActorContext` as the first parameter of every use case — this package establishes the
   convention every later one copies.
5. Unit tests without a database: the domain is framework-free, so the permission algebra is
   testable in isolation and should be tested that way.

## To decide before starting

- Whether `Capability` is an enum or a sealed hierarchy. Recommendation: enum with a `verb` string,
  because the database stores it as text and the mapping should be trivial in both directions.

## Acceptance criteria

- [ ] `:domain` compiles with no dependency on Ktor, SQL, JSON or a logging framework, enforced by
      the Gradle configuration rather than by review.
- [ ] `effectiveCapabilities` returns the intersection with the grantor's current set; a test proves
      that revoking the grantor's capability removes it from the grantee without re-granting.
- [ ] A broken or cyclic grantor chain yields an empty capability set, never a permissive default —
      proved by a paired-negative test.
- [ ] No code path in the diff compares `ActorKind` to decide permission; `scripts/lint_invariants.py`
      enforces it and fails on a deliberately introduced violation.
- [ ] Every use case signature takes `ActorContext` as its first parameter.
- [ ] `make check` green.
- [ ] Independent review (phase 4, never the author): 0 BLOCKING findings.

## Affected files

- `backend/domain/src/main/kotlin/ai/nodera/domain/actor/` — the model.
- `backend/domain/src/main/kotlin/ai/nodera/domain/permission/` — capabilities, role defaults.
- `backend/application/src/main/kotlin/ai/nodera/application/permission/PermissionService.kt`.
- `scripts/lint_invariants.py` — the actor-kind sweep (new).

## Verification

`./gradlew :domain:test :application:test`. The attenuation test is run once with the grantor-chain
check disabled to confirm it goes red — a permission test never seen to fail proves only that the
code runs.
