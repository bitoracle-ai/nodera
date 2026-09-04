---
id: CORE-03
title: Ticket lifecycle, key allocation and the closure gate
priority: P2
status: closed
effort: ~3 d
depends_on: [CORE-01, CORE-02]
created: 2026-08-20
updated: 2026-09-02
closed: 2026-09-02
---

# CORE-03 · Ticket lifecycle, key allocation and the closure gate

**Priority:** P2
**Effort:** ~3 d

## Motivation / context

The closure gate is the mechanism that makes a ticket a specification rather than a status field.
It has to live in the domain service, because both surfaces need the same refusal and the same
itemised explanation of what is missing.

## Current state (honest)

When this ticket was written the schema carried `ticket`, `acceptance_criterion`,
`ticket_dependency` and `ticket_sequence`, and no domain logic existed above them.

It now holds: `ai.nodera.domain.ticket` with the status machine as a pure transition function, the
closure gate returning `ClosureVerdict`, and the working order; three use cases in
`:application` — `CreateTicket`, `TransitionTicket`, `NextTicket`, each taking `ActorContext` first,
opening one transaction and writing exactly one audit event; and four `:persistence` adapters, of
which `JdbcTicketSequence` is the locking allocator. These are the first mutating use cases in the
repository, so CORE-02's completeness harness now has real transactions to watch rather than
representative ones.

**What is still absent, and deliberately.** Nothing is wired in `:app` — no use case is reachable
from a running process, because no surface exists to reach it (API-01, MCP-01). Nothing establishes
`nodera.project_ids` either; that seam is still SEC-01's, and the consequence is stated below rather
than worked around. Acceptance criteria, reviews and findings are **read** by the gate and written
by nobody: `criterion_set`, `review_submit` and `finding_resolve` are CORE-04, and the tests seed
those rows directly.

**One thing the specification does not carry: `open → closed` has no path.** A ticket recognised as
a duplicate the moment it is filed has to be walked through `in_progress` and `in_review` to be
closed. The state machine in `docs/DOMAIN_MODEL.md` § 5.1 does not draw the edge, so this package
did not add one; the proposal is in [`../../docs/plan/CORE-03.md`](../../docs/plan/CORE-03.md) § 8
and is a product decision.

## Approach

1. The status state machine in `:domain` as a pure transition function.
2. Key allocation locking the `ticket_sequence` row with `select ... for update`, so two concurrent
   creates in one project cannot produce the same key.
3. `ClosureGate` returning a structured `UnmetClosureRequirements` rather than a boolean — the MCP
   error shape in `docs/MCP.md` section 4 is the consumer, and a boolean cannot feed it.
4. Dependency-readiness logic for the working-order query that `ticket_next` will use.

## Decisions taken while implementing

Full reasoning in [`../../docs/plan/CORE-03.md`](../../docs/plan/CORE-03.md). Four are worth having
in the ticket, because each changed what shipped.

**The lock is the first statement, and that ordering came out of an experiment.** The allocator
originally ran `insert … on conflict do nothing` unconditionally and then locked. The race test
passed with `for update` deleted — the contender was blocking on the sequence table's unique index
before it ever reached the lock, so the test proved the index. Locking first and creating the row
only when there is none makes an established prefix contend on the lock alone, and removing
`for update` now turns four tests red.

**The state machine answers `PermittedIfClosureGatePasses`, not a flag.** Running the gate is then a
branch the compiler insists on rather than something review has to notice was skipped.

**An empty answer and an unknown answer are never the same value.** `ClosureFactsReader` returns
`ClosureFacts?` — `null` is "the ticket was not visible" — because every input to the gate is a
project-scoped read and row-level security answers an unscoped one with zero rows, which is exactly
what "nothing outstanding" looks like. `UnmetClosureRequirements` additionally refuses to be
constructed with nothing unmet, so a refusal that names nothing cannot exist.

**Permission checks ship with the first mutating use case** rather than waiting for a surface: one
engine, checked in `:application` (invariant #2). `PermissionDirectory` still has no adapter, so the
integration tests supply a one-membership fake.

**The transition write is a compare-and-set**, added in phase 5. `byKey` takes no lock, so two
callers could both read `in_review`, both be permitted, and the later write land a status the
machine never allows from the status the row actually had — with the audit row describing a `before`
the ticket did not have. `applyTransition` therefore carries the expected status into its `where`
clause and returns `null` when the row has moved, which the use case reports as
`TransitionRefusal.ConcurrentlyChanged`.

## Acceptance criteria

- [x] A transition to closed/done is refused while any acceptance criterion is unmet, any blocking
      finding is unresolved, or no review exists — one test per condition. `ClosureGateTest` in
      `:domain` and `TicketClosureTest` in `:persistence` carry one case each, against a real
      database in the second. A resolved blocking finding and an unresolved non-blocking one are
      proved **not** to refuse, so severity and resolution are both read.
- [x] The refusal names every missing item, not just the first. `UnmetClosureRequirements` carries
      all three lists and the gate short-circuits nowhere; proved by "a refusal names every missing
      item" in both modules, and watched red with the gate reporting only the first unmet
      criterion.
- [x] Concurrent creation of two tickets in one project produces two distinct keys; proved by a
      test that actually runs them concurrently. Four cases — 2 and 8 racers, against a new prefix
      and against an existing sequence row — plus a deterministic two-transaction race whose
      contender is released only when Postgres itself reports a backend waiting on the row.
      Removing `for update` turns three of the four and the race red; the fourth (2 racers, new
      prefix) stays green because the unique index serialises two creates on its own, and that is
      stated rather than claimed as proof.
- [x] A ticket key is never reused after closure, including `wont_do` and `duplicate`. Nothing
      derives a number from the tickets — `ticket_sequence.next_number` is the only source and is
      never decremented. `TicketKeyAllocationTest` closes one ticket as `wont_do` and one as
      `duplicate` and asserts the next key is 4.
- [x] `make check` green — `make PY=py check`, exit 0, all four lanes; 252 backend tests, 0
      failures, 0 skipped (150 before this package).
- [x] Independent review: 0 BLOCKING findings — three rounds, all APPROVED, recorded below.

## Affected files

- `backend/domain/src/main/kotlin/ai/nodera/domain/ticket/` — `TicketKey.kt`, `TicketStatus.kt`,
  `ClosureGate.kt`, `Ticket.kt`, `WorkingOrder.kt`, and four specs beside them.
- `backend/application/src/main/kotlin/ai/nodera/application/ticket/` — the five ports, the three
  result types, and `usecase/CreateTicket.kt`, `usecase/TransitionTicket.kt`, `usecase/NextTicket.kt`.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/ticket/` — `JdbcTicketSequence.kt`,
  `JdbcTicketRepository.kt`, `JdbcClosureFacts.kt`, `JdbcTicketCandidates.kt`.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/Binding.kt` — the parameter binder and
  the enum-label extension, lifted out of `AuditEventRepository.kt` rather than copied.
- `backend/persistence/src/test/kotlin/ai/nodera/persistence/ticket/` — fixtures and three specs.
- `backend/application/src/test/kotlin/ai/nodera/application/ticket/TransitionTicketTest.kt` — the
  branches a database cannot be made to produce.
- `docs/DOMAIN_MODEL.md` § 5.1 — the transition table beside the diagram.
- `backend/application/src/main/kotlin/.../permission/PermissionService.kt` and
  `docs/plan/CORE-01.md` — two sentences that named CORE-03 as the package which would refuse a
  self-granted membership. It does not write memberships, so closing it would have made them point
  at code that was never coming; re-pointed rather than left.

**One deviation, and it is CORE-02's.** The integration tests live in `:persistence` rather than in
`:application`, because a use case's transaction is only watched for audit completeness there and
`:application` may not depend on `:persistence`. The acceptance criteria are unchanged.

## Verification

`./gradlew :domain:test :application:test :persistence:test`. The concurrency test uses two real
connections against Testcontainers; a single-threaded simulation would not exercise the row lock.

**Guards watched going red, one at a time, each restored afterwards:**

| Guard disabled | Went red |
|---|---|
| `for update` removed from the allocator | 3 of 4 concurrency cases and the deterministic race |
| the gate reports only the first unmet criterion | "a refusal names every missing item", both modules |
| `UnmetClosureRequirements` empty-refusal check removed | "a refusal that names nothing cannot be constructed" |
| the visibility check removed from `JdbcClosureFacts` | "closure facts are absent, not empty" |
| the allocator answers an empty read with 1 | **nothing** — see below |
| the `and status = ?` clause removed from the transition write | both compare-and-set cases |
| the allocator's insert put back in front of the lock, lock removed | the race, and 3 of 4 concurrency cases |
| `require(reviewCount >= 0)` removed from `ClosureFacts` | "a negative review count cannot be described" |
| the entity id dropped from the three refusal paths that have read the ticket | one case per path, in two modules |

**The insert-before-lock row is the one phase 4 asked for, and it is the sharpest of them.** Before review, the
race waited for *any* backend blocked on a statement mentioning `ticket_sequence`, and the reviewer
showed that an allocator which inserts before it locks blocks on the insert instead — so the holder
was released before the contender had read, and the race signed off on an allocator with no lock at
all. `awaitContention` now names the statement it is waiting for, and the reproduction above is red.

**The empty-read row is the finding that changed the prose rather than the code.** The allocator's
`?: error(…)` was described as the guard against a restarted sequence; replacing it with `?: 1`
leaves all eight allocation tests green, so it has never executed. What actually refuses an unscoped
allocation is `V4`'s policy: `ticket_sequence` is `for all … using` with no separate `with check`,
so one predicate governs the read and the write, and the insert fails with
`new row violates row-level security policy`. The code keeps the refusal — 1 is the answer that
silently restarts a sequence, and a policy later widened on the write side alone would make it
reachable — but it is no longer claimed to be doing the work, and the test now asserts the refusal
that is.

## Review result

**2026-09-02 · Two rounds, each in a fresh sub-agent context.**

| Round | Verdict | Findings |
|---|---|---|
| 1 | APPROVED | 0 BLOCKING, 7 NON-BLOCKING |
| 2 | APPROVED | 0 BLOCKING, 5 NON-BLOCKING |
| 3 | APPROVED | 0 BLOCKING, 6 NON-BLOCKING |

All eighteen findings are fixed rather than deferred. Every round after the first reviewed the
previous round's fixes, and rounds 2 and 3 each reproduced every guard experiment in *Verification*
rather than taking them on trust — seven rows at round 2, eight at round 3.

**N1 was a defect, not a nit, and it is the one worth carrying: two concurrent transitions could
lose an update.** `byKey` reads without a lock and the write was unconditional, so two callers that
both read `in_review` were both permitted and the later write won — landing, in the reviewer's own
example, a `closed/done` row that was `open` when the statement ran, which is precisely the
`open → closed` edge the machine refuses. Nothing can reach it yet, because no surface is wired, and
that is the only reason it was not blocking. The write is now a compare-and-set with two tests, one
deterministic and one concurrent, both red without the clause.

**N3 was a defect in this package's own proof.** Detailed in *Verification* above: the race's
release condition could be satisfied by the wrong wait. This is the second package running in which
the sharpest finding was in the guard rather than in the code it guards.

**N2 established that the `facts == null` branch cannot execute against the JDBC adapter** — `byKey`
has already read the ticket in the same transaction — so the plan's claim that two structural things
stop the gate failing open was over-credited by one. The branch is the port's contract rather than a
live guard, it is now proved where a database cannot produce it (`TransitionTicketTest`), and the
plan says so.

**N4, N5 and N7 were all prose or tests claiming more than they did:** a stand-in described as
"nothing else changed" that had also lost a branch, a four-way parameterisation over a dimension the
type does not carry, and a comment claiming the domain restates the column's rule when `citext`
makes the column's own regex case-insensitive and the domain strictly stricter. **N6** was CORE-01's
prose assigning to this package a refusal it does not ship.

**Round 2 found nothing structural, and its most useful finding was about the trail rather than the
code.** Three refusal paths wrote their audit row with no `entity_id` although the ticket had
already been read, and `V4` indexes the trail on `(entity_type, entity_id)` precisely so a ticket's
history can be asked for by id — so a reader reconstructing a lost race would have seen the
transitions and the gate refusals but neither the unknown-edge nor the concurrently-changed ones.
Fixed, and pinned by an assertion rather than left to the next reader.

Its other four were the same class this package keeps producing: a guard nobody had watched
(`require(reviewCount >= 0)`, now with its own paired negative), an enum the gate parses that the
label-parity check had missed (`finding_severity`), an `insert … returning` whose empty result would
have surfaced as a cursor error rather than as a sentence — round 3 then established that it cannot
have one, since `INSERT_TICKET` carries no `on conflict` and `ticket` has no before-insert trigger,
so the check names a case that cannot arise rather than one that had been mishandled — and a plan
that had recorded round 1's prose fix but not round 1's compare-and-set. The last is the one worth naming: **the plan is the
document CORE-04, API-01 and MCP-01 will read**, and a design record that omits how a status is
actually written is worse than no record.

**Round 3 found that round 2's own fix was half-tested, which is the pattern this package has
produced in every round.** The audit entity id had been added to all three refusal paths that read
the ticket, and only one of the three was pinned by an assertion: deleting it from the other two
left the whole suite green. Both are now pinned, and both were watched red without it. Its other
five were record accuracy — a positional reference ("the last row") that a later edit had moved out
from under, a count that had gone from seven to eight, a plan test-plan row that had not followed
the code, a plan row describing a property no test carries, and the plan's status header.

**The round-3 fixes were not themselves reviewed by a fourth round**, and that is stated rather than
implied. They are two test assertions plus five corrections to prose; the assertions were verified
the way this repository verifies a guard, by watching them fail with the code they pin removed, and
nothing in production code changed after round 3.

**What three rounds cost and bought.** Eighteen findings, none blocking, and the three that mattered
were all about whether a guard could fail rather than about whether the code was right: a race that
would have accepted an unlocked allocator, an allocator refusal that had never executed, and an
audit id that two of three paths could drop unnoticed. The code the guards guard drew one real
defect — the lost update on concurrent transitions — and it was found by reading the diff, not by a
test.