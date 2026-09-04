# Plan — CORE-02 · Audit recorder, one event per mutation, in the mutation's transaction

**Status:** `implemented`
**Ticket:** [`../../tickets/closed/CORE-02.md`](../../tickets/closed/CORE-02.md)
**Invariant this implements:** #3 (the audit trail is append-only, **and complete**) —
[`../../skills/critical-invariants.md`](../../skills/critical-invariants.md)

---

## 1. What phase 1 found

`V4` carries `audit_event` with its append-only triggers, its privilege split and its RLS policy,
and DB-01 proved all three refuse. Above the database there is nothing: no writer, no transaction
abstraction, no connection pool, no use case. `:persistence` holds exactly one main source file
(`Migrations.kt`) and it speaks raw JDBC.

Two consequences shape this package.

**There are no mutating use cases yet, so criterion 1 cannot be proved by enumeration.** CORE-03 and
CORE-04 write the first ones. What CORE-02 can deliver — and what the ticket actually asks for — is
the writer, the transaction seam it writes through, and a harness that makes an omission fail a
build rather than survive a review. Criterion 1 therefore reads: the mechanism holds for every
mutation the harness can see, proved on a representative mutation now, and it goes red the moment a
future one skips the recorder.

**The schema and the domain disagree about one type.** `ActorContext.requestId` is a
`value class RequestId(String)`; `audit_event.request_id` is `uuid not null`. A string that is not a
UUID type-checks, passes the domain, and fails on the last statement of the mutation's transaction —
which is the failure mode this package exists to prevent, arriving one layer earlier than expected.
`ActorIdentity.kt` already states the rule this breaks: the domain refuses exactly what the database
would refuse, rather than one insert later. `RequestId` therefore becomes `value class(Uuid)`.

## 2. The shape

```
  use case (CORE-03+)                     :application
        │  unitOfWork.inTransaction {           ┌──────────────────────┐
        │      … the mutation …                 │ AuditRecorder        │
        │      recorder.record(ctx, entry)  ────▶│  ctx + entry → event │
        │  }                                    └──────────┬───────────┘
        │                                                  │ AuditEventSink (port)
        ▼                                                  ▼
  UnitOfWork (port)  ────────────────────▶  JdbcUnitOfWork · AuditEventRepository
                                            one JDBC connection, in the coroutine context
                                                          :persistence
```

The recorder never opens a transaction and the sink refuses to write outside one. That refusal is
the guard, not a convention: a recorder that could open its own would produce exactly the failure
this package exists to prevent — a mutation that commits while its audit row rolls back, or the
mirror image.

### 2.1 Why the transaction rides in the coroutine context

`:application` may not see JDBC, so "the caller's transaction" cannot be a `Connection` parameter.
The alternatives were:

| Option | Rejected because |
|---|---|
| Thread the transaction through every port signature | Every port grows a parameter it does not use, and a port is defined by the use case that needs it. |
| An opaque `Transaction` marker the adapter downcasts | A downcast is an unchecked assumption in the one path that must not have any. |
| Exposed's `newSuspendedTransaction` + `TransactionManager.current()` | Reasonable, and the eventual home when repositories arrive. Today it buys nothing: `audit_event` is one parameterised `insert`, and adopting the DSL here would mean a table object, a jsonb column type and an enum mapping before any of it is exercised. |

So `JdbcUnitOfWork` puts the connection in a `CoroutineContext` element and
`AuditEventRepository` reads it from there. That is what Exposed does internally, and it keeps
`:application` free of the detail.

**This is not the `ActorContext` argument re-litigated.** `skills/backend-kotlin.md` refuses
ambient context for *who is acting*, because the call site is where a reviewer of an audit-sensitive
path needs to see it. The transaction is the opposite case: it is the ambient unit of work, nobody
audits it, and every use case is inside exactly one. `ActorContext` stays a parameter — of the use
case and of every recorder call.

### 2.2 The types

`:domain`, package `ai.nodera.domain.audit` — the event is a domain entity
(`docs/DOMAIN_MODEL.md` § 9), so its vocabulary lives with the rest of the model.

| Type | Shape | Why |
|---|---|---|
| `AuditAction` | `value class(String)`, `^[a-z_]+\.[a-z_]+$` | The column's own check, restated so the domain refuses what the database would refuse. |
| `AuditOutcome` | `enum { SUCCESS, DENIED, FAILED }` | The column's three permitted values. |
| `AuditEntry` | what the use case knows | action, entity type/id, project, `before`/`after`, outcome, tool name. |
| `AuditEvent` | `ActorContext` + `AuditEntry` | Composition, not thirteen fields: `actor_kind` (AU2) and `on_behalf_of_actor_id` (AU4) can then only come from the context, never from a caller that forgot them. |

`before` and `after` are `Map<String, String?>` — field name to rendered value, written to the
`jsonb` columns as a flat object. Rendered rather than typed: `:domain` is framework-free, so it has
no serialiser, and a trail read by a person or an agent wants the value as it would be shown. A
package that needs structure can widen the type; nothing here is lost by starting flat.

`occurred_at` is left to the column default. The database is the clock for the trail, so two rows
written in one transaction cannot disagree about when it happened.

### 2.3 What "records failures" can and cannot mean

`outcome = 'denied'` is straightforward: the permission check refused, nothing was mutated, and the
transaction commits carrying the audit row alone.

`outcome = 'failed'` is only honest for a failure that is a **value** — a domain rule refused, the
use case returns a sealed result, the transaction still commits. A failure that *aborts* the
transaction takes the audit row with it, and that is correct rather than a gap: the mutation left no
trace either, and writing the row from a second transaction would reintroduce, in mirror image, the
exact defect this package exists to prevent. Crashes belong to the log, not to the trail.

## 3. Enforcing completeness

The ticket's open question, decided here: **a test-time transaction listener at the JDBC layer.**
Annotation processing is rejected — it is a large build dependency, and it would only see
annotations somebody remembered to write, which is the review attention this criterion is trying to
replace.

`AuditCompleteness` wraps a JDBC `Connection` and every statement it produces, records the SQL that
actually executes, and evaluates one rule at `commit()`:

> A transaction that mutates any table other than `audit_event` must contain **exactly one**
> `insert into audit_event`.

It sees statements, not calls, so it is indifferent to how a use case is written — a mutation that
bypasses `AuditRecorder` entirely is caught the same way as one that forgets it. A transaction with
an audit row and no mutation is accepted, because that is what a denial is.

**What it does and does not see, stated rather than implied.** The scan is unanchored and counts
every match after comments, string literals and locking clauses are removed in one left-to-right
pass — the last of those because `select … for update of ticket_sequence`, the shape key allocation
writes, otherwise reads as a mutation of a table called `of`. So a mutation inside a common table
expression, behind a leading comment, or second in a multi-statement string is found; `merge into` and `copy` are classified alongside the three obvious verbs, and a
schema-qualified or quoted table name collapses to the bare name. `Statement`, `PreparedStatement`
and `CallableStatement` are all intercepted, on every `execute*` and `addBatch`, and `commit()` and
`setAutoCommit(true)` are both checked because JDBC commits through either. Two holes remain and are
named rather than left to be found: a mutation issued on a connection it never handed out — which is
the hole the linter rule below closes, and the reason that rule exists — and one issued on the raw
connection a `ResultSet` still exposes, which would need a third proxy layer for a shape no caller
has.

**Bypassing it must not be a matter of attention either.** `JdbcUnitOfWork` is therefore added to
`scripts/lint_invariants.py` in the shape the `PermissionService` rule already has: constructing one
outside the composition root or the audited fixture is a finding, with fixtures in the same
`--self-test` that proves the sweep still fires.

**The harness ships with its own paired negative,** permanently rather than as an experiment
somebody once ran: `AuditCompletenessTest` commits a deliberately un-audited mutation and asserts
the harness refuses it, and commits a doubly-audited one and asserts the same. A completeness check
that has never caught anything is not a completeness check.

## 4. Files

| File | Change |
|---|---|
| `backend/domain/src/main/kotlin/ai/nodera/domain/actor/ActorContext.kt` | `RequestId` carries a `Uuid` (§ 1). |
| `backend/domain/src/main/kotlin/ai/nodera/domain/audit/AuditEvent.kt` | new — the four types in § 2.2. |
| `backend/application/src/main/kotlin/ai/nodera/application/audit/AuditEventSink.kt` | new — the one-method port. |
| `backend/application/src/main/kotlin/ai/nodera/application/audit/AuditRecorder.kt` | new — `record`, `recordDenied`. |
| `backend/application/src/main/kotlin/ai/nodera/application/transaction/UnitOfWork.kt` | new — the transaction port. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/JdbcUnitOfWork.kt` | new — the boundary and the context element. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/audit/AuditEventRepository.kt` | new — the sink adapter. |
| `backend/persistence/build.gradle.kts` | `kotlinx-coroutines-core` for the transaction boundary, `kotlinx-serialization-json` for the two `jsonb` columns. |
| `backend/domain/src/test/kotlin/.../ActorIdentityTest.kt` | follows `RequestId`. |
| `docs/plan/CORE-01.md` · `docs/API_CONTRACT.md` | the two claims `RequestId`'s new shape falsifies. |
| `backend/domain/src/test/kotlin/.../audit/AuditEventTest.kt` | new — the action grammar and the entry's own rules. |
| `backend/application/src/test/kotlin/.../PermissionFixtures.kt` | follows `RequestId`. |
| `backend/application/src/test/kotlin/.../audit/AuditRecorderTest.kt` | new. |
| `backend/persistence/src/test/kotlin/.../AuditCompleteness.kt` | new — the listener and the audited fixture. |
| `backend/persistence/src/test/kotlin/.../AuditFixtures.kt` | new — the context builder and the two read-backs the specs share. |
| `backend/persistence/src/test/kotlin/.../AuditCompletenessTest.kt` | new — criteria 1, 2 and 5. |
| `backend/persistence/src/test/kotlin/.../AuditEventRepositoryTest.kt` | new — criteria 3 and 4, and the row's shape. |
| `backend/persistence/src/test/kotlin/.../SchemaFixture.kt` | one accessor: a connection the caller owns. |
| `scripts/lint_invariants.py` | the `JdbcUnitOfWork` rule and its self-test fixtures. |

## 5. Test plan

Each line names the criterion it carries and the guard it goes red without.

| Test | Proves | Red when |
|---|---|---|
| a mutation and its audit row commit together | 1 | the recorder call is removed |
| an un-audited mutation is refused by the harness | 5 | the listener's rule is removed |
| a twice-audited mutation is refused by the harness | 1, 5 | `!= 1` becomes `< 1` |
| a failing transaction leaves neither the mutation nor the audit row | 2 | the sink opens its own transaction |
| the sink refuses to write with no transaction open | 2 | the sink falls back to a fresh connection |
| a denied operation writes `outcome = 'denied'` and no mutation | 3 | the outcome is not carried to the column |
| `on_behalf_of_actor_id` is written from the context's delegation, and is null without one | 4 | the recorder builds the row without the context |
| every `Surface`, `ActorKind` and `AuditOutcome` value is a label the schema accepts | — | an enum gains a value the column refuses |
| one `record` call produces exactly one append | 1 | the recorder retries or fans out |
| `AuditAction` refuses a malformed verb | — | the check is dropped |

Database-backed tests live in `:persistence`, because `:application` may not depend on it and the
rows only exist against a real Postgres. That moves `AuditCompletenessTest` out of the module the
ticket's *Affected files* names; the acceptance criteria are unchanged.

## 6. Deliberate non-goals

- **No use case.** CORE-03 writes the first mutating one, against this seam.
- **No connection pool and no wiring in `:app`.** Nothing calls a use case yet, so a pool wired now
  would be configuration with no consumer and no test. `JdbcUnitOfWork` takes a `DataSource`;
  whoever needs the first use case supplies it.
- **No Exposed DSL.** § 2.1.
- **No production-time completeness check.** Intercepting every statement in the serving path is a
  cost and a failure mode this package cannot justify; the listener is the harness's.
- **No repository-wide comment sweep.** Only the regions this package edits.

## 7. Open questions

**One handed to API-01, deliberately not settled here.** `RequestId` is a `Uuid`, so a surface has to
decide what happens to a client-supplied `X-Request-Id` that is not one: reject the request, or
substitute a server-generated id and echo that. Both are defensible and the choice is a contract
decision, not a persistence one. `docs/API_CONTRACT.md` states only the shape.

Nothing else outstanding. The ticket's one open decision — how to enforce completeness mechanically — is
settled in § 3, with the reason recorded in the ticket.
