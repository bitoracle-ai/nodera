---
summary: The twelve hard invariants of Nodera — actor kind never gates permission, one permission engine, append-only audit, attenuation at use time, server-side scoping, forward-only migrations, and the rest. A violation is a BLOCKING finding, not a discussion.
read_when:
  - Before EVERY change, without exception — this is the shortest file in the repository for a reason.
  - As the first checklist of every phase-4 review.
  - When a change appears to require weakening one of them (it does not — ask the maintainers instead).
---

# Critical invariants — Nodera

Twelve rules. They are **hard**: a violation is a BLOCKING review finding, not a discussion. They are
short because they are meant to be re-read before every change, and each one exists because it has a
failure mode a passing test suite would not show you.

Where an invariant is enforced by the database or the build, that is said explicitly — an invariant
enforced only by attention is an invariant waiting to be broken.

The twelve here are numbered, never lettered; the lettered invariants cited elsewhere (F1/F2, R1/R2,
AU1, CM1/CM2, CR1/CR2, …) are the per-area catalogues in
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) and
[`../docs/DOMAIN_MODEL.md`](../docs/DOMAIN_MODEL.md).

---

## 1. Actor kind never gates permission

`actor.kind` is for **display and audit**. No code path anywhere may branch on it to decide what is
allowed.

```kotlin
// FORBIDDEN — this single line ends the product's premise
if (actor.kind == ActorKind.AGENT) return Denied("agents cannot close tickets")

// Correct — the question is always about capability, never about kind
permissions.require(ctx, projectId, Capability.TICKET_CLOSE)
```

**Why it is invariant #1:** every tracker that claims agent support has this branch somewhere, and it
is why none of them actually deliver it. The moment the branch exists, agents are second-class again
and every other invariant here is decoration. If an agent genuinely must not close tickets *in a given
project*, that is a capability grant, and it is expressed as one.

Enforced by: `scripts/lint_invariants.py` (a **regex line scan**, not an AST sweep) plus review. It
covers three shapes outside display and audit modules — the `kind` comparison above, a
`when (actor.kind) { AGENT -> … }` branch, and `is AgentActor` / `is HumanActor`, which is the same
branch written through the sealed `Actor` hierarchy. `--self-test` proves the sweep still fires and
runs in the same gate; a regex that stops matching is otherwise a silently permissive gate.

**One shape remains an explicit reviewer duty**, because a line scan cannot follow it: a comparison
through an aliased kind (`val k = actor.kind; if (k == …)`).

## 2. One permission engine

REST and MCP call the same `PermissionService` object. Not the same logic re-implemented — the same
object.

- No MCP-specific shortcut, no "trusted internal caller", no bypass flag for tests that then leaks.
- Every capability that exists is enforced identically on both surfaces, proved by a parity test that
  drives the same denial through REST and through MCP.

**Why:** two engines drift, and the one that drifts is always the one with fewer readers. The MCP
surface is used by agents, so its permission bugs are the least likely to be noticed by a human and
the most likely to be exercised at scale.

## 3. The audit trail is append-only, and complete

- The application role holds `INSERT` and `SELECT` on `audit_event`, nothing else. Plus table triggers
  that refuse update, delete and truncate.
- **Every mutation writes exactly one event, in the same transaction as the mutation.** Not
  best-effort, not asynchronous, not a listener that might be unregistered.
- Denied and failed attempts are recorded too, with `outcome`. An audit trail of successes cannot
  answer what an agent tried to do.

**Why:** a mutation that commits without its audit row is a defect of the same severity as losing the
mutation. Both leave the system describing a past that did not happen.

## 4. Attenuation is re-checked at use time

An actor's effective capabilities are always a subset of its grantor's effective capabilities **at the
moment of use**, not merely at the moment of granting.

**Why the timing is the whole invariant:** if Anna leaves the team and her grants are revoked, the
three agents she configured must lose their access in the same instant. Checking only at grant time
leaves them running with permissions their grantor no longer has — which is precisely the
"agent borrowed a human's identity" failure this product exists to fix, reintroduced one layer down.

## 5. Scoping is server-side, and RLS is the floor

`project_id` comes from the authenticated `ActorContext`. It is never read from a request parameter,
a header, a path segment the client controls, or a token claim the client can mint.

Row-level security is enabled on every project-scoped table, so a forgotten `WHERE project_id = …`
returns **zero rows**, not another project's rows. The application role never holds `BYPASSRLS`.

**Why RLS and not careful code:** careful code is one refactor away from being careless code, and a
cross-project leak is unrecoverable — you cannot un-show someone another team's backlog.

## 6. Credentials fail closed and are never readable

- A token is stored only as an Argon2id hash. The plaintext is returned exactly once, at creation.
- No secret is logged, echoed in an error message, included in an API response, or passed as a
  command-line argument (where it lands in the process table).
- A missing required secret makes the process **refuse to start**, never fall back to a default.

**Why a startup refusal and not a warning:** a warning in a log nobody reads is how a system ends up
running in production with a development signing key.

## 7. Migrations are forward-only

Expand/contract. An applied migration is **never** edited — a mistake is corrected by a new migration.
Identifiers are unquoted lowercase `snake_case`.

**Why the casing rule is here and not in a style guide:** a quoted mixed-case identifier works
perfectly until something addresses it unquoted, and then fails in a way that reads like a missing
table. It is a permanent, invisible source of "works on my machine".

## 8. The closure gate is not clickable

A ticket transitions to `closed`/`done` only when every acceptance criterion is met, every `blocking`
finding is resolved, and at least one review exists. The gate lives in the domain service — not in the
UI, not in the API layer, not in a validation annotation.

A refusal returns **what is missing**, itemised. An agent that receives `409 Conflict` guesses; an
agent that receives the list finishes the work.

## 9. The reviewer is not the author

Enforced in the domain service and in this repository's own process. An agent may review a human's
work and a human may review an agent's; what is refused is reviewing one's own.

Review history is **append-only across rounds**, including a verdict that contradicts an earlier one.
Do not collapse to a "current" verdict — the contradiction is the most informative thing in the record.

## 10. Ticket keys are permanent

A key is never changed and never reused, including after a ticket is closed as `duplicate` or
`wont_do`. There is no delete path.

**Why:** keys are quoted in commits, chat, other tickets and external systems that Nodera cannot see.
A reused key silently retargets every one of those references.

## 11. Contract-first, generated types

The REST contract is the OpenAPI document. Frontend types and Zod schemas are **generated** from it;
they are never hand-written a second time. Components never call `fetch` directly — all I/O goes
through `src/api/`.

**Why:** a hand-maintained client type is a copy that agrees with the server exactly until the first
day nobody checks.

## 12. Entry files and this hub change only inside a work package

`CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md`, `.github/instructions/`, `docs/INDEX.md`
and this file are never edited in passing, as a side effect of unrelated work.

**Why:** they are what every contributor and every tool loads first. A rule that appears in them
without a ticket has no recorded reasoning, and the next reader cannot tell an agreed decision from
someone's passing preference.

---

## When one of these seems to be in the way

It is not. Every one of them has already cost someone something.

If a change genuinely cannot be made without weakening an invariant, that is a **structural decision**:
it goes to the maintainers as an ADR proposal, not into the diff. `docs/PROJECT_MANAGEMENT.md` § 8
names it explicitly as one of the few criteria that justify their own ticket.
