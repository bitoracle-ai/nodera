---
id: API-02
title: Project-scoped REST surface and the scope bootstrap it needs
priority: P2
status: open
effort: ~3 d
depends_on: [API-01, CORE-03, CORE-04]
note: Carries API-01's two project-scoped acceptance criteria and the RLS bootstrap decision behind them.
created: 2026-09-10
updated: 2026-09-10
---

# API-02 · Project-scoped REST surface and the scope bootstrap it needs

**Priority:** P2
**Effort:** ~3 d
**Skills:** `critical-invariants.md` · `secure-coding.md` · `database-design.md` · `backend-kotlin.md`

## Motivation / context

[`../../docs/API_CONTRACT.md`](../../docs/API_CONTRACT.md) specifies every `/projects/…` route and
nothing serves one. API-01 built the surface that resolves no project — the caller's own actor and
its own credentials — and stopped where the project boundary begins, because reaching across it
needs a decision about the schema rather than a route.

**Why this is a ticket of its own** ([`../../docs/PROJECT_MANAGEMENT.md`](../../docs/PROJECT_MANAGEMENT.md)
§ 8): it hits two of the criteria. It carries a **structural decision** — how the project scope is
established — which needs an owner's answer rather than a contributor's edit; and it is a **foreign
subtree** for an `API-` package, since the work is mostly a migration and two `:persistence`
adapters.

## Current state (honest)

Three things are missing, and the third is the one that decides the package's shape.

1. **`PermissionDirectory` has no implementation.** CORE-01 declared the port, DB-01 wrote no
   production Kotlin, and CORE-02 and SEC-01 did not need it. Nothing calls
   `PermissionService.require` outside tests.
2. **`JdbcUnitOfWork` establishes no `nodera.project_ids`.** Its own comment says so and names this
   surface as where it belongs. Until it does, every project-scoped read returns zero rows and every
   audit row carrying a `project_id` is refused by `V4`'s policy.
3. **Establishing it is circular.** The setting has to come from the acting actor's memberships
   (invariant #5 forbids taking it from the `{key}` in the path), and `project_membership_visible`
   makes those rows invisible until the scope is already set. Nothing in the schema breaks that
   circle today.

The use cases the routes would host **do** exist: `CreateTicket`, `NextTicket`, `TransitionTicket`,
and the seven collaboration ones from CORE-04. Nothing here has to be written in `:application`.

## ⚠️ To decide before starting

**How the project scope is bootstrapped.** Two answers, and the choice is the maintainers'.

- **A second row-level-security policy** on `project_membership`, admitting rows whose `actor_id`
  matches a `nodera.actor_id` session setting written from the authenticated context. The read stays
  under RLS; the new setting is established exactly where `nodera.project_ids` is; nothing gains a
  standing exemption.
- **A `security definer` function** returning an actor's project ids. Fewer moving parts, and a
  permanent privilege-escalation surface that has to be argued about for the life of the schema.

*Recommendation:* the policy, for the reason above —
[`../../docs/plan/API-01.md`](../../docs/plan/API-01.md) § 8 argues it. Either way it is a new
migration and an ADR, and neither is an API package's to decide alone.

**A second question falls out of the first:** `nodera_app` cannot insert a `project`, because
`project_visible` has no `with check` of its own and the row's own id must already be in the
context. DB-01 § 8 raised it and left it here. `POST /projects` needs an answer — admit the insert
with a `with check`, or make project creation an operator act outside the API.

## Approach

1. The decision above, as an ADR, before any code.
2. The migration it implies, forward-only, with its paired negatives against a real Postgres.
3. `JdbcPermissionDirectory`, and the scope establishment in the transaction the use case opens.
4. Routes for projects, tickets, criteria, dependencies and actors — translation only, against the
   use cases that already exist.
5. The OpenAPI document and `docs/API_CONTRACT.md` extended in the same package, before the routes.
6. Idempotency: `idempotency_record` has been in the schema since `V4` and nothing reads it. The
   mechanism belongs in `:application`, in the mutation's own transaction.
7. Teach the single-page fallback where the API begins. An unmatched `/api/v1` path currently
   answers `index.html` with `200` rather than a problem document
   ([`../../docs/API_CONTRACT.md`](../../docs/API_CONTRACT.md) § 2b) — wrong, not dangerous, and
   this is the first package that adds a path space worth mistyping.

## Acceptance criteria

- [ ] The scope establishment is a decision recorded as an ADR, and the migration that implements it
      carries a paired negative watched red.
- [ ] `project_id` reaching a use case is derived from the acting actor's memberships and never from
      a path segment, a query parameter or a header — proved by a test that presents a project key
      the caller is not a member of.
- [ ] `not_found` is returned for a project the caller cannot see, never `forbidden` — a test
      confirms an outsider cannot distinguish absent from invisible. *(Inherited from API-01, which
      had no project route to prove it on.)*
- [ ] Every mutating route accepts an idempotency key, and a repeat with the same key and different
      arguments returns `idempotency_conflict`. *(Inherited from API-01, which had no store to honour
      it with.)*
- [ ] Every route maps to a use case and contains no permission decision, no state transition, no
      SQL and no audit write.
- [ ] The committed OpenAPI document still matches what the server serves — `ContractDriftTest` is
      green with the new routes in it.
- [ ] `make check` green.
- [ ] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.

## Affected files

- `docs/adr/00NN-…md` — the scope bootstrap decision.
- `db/migrations/V8__….sql` — whatever that decision implies.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/permission/` — the directory adapter.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/JdbcUnitOfWork.kt` — the seam its own
  comment describes.
- `backend/api-rest/src/main/kotlin/ai/nodera/api/rest/` — the routes.
- `backend/api-rest/src/main/resources/openapi.yaml` and `docs/API_CONTRACT.md` — first, not last.

## Verification

`./gradlew :persistence:test` for the migration and the directory, `./gradlew :api-rest:test` for
the routes and the drift check. The enumeration test asserts identical responses for a non-existent
project and an invisible one, byte for byte. Say which lanes executed rather than being served from
a cache, and name the environment the run created and removed.
