---
id: CORE-01
title: Actor model and permission engine in the domain core
priority: P1
status: closed
effort: ~3 d
depends_on: []
created: 2026-08-20
updated: 2026-08-25
closed: 2026-08-25
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

**Closed 2026-08-25.** When this ticket was written, `backend/` held module
structure and build files and no domain type at all. It now holds:

- `:domain` — `ai.nodera.domain.{actor,permission,project}`: `Actor` (sealed, with `HumanActor` and
  `AgentActor`), `ActorKind`, `ActorStatus`, `ActorId`, `Handle`, `DisplayName`, `Email`,
  `ActorContext`, `Surface`, `RequestId`, `ProjectId`, `Capability` (16 verbs), `ProjectRole` with
  `defaultCapabilities()`, `ProjectMembership`, `CapabilityGrant`.
- `:application` — `PermissionDirectory` (a three-method port), `PermissionDecision`, and
  `PermissionService`, which reads the grantor closure breadth-first and resolves it as the least
  fixed point of a monotone step function.
- 64 backend tests, 0 failures. Six guards were confirmed red by disabling exactly one thing each —
  see `docs/plan/CORE-01.md` § 6.1 for the table and for the one branch that is deliberately
  unobservable.

Two things this ticket asked for were already done when work started, by Dependabot rather than by
this package: Kotlin was at `2.4.10` and Ktor at `3.5.2`. `scripts/lint_invariants.py` also already
existed. See the toolchain section below, which is corrected in place.

The engine is the algebra only. `PermissionDirectory` has no `:persistence` implementation — DB-01
writes it — so nothing here has run against a database.

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

## Toolchain baseline — first, not after

This package writes the first `:domain` types, so it fixes the identifier representation for
everything that follows. The version catalogue had to be raised **before** that, because afterwards
every change to it is a migration across the whole domain.

The ticket named three bumps. **Two of them had already landed** through Dependabot after this
ticket was written, and saying so here rather than claiming credit for them: Kotlin `2.1.20` →
`2.4.10` (#22) and Ktor `3.1.2` → `3.5.2`. Only **Exposed `0.60.0` → `1.4.0`** was left, and it is
the one the ordering argument actually applies to — no Kotlin compiles against Exposed yet, so the
1.x package rename costs nothing today and would be a large package once DB-01 has written
repositories.

Two further build changes belong to this baseline and were not foreseen:

- **Explicit API mode** (`-Xexplicit-api=strict`) for every module, which is its own acceptance
  criterion below.
- **`backend/detekt.yml` and the ktlint version pin had never applied to any module.** A bare
  `detekt { }` / `ktlint { }` block at the top level of `backend/build.gradle.kts` configures the
  root project only; the six modules got their own extensions with defaults. Every module was being
  linted by ktlint **1.0.1**, the plugin's default, rather than the 1.5.0 named two lines above it,
  and analysed by detekt's default thresholds rather than the committed file. Fixed with an
  `allprojects { }` block. The cost is visible: making the pin real reformats seven pre-existing
  files, mechanically.

Raising versions is not an ADR — see `docs/adr/README.md` — but doing it after `:domain` exists is a
much larger package than doing it before.

## Acceptance criteria

- [x] `:domain` compiles with no dependency on Ktor, SQL, JSON or a logging framework, enforced by
      the Gradle configuration rather than by review.
- [x] `effectiveCapabilities` returns the intersection with the grantor's current set; a test proves
      that revoking the grantor's capability removes it from the grantee without re-granting.
- [x] A broken or cyclic grantor chain yields an empty capability set, never a permissive default —
      proved by a paired-negative test.
- [x] No code path in the diff compares `ActorKind` to decide permission; `scripts/lint_invariants.py`
      enforces it and fails on a deliberately introduced violation.
- [x] Every use case signature takes `ActorContext` as its first parameter. **Vacuously — there are
      no use cases yet.** `PermissionService.require(ctx, …)` establishes the shape, the rule is now
      mechanical in `lint_invariants.py`, and the directory it binds is named in `backend/CLAUDE.md`,
      `backend/AGENTS.md`, `.github/instructions/backend.instructions.md` and
      `skills/backend-kotlin.md` so CORE-03 does not have to guess it.
- [x] The version catalogue is raised as above and `make check` is green on it **before** any
      `:domain` type is written — recorded in the review result, since the diff cannot show ordering.
- [x] `:domain` uses `kotlin.uuid.Uuid`; `java.util.UUID` appears only in adapters, at the boundary.
      There is in fact no `java.util.UUID` anywhere in `backend/` yet.
- [x] Explicit API mode (`-Xexplicit-api=strict`) is on for every module and the build is green
      under it.
- [x] `make check` green — every lane run, though not as one `make check` invocation; the review
      result names exactly which command produced which lane.
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings. Round 2.

## Affected files

- `backend/gradle/libs.versions.toml` — Exposed `0.60.0` → `1.4.0`.
- `backend/build.gradle.kts` — explicit API mode; the `allprojects { }` block that makes
  `detekt.yml` and the ktlint pin reach the six modules.
- `backend/domain/src/main/kotlin/ai/nodera/domain/{actor,permission,project}/` — the model.
- `backend/application/src/main/kotlin/ai/nodera/application/permission/` — port, decision, engine.
- Tests under `backend/domain/src/test/` and `backend/application/src/test/`.
- `scripts/lint_invariants.py` — **not new.** Gains `--self-test`, two further shapes of the
  actor-kind branch, and the `ActorContext`-first rule.
- `Makefile`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `docs/ci.md` — wiring the
  self-test into the gates.
- `skills/critical-invariants.md`, `skills/backend-kotlin.md`, `docs/DOMAIN_MODEL.md`,
  `backend/CLAUDE.md`, `backend/AGENTS.md`, `.github/instructions/backend.instructions.md`,
  `.claude/agents/reviewer.md`, `docs/prompts/code-review.prompt.md` — claims corrected to match.
- Seven pre-existing files reformatted, mechanically, by the now-effective ktlint pin.

## Verification

`./gradlew :domain:test :application:test`. Every safety claim is paired with a negative that was
watched to fail: `docs/plan/CORE-01.md` § 6.1 lists what was disabled and which tests went red, and
names the one branch that is deliberately unobservable.

## Review result

**2026-08-25 · CHANGES REQUIRED (independent review, round 1).** 1 BLOCKING, 8 NON-BLOCKING.

| # | Finding | Fix |
|---|---|---|
| B1 | The engine walked the grantor chain depth-first under a shared 64-resolution budget spent in row order. On a graph where one grantor's subtree cost more than the budget, **breaking that grantor's membership freed budget to reach a second grantor, and the subject gained `ticket.close` by losing a grantor.** A break yielded more, not less — while the KDoc claimed the opposite. The reviewer verified it with a probe over a schema-legal graph rather than reasoning about it. | The engine was **rewritten**, not patched: read the grantor closure breadth-first (each actor once, 16-hop bound, no budget), then take the **least fixed point** of a monotone step function starting from the empty set. The budget is gone, monotonicity is a theorem rather than a claim, and a regression test built on the reviewer's own graph is red against the previous design and green against this one. |

NON-BLOCKING N1–N8 were all fixed in the same session: the two reviewer distillates that still
described the linter's old coverage; `release.yml` missing the self-test — and, pre-existing,
`lint_line_endings.py`; the untestable cycle guard, which the rewrite removed entirely; a
`Path(tmp).resolve()` that would have crashed the self-test on macOS; the undocumented `usecase/`
convention; two test fixtures that built states `capability_grant`'s unique key forbids; an
overstated reference to invariant C3; and a `draft`/`active` mismatch between the plan and its index.

**2026-08-25 · APPROVED (independent review, round 2).** 0 BLOCKING, 10 NON-BLOCKING. Round 2 read
the rewritten engine cold and re-derived rather than accepted: it confirmed `step` is monotone on
every branch, that the round bound `closure.size × Capability.entries.size + 1` is exactly sufficient
and never returns a non-fixed-point, and that B1's shape is structurally gone. It also probed the
build through a Gradle init script and independently confirmed the dead-configuration finding — all
six modules now report `explicitApi=Strict`, `ktlint=1.5.0` and `detekt.yml`.

**Two of round 2's findings were defects in round 1's own fixes, which is the argument for reviewing
again after fixing.** The read-once test used a symmetric diamond, where a plain set already
deduplicates and the guard it named was unguarded; and the row-order test reversed rows that fold
into sets, so it could not fail. Both are rebuilt — the diamond now has sides of different lengths,
and the order assertion moved onto the only state in which two rows touch one capability — and both
are now demonstrably red under mutation. The other eight: the monotonicity claim was stated too
widely (a *denial* row is a restriction rather than authority, so deleting one widens the result —
and revoking a verb in this model *adds* a row rather than removing one); the plan's pseudocode was
more permissive than the code; `docs/DOMAIN_MODEL.md` § 4's role table contradicted the
implementation; the third adapter of the `ActorContext` rule had not received the clause its two
siblings did; the use-case regex silently skipped type parameters and extension receivers; `Grantee`
used one `null` for two meanings; the write-side obligation the root rule implies was recorded
nowhere; and this section did not exist. All fixed.

### What the reviewers could not verify, stated rather than implied

- **The ordering criterion.** The catalogue was raised, explicit API mode turned on, the existing
  sources fixed and the backend gate run green *before the first `:domain` file was written* — and
  that is the implementer's account, not evidence. The whole package is one uncommitted change set,
  so no artefact in the tree records the sequence, and both reviewers said so plainly. The
  criterion's own mechanism is this record; it is worth exactly what the record is worth.
- **`make check` as one command.** This machine has no JDK, so the backend lane runs in an
  `eclipse-temurin:21-jdk` container and the Makefile target cannot be invoked whole. What was run:
  `./gradlew ktlintCheck detekt checkModuleBoundaries test build` in the container — green, **64
  tests, 0 failures, 0 skipped**, counted from the JUnit XML rather than the console; every
  `check-repo` script plus the TODO/FIXME grep and `lint_invariants.py --self-test` over 15
  fixtures; `check-db`'s `lint_sql.py`; and the frontend's `yarn install --frozen-lockfile`,
  `api:generate` with a clean `git diff`, `lint`, `typecheck`, `test:coverage` and `build`. The
  frontend ran before the round-2 fixes, which touch no frontend file.
- **`make verify-db`.** Not run — it needs a live Postgres, and this package touches no migration.
- **gitleaks.** CI-only by design.
