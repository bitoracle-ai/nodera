---
id: MCP-02
title: MCP mutating tools with idempotency and structured gate errors
priority: P2
status: open
effort: ~2 d
depends_on: [MCP-01, CORE-04]
created: 2026-08-20
updated: 2026-08-20
---

# MCP-02 · MCP mutating tools with idempotency and structured gate errors

**Priority:** P2
**Effort:** ~2 d

## Motivation / context

Agents retry. Without idempotency a retried create produces a duplicate, and without structured
gate errors an agent that is refused can only guess. Both are what separate a usable agent surface
from a REST API with a different envelope.

## Current state (honest)

MCP-01 delivers the read half and the parity harness. `idempotency_record` exists in the schema
with nothing writing to it.

## Approach

1. `ticket_create`, `ticket_update`, `ticket_transition`, `ticket_assign`, `criterion_set`,
   `ticket_dependency_add`/`_remove`, `comment_create`, `review_submit`, `finding_resolve`.
2. Idempotency: required on `ticket_create` and `comment_create`, accepted everywhere else.
3. Gate refusals return the itemised `closure_gate_failed` body from `docs/MCP.md` section 4.
4. Extend the parity harness to every new capability.

## Acceptance criteria

- [ ] A repeated `ticket_create` with the same key returns the original result with
      `idempotent_replay: true` and creates nothing.
- [ ] The same key with different arguments returns `idempotency_conflict`, never an overwrite.
- [ ] A refused closure names every unmet criterion and every unresolved blocking finding.
- [ ] A dependency cycle is refused with the offending path in the error.
- [ ] The parity test covers every mutating capability added here.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/api-mcp/src/main/kotlin/ai/nodera/api/mcp/tools/`.
- `backend/application/src/main/kotlin/ai/nodera/application/idempotency/`.

## Verification

`./gradlew :api-mcp:test`. Drive a real agent client through create-retry and confirm one ticket
exists afterwards.
