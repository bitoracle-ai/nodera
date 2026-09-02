---
id: CORE-02
title: Audit recorder — one event per mutation, in the mutation's transaction
priority: P1
status: closed
effort: ~2 d
depends_on: [CORE-01, DB-01]
created: 2026-08-20
updated: 2026-09-02
closed: 2026-09-02
---

# CORE-02 · Audit recorder — one event per mutation, in the mutation's transaction

**Priority:** P1
**Effort:** ~2 d

## Motivation / context

The audit trail is what makes agents accountable rather than merely permitted, and it is the
capability that cannot be added later — a trail with a gap where a feature predates it is not a
trail. It has to be in place before the first mutating use case ships.

## Current state (honest)

**Closed 2026-09-02.** When this ticket was written, `audit_event` existed in `V4` with its
append-only triggers and privilege split, no writer existed, and nothing forced a use case to write
one.

It now holds: `AuditRecorder` in `:application` behind a one-method `AuditEventSink` port,
`AuditEventRepository` in `:persistence` writing the row on the transaction already in progress and
refusing when there is none, and `UnitOfWork` / `JdbcUnitOfWork` as the transaction seam a use case
opens. Completeness is enforced by a JDBC-level listener in `:persistence`'s test harness that
refuses to commit a transaction whose mutations carry no audit event, and by a
`scripts/lint_invariants.py` rule that stops a test opening an unwatched transaction.

**What is still absent, and deliberately:** no mutating use case exists yet, so criterion 1 holds by
mechanism rather than by enumeration — CORE-03 writes the first one against this seam. Nothing is
wired in `:app`: `JdbcUnitOfWork` takes a `DataSource` and nobody supplies one yet. And nothing here
establishes `nodera.project_ids` from the authenticated context, which is the surface packages' seam,
stated in `JdbcUnitOfWork`'s own documentation.

## Approach

1. `AuditRecorder` in `:application`, taking the `ActorContext` and the before/after diff.
2. Enlist in the **caller's** transaction. A recorder that opens its own produces exactly the
   failure this package exists to prevent: a mutation that commits while its audit row rolls back.
3. Record denials and failures with `outcome` — a trail of successes cannot answer what an agent
   tried to do.
4. Make omission detectable by the harness rather than by review attention.

## To decide before starting

- How to enforce "every mutation writes an event" mechanically. Recommendation: a test-time
  transaction listener that fails when a mutation committed without an audit insert. A compile-time
  approach needs annotation processing, which is a large dependency for the benefit.

## Decision — the listener, at the JDBC layer

**Taken as recommended, and placed one layer lower than "test-time" implies.** The listener wraps the
JDBC connection rather than the recorder, so what it sees is **the statements that actually
executed**: a use case that bypasses `AuditRecorder`, writes its own SQL, or simply forgets is caught
identically, because none of those change what reaches the database. The rule it evaluates at
`commit()` is that a transaction mutating any table other than `audit_event` carries exactly one
`insert into audit_event`. A transaction with an audit row and no mutation is accepted — that is what
a denial is.

The scan is unanchored and counts every match after comments, string literals and locking clauses
are stripped, so a mutation inside a common table expression or behind a leading comment is found
while `select … for update` stays the read it is — phase-4 review
caught the first version, which was anchored at `^`, missing exactly the sequence-bump shape CORE-03
will write. `Statement`, `PreparedStatement` and `CallableStatement` are intercepted, and both
`commit()` and `setAutoCommit(true)` are checked. Two things it cannot see, both stated rather than
discovered later: a mutation on a connection it never handed out, which is what the linter rule below
is for, and one issued on the raw connection a `ResultSet` still exposes, which the harness's own
documentation names.

Annotation processing is rejected for the reason the ticket gives, plus one it does not: it would
only ever see annotations somebody remembered to write, which is the review attention this criterion
exists to replace.

It stays out of the serving path deliberately. Intercepting every statement in production is a
per-statement cost and a second failure mode, and the database already holds the append-only half
there through privileges and triggers (`V4`, proved by DB-01).

**Opting out is also mechanical.** `scripts/lint_invariants.py` now refuses a `JdbcUnitOfWork`
constructed outside the composition root and the harness file itself, so a future use case cannot be
tested through an unwatched transaction — an opt-in completeness check is review attention wearing a
harness. The rule ships with fixtures in the same `--self-test` that proves the sweep still fires.

Reasoning in full, including what was rejected: [`../../docs/plan/CORE-02.md`](../../docs/plan/CORE-02.md).

## Acceptance criteria

- [x] Every mutating use case writes exactly one `audit_event` row, in the same transaction —
      **by mechanism, not by enumeration**, because no mutating use case exists yet. The harness
      refuses a commit whose mutations carry any count but one, proved by
      `AuditCompletenessTest` on a representative mutation and by its doubly-audited case.
- [x] A test proves the audit row rolls back **with** the mutation when the transaction fails —
      `AuditCompletenessTest`, "a failing transaction leaves neither the mutation nor its audit
      event behind": a real transaction is failed, and both the row and the mutation are gone.
- [x] Denied operations produce a row with `outcome = 'denied'`, proved by a test —
      `AuditEventRepositoryTest`, read back out of the column as the application role.
- [x] `on_behalf_of_actor_id` is populated wherever the context carries a delegation — positive
      and paired negative; the column can only come from the `ActorContext`, so no caller can
      omit it.
- [x] The enforcement mechanism fails when a deliberately un-audited mutation is added — seven
      committed refusals, one per door the harness watches, kept rather than run once by hand.
      Confirmed twice by experiment: neutering `verify()` turns the refusals red, and a
      throwaway un-audited `insert into label`, a shape no committed test uses, was refused on
      sight before being removed.
- [x] `make check` green — `make PY=py check`, exit 0, all four lanes; 150 backend tests, 0
      failures.
- [x] Independent review: 0 BLOCKING findings — five rounds, see below.

## Affected files

- `backend/application/src/main/kotlin/ai/nodera/application/audit/AuditRecorder.kt`.
- `backend/persistence/src/main/kotlin/.../AuditEventRepository.kt`.
- `backend/application/src/test/kotlin/.../AuditCompletenessTest.kt`.

**Two deviations, both deliberate.**

`AuditCompletenessTest` lives in `:persistence`, not `:application`. The criteria it carries — one
row per mutation, and the audit row rolling back with it — only exist against a real database, and
`:application` may not depend on `:persistence`. `:application` keeps `AuditRecorderTest`, which is
what can be proved without one. The acceptance criteria are unchanged.

**A CORE-01 domain type changed shape.** `RequestId` carried a non-blank `String`; the column is
`uuid not null`, so it now carries a `Uuid`. A string that is not a UUID type-checked, passed the
domain, and would have failed on the last statement of the mutation's own transaction — the failure
this package exists to prevent, one layer earlier. CORE-01's "a request id must not be blank" test
goes with it: `RequestId("  ")` no longer compiles, so the rule became structural rather than lost,
and `AuditEventTest` replaces it with the guard that is now runtime-checkable, `AuditAction`'s
grammar. `docs/plan/CORE-01.md` carries the supersession note, and `docs/API_CONTRACT.md`'s
`X-Request-Id` row now states the shape the column forces.

## Verification

`./gradlew :application:test :persistence:test`. The rollback test is the important one: assert the
audit table is empty after a deliberately failed mutation.

## Review result

**2026-09-02 · Five rounds, each in a fresh sub-agent context. Two returned CHANGES REQUIRED, and
both were right to.**

| Round | Verdict | Findings |
|---|---|---|
| 1 | CHANGES REQUIRED | 1 BLOCKING, 9 NON-BLOCKING |
| 2 | APPROVED | 0 BLOCKING, 5 NON-BLOCKING |
| 3 | APPROVED | 0 BLOCKING, 9 NON-BLOCKING |
| 4 | CHANGES REQUIRED | 1 BLOCKING, 5 NON-BLOCKING |
| 5 | APPROVED | 0 BLOCKING, 2 NON-BLOCKING |

All 31 findings are fixed. Every round after the first reviewed the previous round's fixes, and
**both blocking findings were defects in the completeness check itself** — the one mechanism whose
failure mode is silence.

**Round 1, B1 — the harness could not see the mutation CORE-03 is about to write.** The statement
classifier was anchored at `^` and matched three literal verbs, so a mutation inside a common table
expression, behind a leading comment, or written as `merge into` was invisible, and every one of
those is a shape a real use case takes — `select … for update` on `ticket_sequence` followed by an
insert is exactly the key allocation CORE-03 owns. It also misread `insert into public.audit_event`
as a mutation of a table called `public`, so a correctly audited transaction would have been refused.
The scan is now unanchored, counts every match, and strips comments, string literals and locking
clauses in one left-to-right pass first. Three shapes gained committed refusals plus their audited
counterparts.

**Round 4, B1 — an interception nothing had ever executed.** The `CallableStatement` door was
claimed in the ticket, in the plan and in the code, and no test went through it; deleting the branch
left the whole suite green. pgjdbc accepts plain SQL in `prepareCall`, so the door is real, and it
now has its own refusal case. The same round found `connection.metaData.connection` handing back the
raw connection — a route out of the harness on a connection it *had* handed out.

**The pattern across the non-blocking findings is worth carrying: a guard is only as honest as its
prose.** Several rounds found the code correct and a sentence describing it false — a comment saying
`after` is empty for a denial while the code merged into it, a linter message naming an allowance
the linter did not apply, a plan claiming an exhaustive list of what the harness cannot see. Each was
corrected where it stood rather than at the next reader's expense.

**Two decisions were narrowed by review rather than by the author.** `recordDenied` now *replaces*
`after` instead of extending it: `before`/`after` are the changed fields, a denial changes none, and
a row asserting the state the caller was aiming for would have the trail describe an entity state
that never existed. And `docs/API_CONTRACT.md` states only the shape `X-Request-Id` must have; what
a surface does with a non-UUID header is API-01's contract decision, recorded as an open question in
the plan rather than settled here in passing.
