---
id: CORE-03
title: Ticket lifecycle, key allocation and the closure gate
priority: P2
status: open
effort: ~3 d
depends_on: [CORE-01, CORE-02]
created: 2026-08-20
updated: 2026-08-20
---

# CORE-03 · Ticket lifecycle, key allocation and the closure gate

**Priority:** P2
**Effort:** ~3 d

## Motivation / context

The closure gate is the mechanism that makes a ticket a specification rather than a status field.
It has to live in the domain service, because both surfaces need the same refusal and the same
itemised explanation of what is missing.

## Current state (honest)

The schema carries `ticket`, `acceptance_criterion`, `ticket_dependency` and `ticket_sequence`. No
domain logic exists: no state machine, no key allocation, no gate.

## Approach

1. The status state machine in `:domain` as a pure transition function.
2. Key allocation locking the `ticket_sequence` row with `select ... for update`, so two concurrent
   creates in one project cannot produce the same key.
3. `ClosureGate` returning a structured `UnmetClosureRequirements` rather than a boolean — the MCP
   error shape in `docs/MCP.md` section 4 is the consumer, and a boolean cannot feed it.
4. Dependency-readiness logic for the working-order query that `ticket_next` will use.

## Acceptance criteria

- [ ] A transition to closed/done is refused while any acceptance criterion is unmet, any blocking
      finding is unresolved, or no review exists — one test per condition.
- [ ] The refusal names every missing item, not just the first.
- [ ] Concurrent creation of two tickets in one project produces two distinct keys; proved by a
      test that actually runs them concurrently.
- [ ] A ticket key is never reused after closure, including `wont_do` and `duplicate`.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/domain/src/main/kotlin/ai/nodera/domain/ticket/`.
- `backend/application/src/main/kotlin/ai/nodera/application/ticket/`.

## Verification

`./gradlew :domain:test :application:test :persistence:test`. The concurrency test uses two real
connections against Testcontainers; a single-threaded simulation would not exercise the row lock.
