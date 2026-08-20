---
id: API-01
title: REST API skeleton with a contract-first OpenAPI document
priority: P2
status: open
effort: ~3 d
depends_on: [CORE-01, SEC-01, CORE-03]
created: 2026-08-20
updated: 2026-08-20
---

# API-01 · REST API skeleton with a contract-first OpenAPI document

**Priority:** P2
**Effort:** ~3 d

## Motivation / context

The OpenAPI document is the contract the frontend generates its types from. Written after the fact
it becomes documentation nobody trusts; written first it is the thing both sides are checked
against.

## Current state (honest)

`:api-rest` is an empty module with a Ktor dependency. `docs/API_CONTRACT.md` does not exist yet.

## Approach

1. Write `docs/API_CONTRACT.md` and the OpenAPI document **before** the routes.
2. Routes for projects, tickets, criteria, dependencies and actors — translation only, no logic.
3. One error mapper turning the sealed application results into status codes and problem bodies.
   MCP-01 reuses this taxonomy, so it is designed for two consumers from the start.
4. CI check that the served document matches the committed one.

## Acceptance criteria

- [ ] Every route maps to a use case and contains no permission decision, no state transition, no
      SQL and no audit write.
- [ ] The committed OpenAPI document matches what the server serves; CI fails on drift.
- [ ] `not_found` is returned for a project the caller cannot see, never `forbidden` — a test
      confirms an outsider cannot distinguish absent from invisible.
- [ ] Every mutating route accepts an idempotency key.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `docs/API_CONTRACT.md` — new, and linked from `docs/INDEX.md` in the same commit.
- `backend/api-rest/src/main/kotlin/ai/nodera/api/rest/`.

## Verification

`./gradlew :api-rest:test`. The enumeration test asserts identical responses for a non-existent
project and an invisible one, byte for byte.
