---
summary: What to test and how — the paired-negative rule, real Postgres for anything involving RLS, surface parity tests, per-file coverage, and the tests that are worse than no test.
read_when:
  - Before writing tests, and before reviewing them.
  - When deciding whether a test actually proves what it claims.
---

# Testing — Nodera

## The paired-negative rule

**Every safety claim ships with a test that is demonstrably red when the guard is disabled.**

Write the test, disable the guard, watch it fail, re-enable, watch it pass. A test that has never been
seen to fail proves nothing about the guard — only that the code runs.

This applies to: permission checks, the closure gate, RLS policies, attenuation, append-only triggers,
the reviewer-independence rule, and every input validation described as protection.

## Real Postgres for anything the database enforces

RLS policies, triggers, constraints and privilege grants are tested against a real Postgres via
Testcontainers. An in-memory substitute has none of them, so a green suite against one proves the
application code compiles, not that the invariant holds.

## Surface parity

Anything enforced on both REST and MCP gets a parity test: the same denial, driven through both, with
the same expected outcome. This is the test that keeps "one permission engine" true after the design
document stops being read.

## Coverage

Per-file, at least 80 %, enforced in CI. An aggregate threshold lets a well-tested file carry an
untested one, and the untested one is usually the newest and most dangerous.

Coverage is a floor, not a goal. A file at 95 % whose tests assert nothing meaningful is worse than one
at 80 % with sharp tests, because it looks finished.

## Tests that are worse than no test

| Pattern | Why |
|---|---|
| Passes against both the old and the new code | Tests nothing. Verify by reverting the change. |
| Asserts on internal state rather than behaviour | Breaks on refactors, survives real regressions. |
| Mocks the thing under test | Proves the mock works. |
| Asserts "no exception thrown" and nothing else | A silent wrong answer passes. |
| Depends on test execution order | Fails randomly, gets marked flaky, gets ignored. |
| Reproduces the implementation's arithmetic in the assertion | Both are wrong together. |

## Naming

Test names are English sentences describing the behaviour and its condition:

```kotlin
"refuses closure when a blocking finding from an earlier round is unresolved"
"returns zero rows when the project context was never established"
```

Not `testCloseTicket2`. The name is what a reader sees in a failure report, and it should say what
broke without opening the file.

## What does not need a test

Generated code, framework configuration with no branching, and pure data classes. Writing tests for
these inflates the number and dilutes attention on the ones that matter.
