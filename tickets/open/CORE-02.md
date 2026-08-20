---
id: CORE-02
title: Audit recorder — one event per mutation, in the mutation's transaction
priority: P1
status: open
effort: ~2 d
depends_on: [CORE-01, DB-01]
created: 2026-08-20
updated: 2026-08-20
---

# CORE-02 · Audit recorder — one event per mutation, in the mutation's transaction

**Priority:** P1
**Effort:** ~2 d

## Motivation / context

The audit trail is what makes agents accountable rather than merely permitted, and it is the
capability that cannot be added later — a trail with a gap where a feature predates it is not a
trail. It has to be in place before the first mutating use case ships.

## Current state (honest)

`audit_event` exists in `V4` with its append-only triggers and privilege split. No writer exists,
and nothing yet forces a use case to write one.

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

## Acceptance criteria

- [ ] Every mutating use case writes exactly one `audit_event` row, in the same transaction.
- [ ] A test proves the audit row rolls back **with** the mutation when the transaction fails.
- [ ] Denied operations produce a row with `outcome = 'denied'`, proved by a test.
- [ ] `on_behalf_of_actor_id` is populated wherever the context carries a delegation.
- [ ] The enforcement mechanism fails when a deliberately un-audited mutation is added.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/application/src/main/kotlin/ai/nodera/application/audit/AuditRecorder.kt`.
- `backend/persistence/src/main/kotlin/.../AuditEventRepository.kt`.
- `backend/application/src/test/kotlin/.../AuditCompletenessTest.kt`.

## Verification

`./gradlew :application:test :persistence:test`. The rollback test is the important one: assert the
audit table is empty after a deliberately failed mutation.
