# Plan — CORE-03 · Ticket lifecycle, key allocation and the closure gate

**Status:** `implemented`
**Ticket:** [`../../tickets/closed/CORE-03.md`](../../tickets/closed/CORE-03.md)
**Invariants this implements:** #8 (the closure gate is not clickable) and #10 (ticket keys are
permanent) — [`../../skills/critical-invariants.md`](../../skills/critical-invariants.md)

---

## 1. What phase 1 found

The schema carries everything — `ticket`, `ticket_sequence`, `acceptance_criterion`,
`ticket_dependency`, `review`, `review_finding`, all proved to refuse by DB-01 — and the domain
carries nothing: there is no `ai.nodera.domain.ticket` package, no use case, no adapter.

CORE-02 left four things that bind this package rather than merely informing it.

1. **Every mutation goes through `UnitOfWork.inTransaction { … }` plus `AuditRecorder.record`, and
   the harness enforces it.** A transaction that mutates any table other than `audit_event` and
   carries any audit-event count but one is refused at `commit()`. So this package's integration
   tests live in `:persistence`, which is where `auditedUnitOfWork` is.
2. **The transaction rides in the coroutine context**, not as a port parameter. Adapters read it
   with `currentConnection()` and refuse when there is none.
3. **`RequestId` is a `Uuid`.** Anything building an `ActorContext` supplies one.
4. **`JdbcUnitOfWork` does not establish `nodera.project_ids`.** § 4.3 decides what this package
   does about that, because key allocation is the first thing that reads a project-scoped row.

## 2. The three properties that carry the package

Everything below is arranged around these, because they are the design and not the checklist.

**The refusal names every missing item.** The consumer is the MCP error shape in `docs/MCP.md` § 4,
which lists the unmet criteria, the unresolved blocking findings and the review state in one object.
A boolean cannot feed it, and neither can a refusal that stops at the first failing condition.

**Two concurrent creates in one project produce two distinct keys**, proved by a test that races
them rather than one that reasons about the lock.

**A key is never reused after closure, including `wont_do` and `duplicate`** — the case where
"highest open key + 1" quietly breaks.

## 3. The shape

```
  :domain (pure)                     :application                    :persistence
  ┌──────────────────┐             ┌──────────────────┐            ┌──────────────────────┐
  │ TicketStatus     │◀────────────│ CreateTicket     │───ports───▶│ JdbcTicketSequence   │
  │ transition(…)    │             │ TransitionTicket │            │ JdbcTicketRepository │
  │ ClosureGate      │             │ NextTicket       │            │ JdbcClosureFacts     │
  │ WorkingOrder     │             └──────────────────┘            │ JdbcTicketCandidates │
  └──────────────────┘             UnitOfWork · AuditRecorder      └──────────────────────┘
```

`:domain` decides; `:application` sequences the decision, the permission check and the audit row
inside one transaction; `:persistence` does nothing but read and write rows.

### 3.1 The state machine, and the reading of the diagram it commits to

`docs/DOMAIN_MODEL.md` § 5.1 draws the transitions as ASCII, and ASCII is ambiguous about direction.
The edges this package implements:

| From | To | Resolution |
|---|---|---|
| `open` | `in_progress` | none |
| `in_progress` | `in_review` | none |
| `in_progress` | `open` | none |
| `in_progress` | `blocked` | none |
| `in_review` | `closed` | any; `done` runs the gate |
| `in_review` | `open` | none |
| `in_review` | `blocked` | none |
| `blocked` | `open` | none |
| `blocked` | `closed` | `wont_do` only |

Nine edges, and the table goes into `docs/DOMAIN_MODEL.md` § 5.1 beside the diagram. Leaving the
reading in this plan only would hand CORE-04 and MCP-02 the same ambiguity again, and they would
each resolve it privately.

Two edges are **deliberately absent** because the diagram does not draw them, and adding them is a
product decision rather than an implementation one: `open → closed`, and `in_review → in_progress`.
The first is a real gap and is raised in § 8 with a recommendation; the second has a path through
`open`.

*2026-09-03:* the first is no longer absent — § 8 records the decision and CORE-06 implemented it.
The table above is the machine as this package shipped it; `docs/DOMAIN_MODEL.md` § 5.1 is current.

The resolution rules are the schema's, restated in the domain so it refuses what the database would
refuse rather than one statement later: `closed` carries a resolution, nothing else may, and
`blocked → closed` is `wont_do` only.

**The write is conditional on the status the decision was made against** — added in phase 5, after
review. Reading the ticket takes no lock, so two callers can both read `in_review`, both be
permitted, and the later write win; the row then holds a status the machine never allows from the
status it actually had, and its audit row describes a `before` that had already gone. So
`applyTransition` carries the expected status into its `where` clause, returns `null` when nothing
matched, and the use case reports `TransitionRefusal.ConcurrentlyChanged`. A compare-and-set rather
than `select … for update` on the ticket: the gate's three reads sit between the read and the write,
and holding a row lock across them would serialise every closure in a project behind the slowest
one. The loser is told to retry, which is the honest answer — its decision was made against a state
that no longer exists.

### 3.2 The closure gate returns a structure, and can never return an empty one

```kotlin
public sealed interface ClosureVerdict {
    public data object Satisfied : ClosureVerdict
    public data class Unmet(val requirements: UnmetClosureRequirements) : ClosureVerdict
}
```

There is no boolean anywhere on the path, and `UnmetClosureRequirements` **refuses in its `init` to
be constructed with nothing unmet**. A refusal that names nothing is the boolean failure wearing a
data class, and it is the one shape the MCP consumer cannot render.

All three conditions are evaluated; none short-circuits. `reviews` is a `ReviewRequirement`
(`PRESENT` / `ABSENT`) rather than a boolean, because the MCP shape renders it as a word.

**An empty answer and an unknown answer must not be the same value.** This is the hazard the gate
has, and it is severe: every input to the gate is a project-scoped read, row-level security makes an
unscoped read return **zero rows**, and zero unmet criteria plus zero unresolved findings reads as
*satisfied*. A gate that fails open is worse than no gate.

Two things stop it, and both are structural rather than careful:

- `ClosureFactsReader.facts(…)` returns `ClosureFacts?`, `null` meaning **the ticket was not
  visible**. The use case maps `null` to `TransitionResult.NotFound`; the gate is never handed facts
  that might describe a ticket nobody read. Against the JDBC adapter that branch cannot execute —
  `byKey` has already read the ticket in the same transaction, so nothing can then answer "not
  visible" — and phase 4 established that by collapsing it to satisfied-looking facts and watching
  the suite stay green. It is the port's contract rather than a live guard, and it is proved where
  a database cannot produce it: `TransitionTicketTest` in `:application` drives a reader that
  returns `null` and asserts the ticket is not closed.
- The review condition catches the vacuous case anyway: a ticket with nothing at all has zero
  reviews, so `reviews = ABSENT` and the gate refuses. This is asserted by its own test rather than
  left as a property somebody noticed.

### 3.3 Key allocation

```sql
select next_number from ticket_sequence where project_id = ? and prefix = ? for update;
-- only when that returns nothing, i.e. a prefix used for the first time:
insert into ticket_sequence (project_id, prefix, next_number) values (?, ?, 1)
    on conflict (project_id, prefix) do nothing;
select next_number from ticket_sequence where project_id = ? and prefix = ? for update;
-- then, always:
update ticket_sequence set next_number = next_number + 1 where project_id = ? and prefix = ?;
```

The lock is the ticket's approach point 2, and it is the shape CORE-02's harness was fixed to
recognise, so the allocation is audited like any other mutation.

**The lock comes first, and that ordering was found by experiment rather than chosen.** The first
version ran the `insert … on conflict do nothing` unconditionally, and the race test passed with
`for update` deleted: the contender was blocking on the unique index before it ever reached the
lock, so the test proved the index rather than the lock. With the lock first, an established prefix
contends on the lock alone, and removing `for update` turns the race red — see § 6.

**The race's release condition is part of that, and it is the one thing here no test can guard.**
The holder commits only once Postgres reports a backend blocked on a statement whose text names
`for update`, rather than on any statement touching `ticket_sequence`. Matching on the table would
leave the race green against an allocator with no lock at all — phase 4 demonstrated exactly that —
and no test catches it, because the failure mode is the test releasing too early. It is a property
of the harness, held by review and by the insert-before-lock experiment in the ticket's
*Verification* table.

**Nothing anywhere derives the next number from the tickets.** Not `max(number)`, not `count(*) + 1`,
not "highest open key + 1" — `ticket_sequence.next_number` is the only source, it is monotone, and
closing a ticket never touches it. That is invariant #10, and it is why a key survives `wont_do` and
`duplicate` without a special case.

**An unscoped caller is refused by the policy, not by this code, and the distinction is stated
because it was measured.** `V4` puts `ticket_sequence` behind `for all … using` with no separate
`with check`, so one predicate governs the read and the write: a row an unscoped caller cannot see
is a row it cannot create, and the insert fails with `new row violates row-level security policy`.
The allocator's own `?: error(…)` — refusing rather than answering 1 — is therefore a fallback the
type system requires, **not** a guard, and it has never been observed to fire; replacing it with
`?: 1` leaves the whole suite green. It is kept because 1 is the answer that silently restarts a
sequence, and a policy later widened on the write side alone would make it reachable. The test
asserts the refusal that actually does the work.

### 3.4 Dependency readiness

Pure, in `:domain`: `WorkingOrder.next(candidates, forActor)` over `TicketCandidate` — id, key,
priority, status, assignee, `dependsOn` — returning the chosen ticket **and the reason**, because
`docs/MCP.md` § 3.2 says `ticket_next` returns both.

The rules, each with the alternative it rejects:

- **A dependency is satisfied when the ticket it points at is `closed`, whatever the resolution.**
  Requiring `done` would leave a ticket permanently unstartable the moment a dependency is abandoned
  as `wont_do`, with no way out except editing the graph — and the graph is a record.
- **Candidates are `open` tickets only.** `in_progress` and `in_review` are already started,
  `blocked` is blocked, `closed` is finished.
- **A ticket assigned to another actor is not offered.** One assigned to the caller is, and ranks
  first: work already owned outranks work merely available.
- **Order:** priority, then `created_at`, then key. A total order, so the answer does not depend on
  the order rows came back in.

Nothing in this function reads an actor's kind (invariant #1). It compares actor **identity** for
assignment, which is a different question.

The SQL that feeds it belongs to whoever ships `ticket_next` (MCP-01); this package delivers the
rule, one adapter that loads candidates for a project, and the use case that joins them, so the rule
is exercised against a real dependency graph rather than only against hand-built lists.

## 4. Decisions taken here

### 4.1 Permission checks ship with the first mutating use case

`CreateTicket` requires `ticket.create`, `TransitionTicket` requires `ticket.transition` plus
`ticket.close` for a transition to `closed`, and `NextTicket` requires `ticket.read` — through
`PermissionService`, the one engine (invariant #2). A first mutating use case with no check would
leave every later surface to add one, which is how a second path starts.

`PermissionDirectory` has no adapter yet — that is CORE-01's port awaiting SEC-01's rows — so the
integration tests supply a small directory fake. This package does not write a real one: a
`JdbcPermissionDirectory` is the recursive grant-closure query, and it is not in this fence.

### 4.2 A denial and a gate refusal are both recorded, and both commit

`outcome = 'denied'` for the permission refusal, `outcome = 'failed'` for a gate refusal — a failure
that is a **value**, so the transaction commits carrying the audit row alone. The harness accepts a
transaction with an audit row and no mutation; that is what a refusal is.

### 4.3 The project context: this package establishes nothing, and says so where it matters

`JdbcUnitOfWork` still does not set `nodera.project_ids`, and this package does not add it. The
scope has to come from an authenticated context, SEC-01 is the package that produces one, and
inventing a source here would be deciding SEC-01's contract in passing.

What this package does instead is make the absence loud rather than silent, which is the part it
genuinely owns: `ClosureFactsReader` returns `null` rather than an empty-and-therefore-satisfied
answer, and the allocator never answers an empty read with a starting number (§ 3.3, including what
that guard does and does not do). Under the test harness the context is established at session level
by `SchemaFixture.openApp`, which is how the integration tests reach project-scoped rows at all —
and the unscoped case is tested rather than assumed.

## 5. Files

| File | Change |
|---|---|
| `backend/domain/src/main/kotlin/ai/nodera/domain/ticket/TicketKey.kt` | new — `TicketId`, `TicketPrefix`, `TicketNumber`, `TicketKey`, `TicketPriority`. |
| `backend/domain/src/main/kotlin/ai/nodera/domain/ticket/TicketStatus.kt` | new — statuses, resolutions, the transition function and its refusals. |
| `backend/domain/src/main/kotlin/ai/nodera/domain/ticket/ClosureGate.kt` | new — `ClosureFacts`, `UnmetClosureRequirements`, `ClosureVerdict`, the gate. |
| `backend/domain/src/main/kotlin/ai/nodera/domain/ticket/WorkingOrder.kt` | new — `TicketCandidate`, readiness and the working order. |
| `backend/domain/src/main/kotlin/ai/nodera/domain/ticket/Ticket.kt` | new — the entity the use cases return. |
| `backend/application/src/main/kotlin/ai/nodera/application/ticket/TicketPorts.kt` | new — the narrow ports the three use cases need. |
| `backend/application/src/main/kotlin/ai/nodera/application/ticket/usecase/CreateTicket.kt` | new. |
| `backend/application/src/main/kotlin/ai/nodera/application/ticket/usecase/TransitionTicket.kt` | new. |
| `backend/application/src/main/kotlin/ai/nodera/application/ticket/usecase/NextTicket.kt` | new. |
| `backend/application/src/test/kotlin/ai/nodera/application/ticket/TransitionTicketTest.kt` | new — the branches a database cannot be made to produce. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/ticket/JdbcTicketSequence.kt` | new — the locking allocator. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/ticket/JdbcTicketRepository.kt` | new — insert, read, transition. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/ticket/JdbcClosureFacts.kt` | new — the three reads, `null` when the ticket is not visible. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/ticket/JdbcTicketCandidates.kt` | new — candidates plus their dependency edges. |
| `docs/DOMAIN_MODEL.md` | § 5.1 gains the transition table of § 3.1. |
| `docs/plan/README.md` | this plan in the catalogue. |
| Tests | `:domain` for the pure rules, `:persistence` for everything a database decides. |

## 6. Test plan

Each line names the criterion it carries and the guard it is red without.

| Test | Proves | Red when |
|---|---|---|
| every allowed edge is allowed, and every one of the remaining pairs is refused | machine | an edge is added or dropped |
| `closed` without a resolution, and a resolution without `closed`, are refused | machine | the schema's rule is not restated |
| `blocked → closed` accepts `wont_do` and refuses the other three | machine | the diagram's note is dropped |
| the gate refuses on an unmet criterion, alone | AC1 | the criteria condition is dropped |
| the gate refuses on an unresolved blocking finding, alone | AC1 | the findings condition is dropped |
| the gate refuses when no review exists, alone | AC1 | the review condition is dropped |
| a resolved blocking finding and an unresolved non-blocking one do **not** refuse | AC1 | severity or resolution is ignored |
| all three failing at once names **all** of them | AC2 | any condition short-circuits |
| `UnmetClosureRequirements` cannot be constructed with nothing unmet | AC2 | the refusal may be empty |
| a ticket with no criteria, no findings and no reviews is **refused** | AC2 | an empty read reads as satisfied |
| closing a ticket the reader cannot see returns `NotFound`, not `Closed` | AC2 | `null` facts collapse to empty facts |
| two concurrent creates in one project produce two distinct keys | AC3 | the lock is dropped |
| eight concurrent creates produce eight distinct consecutive keys | AC3 | the lock is dropped |
| an allocation racing an open transaction gets the next number, not the same one, the holder released only on a wait naming `for update` | AC3 | `for update` is dropped |
| the same race against a deliberately unlocked allocator collides | AC3 | the lock stops being load-bearing |
| a key is not reissued after `wont_do`, `duplicate` or `done` | AC4 | anything derives the number from the tickets |
| an unscoped allocation is refused by the policy and leaves `next_number` untouched | AC4 | `ticket_sequence` loses its row-level security |
| every `TicketStatus`, `TicketResolution`, `TicketPriority` and `FindingSeverity` value is a label its column accepts, in the same order | — | an enum gains a value the column refuses, or the two orders diverge |
| a transition write matches nothing once the ticket has left the status it was decided against | machine | the `and status = ?` clause is dropped |
| concurrent transitions of one ticket leave exactly one winner | machine | the `and status = ?` clause is dropped |
| a create writes exactly one audit event, in its own transaction | #3 | the recorder call is dropped |
| a denied create writes `outcome = 'denied'` and no ticket | #3 | the denial is not recorded |
| a gate refusal writes `outcome = 'failed'` and does not transition | #3, AC1 | the refusal path skips the recorder |
| readiness: an open dependency blocks; one closed as `wont_do` does not | readiness | "satisfied means done" |
| the working order honours priority, then age, and skips tickets assigned elsewhere | readiness | the order is not total |

The concurrency tests use real connections from the audited data source — one per transaction —
against Testcontainers. A single-threaded simulation would not exercise the row lock, which is what
the ticket says and why the unlocked-allocator case is committed rather than run once by hand.

## 7. Deliberate non-goals

- **No acceptance-criterion or review writing.** `criterion_set`, `review_submit` and
  `finding_resolve` are CORE-04. The gate *reads* those rows; tests seed them.
- **No `ticket_next` query, no REST or MCP surface.** MCP-01 and API-01. This package delivers the
  rule and the use case behind it.
- **No `JdbcPermissionDirectory`.** § 4.1.
- **No project-context establishment.** § 4.3.
- **No `updated_at` trigger.** There is none in the schema and adding one is a migration this
  package does not need; the transition sets the column.
- **No repository-wide comment sweep.** Only the regions this package edits.

## 8. Open questions

**One, and it is a product decision rather than an implementation one: `open → closed` has no
path.** A ticket recognised as a duplicate the moment it is filed has to be walked through
`in_progress` and `in_review` to be closed, and the record then carries that ceremony forever. The
diagram in `docs/DOMAIN_MODEL.md` § 5.1 does not draw the edge, so this package does not add it.

**Recommendation:** allow `open → closed` for `wont_do`, `duplicate` and `superseded` — never for
`done`, which would route around the gate entirely. That is a change to the documented state machine
and belongs to the maintainers, so it is raised here rather than taken.

**Decided 2026-09-03 — as recommended.** `open → closed` exists for `wont_do`, `duplicate` and
`superseded` and refuses `done`, reusing the refusal `blocked → closed` already had rather than
adding a case. Implemented with a paired negative for each half in CORE-06
(`tickets/closed/CORE-06.md`); `docs/DOMAIN_MODEL.md` § 5.1 is the current edge set.
