# Plan — CORE-01 · Actor model and permission engine in the domain core

**Status:** `implemented`
**Ticket:** [`../../tickets/closed/CORE-01.md`](../../tickets/closed/CORE-01.md)
**Invariants this implements:** #1 (kind never gates permission) · #2 (one permission engine) ·
#4 (attenuation at use time) — [`../../skills/critical-invariants.md`](../../skills/critical-invariants.md)

---

## 1. What phase 1 found, and where it differs from the ticket

The ticket's current-state section holds: `backend/` has module structure, build files and the
OPS-01 entrypoints, and no domain type at all. Three corrections:

| Ticket says | Actually |
|---|---|
| Kotlin `2.1.20` → `2.4.x` | Already `2.4.10`. Raised by Dependabot [#22](https://github.com/bitoracle-ai/nodera/pull/22) after this ticket was written. |
| Ktor `3.1.2` → `3.4.x` | Already `3.5.2`, same route. |
| Exposed `0.60.0` → `1.x` | **Still `0.60.0`.** The one part of the toolchain criterion left to do. Latest release is `1.4.0`. |
| `scripts/lint_invariants.py` (new) | **Already exists**, with the actor-kind sweep. What is missing is the proof that it fires. |

So the toolchain step is smaller than written and the linter step is different from written: not
"write the sweep" but "prove the sweep, and extend it to cover the evasion shape this diff
introduces" (§ 5).

Exposed 1.4.0 costs nothing to adopt today because **no Kotlin compiles against Exposed yet** —
`:persistence` holds one file and it uses Flyway and raw JDBC. The 1.x package rename
(`org.jetbrains.exposed.sql` → `…v1.core` / `…v1.jdbc`) is therefore a no-op here and a large
package once DB-01 has written repositories. That is the ticket's whole ordering argument, and it
applies to Exposed specifically rather than to the catalogue in general.

## 2. Ordering, because one criterion is about ordering

The toolchain criterion cannot be shown by a diff, so it is executed and recorded as a sequence:

1. Raise Exposed, turn on explicit API mode, fix the existing sources it breaks.
2. Run the gates. **Green here, recorded, before step 3.**
3. Write the first `:domain` type.

## 3. The domain types

### 3.1 `ai.nodera.domain.actor`

| Type | Shape | Why |
|---|---|---|
| `ActorId` | `value class(Uuid)` | `kotlin.uuid.Uuid`, not `java.util.UUID` — the module stays JVM-free. |
| `ActorKind` | `enum { HUMAN, AGENT }` | Display and audit only. |
| `ActorStatus` | `enum { ACTIVE, SUSPENDED, RETIRED }` | Mirrors `actor_status`. |
| `Handle`, `DisplayName`, `Email` | value classes | `DisplayName` carries the schema's own rule — trimmed length 1..200 — so the domain refuses what the database would refuse. `Handle` and `Email` carry non-blank only: the schema constrains neither further, and inventing a format here would be a divergence, not an invariant. |
| `Actor` | sealed interface | `id`, `kind`, `handle`, `displayName`, `status`. |
| `HumanActor`, `AgentActor` | data classes | The two subtype tables, field for field. |
| `Surface` | `enum { WEB, REST, MCP, SYSTEM }` | The audit dimension of `ActorContext`. |
| `RequestId` | `value class(String)` | Correlates every event of one request. |
| `ActorContext` | data class | `actorId`, `kind`, `surface`, `onBehalfOf`, `requestId` — exactly `docs/ARCHITECTURE.md` § 5. |

**The sealed hierarchy is a hazard this plan creates and therefore has to close.** `Actor` being
sealed invites `when (actor) { is AgentActor -> … }`, which is invariant #1's forbidden branch in a
shape the current regex sweep cannot see — it looks for `kind` comparisons, and this one never
mentions `kind`. § 5 extends the sweep to cover it. The alternative, a flat `Actor` with a `kind`
field and no subtypes, was rejected: the subtypes hold genuinely different columns
(`email` vs `owner_actor_id`), and modelling them as nullable fields on one class would make every
adapter reconstruct the distinction by null-checking, which is the same branch with worse
diagnostics.

### 3.2 `ai.nodera.domain.permission`

`Capability` is an **enum with a `verb` string**, as the ticket's open question recommends. The
database stores `capability` as `text` with `check (capability ~ '^[a-z_]+\.[a-z_]+$')`, so the
mapping is `verb` in one direction and a lookup in the other, and a test asserts every verb
satisfies that regex — a capability the engine can express but the database would reject is a defect
that must not wait for an insert to surface.

Sixteen verbs, taken from `docs/DOMAIN_MODEL.md` § 4 and `docs/MCP.md` § 3 — the MCP tool table is
normative here, since it states the capability each tool passes to the check:

```
project.read  project.admin  member.grant  actor.read  audit.read
ticket.read  ticket.create  ticket.update  ticket.transition  ticket.close
ticket.assign  ticket.assign_self
comment.read  comment.create  comment.moderate
review.submit
```

Role defaults are a **pure function** `ProjectRole.defaultCapabilities()`, so the engine can be
exhaustive over it and it is testable with no fixture at all.

| Role | Set |
|---|---|
| `observer` | `project.read`, `actor.read`, `ticket.read`, `comment.read` |
| `contributor` | observer **+** `ticket.create`, `ticket.update`, `ticket.transition`, `ticket.assign_self`, `comment.create` |
| `maintainer` | contributor **+** `ticket.close`, `ticket.assign`, `comment.moderate`, `review.submit`, `audit.read` |
| `owner` | every capability (maintainer **+** `project.admin`, `member.grant`) |

Three deliberate departures from the § 4 table, each recorded because a reviewer will check:

1. **`project.read` and `actor.read` are on every role, observer included.** The § 4 table names the
   verbs that *distinguish* the roles, not the floor every member stands on. An observer holding
   `ticket.read` but not `project.read` cannot reach the project the ticket is in (`project_get`,
   MCP § 3.1) and cannot resolve a mention target (`actor_search`).
2. **`ticket.assign_self` is a contributor default.** MCP § 3.3: "Assigning to oneself needs
   `ticket.assign_self` only." A contributor who cannot pick up a ticket cannot do the thing the role
   exists for. Assigning *someone else* stays at maintainer, via `ticket.assign`.
3. **`audit.read` is a maintainer default.** § 4 does not place it; `audit_query` (MCP § 3.2) needs
   it. Maintainer rather than observer keeps the trail from being a general read surface, and owner
   holds it through "everything".

The sets are **monotone** — `observer ⊂ contributor ⊂ maintainer ⊂ owner` — asserted by a test.
That is not tidiness: attenuation intersects a grantee's defaults with a grantor's effective set, so
non-monotone roles would let a higher role silently attenuate a lower one.

`CapabilityGrant` and `ProjectMembership` are the two rows the engine reads, as domain values:
`grantedBy` and a nullable `expiresAt` on both, plus `granted: Boolean` on the grant (false = the
explicit denial the seed uses).

## 4. The permission engine

`ai.nodera.application.permission`, three files.

**The port is narrow on purpose** (ISP — `skills/backend-kotlin.md`): `PermissionDirectory` has the
three reads this service makes and nothing else. It returns **raw rows**, not "active" ones —
expiry is evaluated in the service against an injected clock, so every temporal rule is testable
without a database, which is the property the ticket is buying.

```kotlin
public interface PermissionDirectory {
    public suspend fun membership(projectId: ProjectId, actorId: ActorId): ProjectMembership?
    public suspend fun capabilityGrants(projectId: ProjectId, actorId: ActorId): List<CapabilityGrant>
    public suspend fun actorStatus(actorId: ActorId): ActorStatus?
}
```

### 4.1 The resolution rule

> **Revised after phase-4 review.** The version this plan first carried resolved the chain
> depth-first under a shared work budget, and § 4.3 claimed both of its bounds were fail-closed. The
> review disproved that — see § 4.3 — and the rule below is what was built instead. The earlier
> shape is described in § 4.3 rather than deleted, because the reason it is wrong is the most useful
> thing in this document.

`effectiveCapabilities(actor, project)` is computed in two steps, at call time.

**Step 1 — read the closure.** Breadth-first from the subject, following each actor's membership
grantor and the grantor of each live positive grant, to a depth of 16. Every actor is read exactly
once; the cost is the size of the grantor closure, not the number of paths through it. Actors past
depth 16 are never read, so they contribute nothing.

**Step 2 — solve.** The least fixed point of one step function, starting from *nobody holding
anything*:

```
step(actor):
  no usable membership (missing, expired, actor not active)  ->  {}
      -- and with no usable membership there are no grants either: an actor in that state
      -- contributes nothing at all, not "its grants minus its role"
  base    = defaultCapabilities(membership.role)
  ceiling = if membership.grantedBy == actor then base  -- the root, § 4.2
            else current[membership.grantedBy]
  caps    = base ∩ ceiling
  for each live positive grant g:
      caps += g.capability  if  g.capability ∈ current[g.grantedBy]
  caps -= every live denial's capability                -- unconditional
```

Iterate until stable. `step` is monotone in `current`, and the iteration starts at the bottom, so
this converges to the **least** fixed point — and that is not a formality, it is what buys the two
properties the engine has to have:

- **Nothing is granted that cannot be traced to a root.** A cycle starts at the empty set and stays
  there; no group of actors can vouch for one another into existence.
- **Removing authority can only remove capability.** A directory that returns fewer rows of
  *authority* — a deleted membership, a deleted positive grant, a demoted role, a suspended actor —
  yields a pointwise smaller fixed point. This is the property the first version did not have.
  Deleting a **denial** row is the one removal that widens the result, and it is not a
  counterexample: a denial is a restriction rather than authority. Revoking a verb in this model
  means *adding* a denial row, so a revocation is never a removal.

Denials are applied **last and unconditionally**. A denial is a restriction; requiring its grantor to
still hold the verb would mean revoking a person's access silently *widens* what their agent may do —
the exact inversion of invariant #4. `capability_grant` is unique on
`(project_id, actor_id, capability)`, so a verb has one row and the two orders coincide in any state
the schema permits; last is the fail-closed choice for a directory that returns both anyway.

### 4.2 The one genuine design decision: what terminates the chain

`project_membership.granted_by_actor_id` is `not null`, so the chain has no natural end, and
`db/seed/dev-seed.sql` shows the shape the schema affords: the project's founder holds an `owner`
membership **granted by herself**.

Because membership is keyed `(project_id, actor_id)`, each actor has exactly one grantor edge per
project: the graph is functional, and every chain ends in a self-loop, a cycle of length ≥ 2, or an
actor with no membership.

- **A self-granted _membership_ is the root.** Founding a project is not delegated authority, and
  there is nothing above it in the project to attenuate against. `base ∩ base = base`.
- **A cycle of length ≥ 2 is a break, and collapses to empty.** Two actors each drawing authority
  from the other is laundering with no independent root — precisely the escalation invariant #4
  exists to stop. Under the least fixed point this needs no cycle detection at all: the cycle simply
  never lifts off the empty set.
- **A self-granted _capability grant_ adds nothing**, and needs no special case either. Round one has
  the actor at the empty set, so the verb is not added; from round two the actor's own set is what it
  already legitimately holds, so a self-grant can only re-state. That is invariant C3's **first
  half** — an agent cannot grant more than it holds. C3's second half, that an agent may not grant
  `member.grant` at all, is a rule about *making* a grant and belongs to CORE-03.

The asymmetry between the two self-grants is deliberate and is the part of this plan most worth
keeping: one is the founding act, the other is self-escalation.

**The obligation this creates is on the write side, and CORE-03 inherits it.** One row —
`project_membership(role = 'owner', granted_by_actor_id = <self>)` — is total, unattenuated authority
in a project, and the engine has no check that could refuse it. Only project creation may write a
self-granted membership; every other path must refuse `grantedBy == actorId`. Stated here and in
`PermissionService.read` because there is nowhere else it could be inherited from.

### 4.3 Why there is one bound and not two

The ticket names a 16-hop bound on chain **depth**. Depth alone does not bound the work: an actor may
hold one grant per capability, so a naive walk branches up to seventeen ways per hop, and sixteen
hops of that does not return. A permission check that hangs is a denial of service on every surface
at once.

The first implementation answered that with a second bound — a shared budget of 64 actor resolutions
per call, spent in the order rows came back. **Phase-4 review showed that this breaks attenuation's
central promise.** On a graph where one grantor's subtree costs more than the budget, *removing* that
grantor's membership freed enough budget to reach a second grantor, and the subject gained
`ticket.close` by losing a grantor. A break yielded more, not less. The finding is reproduced as a
committed test, and it is red against the previous design and green against this one.

There is no fix for that shape of bound. Any cap that chooses which actors to drop makes the answer
depend on rows unrelated to the question. So the design changed instead: reading the closure
breadth-first, once per actor, makes the work a function of the graph rather than of its paths, and
no second bound is needed. **Depth 16 is the only bound.** Cost then belongs to the adapter — the
closure is one recursive query in SQL, and DB-01 owns that shape.

### 4.4 Actor status is part of attenuation, deliberately

Invariant #4's own example is "Anna leaves the team". Suspending or retiring an actor is the
operational form of leaving, and a `PermissionService` that ignored `actor.status` would leave a
suspended person's agents running on her authority — the failure the invariant names, one layer
down. It costs one port method, so it is in. Authentication will reject a suspended actor's
credential too (SEC-01); that is a second gate, not a reason to skip this one.

### 4.5 `require` returns a value

```kotlin
public suspend fun require(ctx: ActorContext, projectId: ProjectId, capability: Capability): PermissionDecision
```

Sealed `Permitted` / `Denied(actor, project, capability)` — errors are values in the domain
(`skills/backend-kotlin.md`), and `Denied` carries the capability so the REST 403 and the MCP
`forbidden` error can both name what was missing rather than returning a bare status.

`effectiveCapabilities(actorId, projectId)` keeps the ticket's signature and takes an `ActorId`, not
an `ActorContext`. It is not a use case — it is the engine's own query, and `whoami` (MCP § 3.1)
asks it about the caller's own id. The `ActorContext`-first convention binds use cases, and § 5
makes that mechanical.

## 5. `scripts/lint_invariants.py` — three changes

1. **`--self-test`.** The criterion is that the sweep "fails on a deliberately introduced
   violation". A linter that has never been seen to fire is an assertion, so the script grows a
   self-test that writes violating and clean fixtures to a temporary tree and asserts the verdict
   both ways. Wired into `make check-repo` and the CI repo-checks lane, so it runs on every change
   rather than once by hand.
2. **Two shapes the sweep could not see.** `when (actor.kind) { … }` — a branch that never writes a
   comparison operator — and `is HumanActor` / `is AgentActor`, the shape § 3.1's sealed hierarchy
   introduces. Both outside the display/audit allowlist.
3. **`ActorContext` first, mechanically.** Any `fun` in `backend/application/**/usecase/**` whose
   first parameter is not `ctx: ActorContext` is a finding. There are no use cases yet, so it binds
   nothing today and binds CORE-03 onward; the self-test is what proves it works in the meantime.

`skills/critical-invariants.md` names the two shapes in change 2 as "explicit reviewer duties"
because the scan could not see them. That sentence becomes false with this change, so the paragraph
is corrected in the same diff. It is an entry file, edited here inside the work package that owns
invariant #1 — invariant #12 permits exactly that and forbids doing it in passing.

## 6. Test plan

`:domain`, no fixtures, no database:

- every `Capability.verb` satisfies the schema's regex, and `fromVerb` round-trips;
- verbs are unique;
- role defaults are monotone across the four roles;
- `owner` is the full set;
- `DisplayName` refuses blank and refuses 201 characters, accepts 200 and accepts padding that
  trims into range.

`:application`, against an in-memory `PermissionDirectory`:

| Test | Asserts |
|---|---|
| founder | self-granted `owner` holds every capability |
| **revocation** | grantor's `ticket.close` denied → grantee loses it **without touching the grantee's rows** |
| cycle | A granted by B, B granted by A → both empty |
| broken chain | grantor holds no membership in the project → empty |
| depth | a 17-hop chain → empty; 16 hops → resolves |
| **monotonicity** | on the review's own graph, breaking one grantor never adds a capability through another |
| **cost** | an actor reachable at two different depths is still read once |
| expiry | expired membership → empty; expired grant → verb absent; expired **denial** → verb returns |
| denial | denial beats the role default; and beats a positive grant even when a directory returns both rows in either order, which the unique key forbids |
| self-grant | self-granted *capability* adds nothing; self-granted *membership* is the root |
| status | suspended actor → empty; suspended **grantor** → grantee empty |
| attenuation of a grant | a grant of a verb its grantor does not hold is inert |
| `require` | `Permitted` / `Denied` carrying the capability |
| seed shape | the `dev-seed.sql` arrangement resolves to exactly what the seed's comment claims |

### 6.1 The paired negatives

The ticket asks for the attenuation test "run once with the grantor-chain check disabled, to confirm
it goes red". **A flag on `PermissionService` that disables attenuation is refused** — invariant #2
names "a bypass flag for tests that then leaks" explicitly, and a switch that turns off a security
check is that flag whatever it is called.

Instead the test source set carries `UnattenuatedReference`, a deliberately naive engine — role
defaults plus grants, no grantor chain — and the test asserts that the two **disagree exactly on the
revoked capability**. Delete the attenuation code from the engine and the two agree, and the test goes
red. The negative is committed and runs on every test run, in the same shape as invariant F1's
(`frontend/eslint.selftest.mjs`, moved out of vitest by FIX-02), rather than being a manual step a
reader has to trust somebody performed.

Four guards were confirmed red rather than assumed, each by disabling exactly one thing:

| Disabled | Tests that went red |
|---|---|
| `base ∩ ceiling` in `step` | 8, both permission specs |
| the bottom of the lattice (start from every capability instead of none) | 2 — the cycle and the self-grant |
| reading the whole closure (cap it) | 1 — the depth chain |
| the visited-set subtraction, so actors are re-read | 1 — the read-once test |
| applying denials last rather than before the additions | 1 — the both-rows denial test |
| the whole design (the previous depth-first, budgeted engine, swapped back in) | 1 — the monotonicity test added for review finding B1 |

The last row is the one that matters most: the monotonicity test is a regression test for a *design*,
so a one-line mutation cannot express it. It was checked by restoring the previous implementation and
watching it fail.

**One branch is deliberately not in that table.** `step`'s `?: NO_CAPABILITIES` — the ceiling for a
`Grantee` with no grantor — has no observable effect, because the only such `Grantee` is `NONE` and
its base is empty. Changing it to `?: grantee.base` leaves every test green. It is kept, fail-closed,
against a future `Grantee` that keeps a role for an actor with no usable membership, and the code
says so rather than implying a guard it does not provide.

## 7. Deliberate non-goals

- **No repository implementation.** `PermissionDirectory` has no `:persistence` adapter here; DB-01
  writes it against a real database with RLS. This package is the algebra, and it is proved without
  a container.
- **No caching.** A cache is a correctness hazard for an invariant whose entire point is "at the
  moment of use". It is a measured optimisation later, with an explicit invalidation story, or not
  at all.
- **No grant/revoke use cases.** Mutation is CORE-03's, and it needs the audit recorder (CORE-02)
  that does not exist.
- **No `Project`, `Ticket` or `Review` entity.** Only `ProjectId`, because the engine is scoped by
  it.
- **No Exposed 1.x code.** The version is raised; nothing compiles against it until DB-01.

## 8. What implementation found that this plan did not predict

**`backend/detekt.yml` and the ktlint version pin had never applied to any module.** A bare
`detekt { }` / `ktlint { }` block at the top level of `backend/build.gradle.kts` configures the
**root project's** extension; the six modules apply the same plugins inside `subprojects { }` and get
their own extensions, with defaults. So every module was linted by **ktlint 1.0.1** — the
ktlint-gradle default, not the 1.5.0 named two lines above it — and analysed by detekt's default
thresholds rather than the committed file. The file is documented, named in `backend/CLAUDE.md`, and
was doing nothing.

Found because detekt rejected this package's code citing thresholds `detekt.yml` does not contain:
`LongParameterList` at 6 where the file says 7, `ReturnCount` at 2 where the file says 4. Shaping the
domain around numbers the repository does not intend would have buried the defect, so the wiring is
fixed here (an `allprojects { }` block after `subprojects`), and both findings turned out to be
artefacts of the defaults.

**The cost is visible in the diff and is not scope creep to hide.** Making the ktlint pin real
reformats seven pre-existing files, +226/−211, mechanically — every rule change between 1.0.1 and
1.5.0 landing at once. There is no version of this that is both honest and small: the alternative is
leaving a committed, documented pin inert, which is the shape CI-01 and OPS-01 were both about. No
new detekt finding appeared in existing code once the real thresholds applied.

## 9. Open questions, each with a recommendation

| Question | Recommendation |
|---|---|
| Is a self-granted membership the root, or a cycle? | **Root** (§ 4.2). The alternative makes every project founder powerless and contradicts the committed seed. |
| Should `actor.status` be part of the engine? | **Yes** (§ 4.4). One port method against the invariant's own worked example. |
| Enum or sealed `Capability`? | **Enum with a verb** — the ticket's own recommendation; the database stores text. |
| Should `require` throw on denial? | **No.** Sealed result; adapters map it. A throwing check makes a denial an exception the MCP layer has to re-derive structure from. |
