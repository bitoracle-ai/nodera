---
id: FIX-02
title: The invariant F1 paired negative times out under load
priority: P2
status: open
effort: ~0.5 d
depends_on: []
created: 2026-08-31
updated: 2026-08-31
note: Found by DB-01, which must not fix it — frontend/ is a foreign subtree for a DB- package.
---

# FIX-02 · The invariant F1 paired negative times out under load

**Priority:** P2
**Effort:** ~0.5 d
**Skills:** `critical-invariants.md` · `frontend-react.md` · `testing.md`

## Motivation / context

`frontend/src/invariants.test.ts` is WEB-03's paired negative for invariant F1 — the proof that the
lint rule banning a direct `fetch` in a component actually fires. It runs ESLint programmatically,
and on a loaded machine the first of its two cases exceeds vitest's default 5 s `testTimeout`.

A required gate that fails on machine speed rather than on code is the failure mode
[`../skills/testing.md`](../skills/testing.md) names outright: *fails randomly, gets marked flaky,
gets ignored*. `CI Gate` is a required check, so a random red blocks a merge that has nothing wrong
with it — and the fix people reach for is re-running until green, which is how a real red gets
waved through.

## Current state (honest)

Observed during DB-01, on the same tree, minutes apart:

| Run | Conditions | Result |
|---|---|---|
| `make check` | backend gradle running concurrently | `src/invariants.test.ts` — *"rejects a direct fetch in a component"* **failed: Test timed out in 5000ms** (took 6635 ms). Suite duration 63.94 s. |
| `yarn test:run` | nothing else running | 17 passed, suite duration 6.46 s |
| `make check` | nothing else running | All gates green |

The asymmetry is the tell: the **first** case pays ESLint's cold start — flat-config resolution and
plugin loading — and the second reuses the warmed module cache, so the second has never been seen to
fail. Nothing about the assertion is timing-dependent; only the setup is.

This has not been seen on a CI runner. It is a property of the test, not of this machine, so a
loaded or slower runner would hit it too.

## Approach

1. Reproduce deterministically — run the suite under artificial CPU load, or lower `testTimeout`
   until the first case fails reliably.
2. Prefer removing the cold start over widening the clock. Options, in the order they are worth
   trying: construct the `ESLint` instance once and share it across both cases; warm it in
   `beforeAll`; or move the F1 probe out of vitest into the `yarn lint` step that already loads
   ESLint. A raised `testTimeout` is the fallback, not the first answer — it hides the next
   regression in the same place.
3. Whatever is chosen, the paired negative must stay a paired negative: it still has to be
   demonstrably red when the lint rule is removed.

## Acceptance criteria

- [ ] The F1 proof passes with the machine under load, demonstrated rather than assumed.
- [ ] It is still demonstrably red when the `no-restricted-globals` rule for `fetch` is removed —
      the property WEB-03 added it for.
- [ ] If a timeout was raised rather than the cost removed, the ticket records why the cheaper fix
      did not work.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `frontend/src/invariants.test.ts` — the probe.
- `frontend/vite.config.ts` — only if a timeout change turns out to be the right answer.

## Verification

`cd frontend && yarn test:run` with the machine loaded, and once with the lint rule removed to
confirm the negative still goes red.

## Why this is its own ticket

`docs/PROJECT_MANAGEMENT.md` § 8, **foreign subtree**: found while closing DB-01, a `DB-` package,
which must not edit `frontend/`. DB-01 closes one ticket and opens this one, so the net rule holds.
