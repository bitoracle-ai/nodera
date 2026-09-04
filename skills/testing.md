---
summary: What to test and how — the paired-negative rule, real Postgres for anything involving RLS, throwaway test environments that are torn down, surface parity tests, per-file coverage, and the tests that are worse than no test.
read_when:
  - Before writing tests, and before reviewing them.
  - When deciding whether a test actually proves what it claims.
  - Before starting anything a test needs beyond this repository's toolchain — a database, a service, the running stack.
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

## Test environments are throwaway, and they are torn down

A check, test or simulation that needs more than this repository's own toolchain — a database, a
service, the running stack — runs in a **throwaway Docker environment created for that run**. Never
the host's own installs, and never a developer's persistent volume or dev stack.

Two of the mechanics for it exist already:

- **Testcontainers** for the persistence tests. It starts its containers per run and its reaper
  removes what it labelled — containers, networks, volumes — including when the run is killed. The
  base image it pulled stays in the local image cache, which is what makes the next run fast.
- **A compose project under its own name** — `docker compose -p <name> …`, torn down with
  `down -v`. Without `-p`, a checkout takes its directory name, so `pgdata` resolves to the same
  volume `make up` uses (`nodera_pgdata` in a directory called `nodera`) and an unnamed project runs
  the test against the developer's database. The restore drill in
  [`../docs/ops/backup-restore.md`](../docs/ops/backup-restore.md) already runs under its own name,
  for exactly that reason — and it runs `compose.prod.yml`, which is the half that makes it work:
  `docker-compose.yml` pins `container_name: nodera-postgres`, and a pinned name is
  project-independent, so `-p` alone still collides with a running `make up`. A second project on
  the development file needs its own fragment, or the pin gone.

**`make verify-db` is the exception, and it is stated rather than glossed.** It is the CI database
lane locally, and it isolates the *data*: it creates `nodera_verify`, applies the sequence twice,
runs the schema checks and drops that database again, so it never touches the development database.
It is not a throwaway environment. `verify-db` depends on `up`, so it runs inside the developer's
Postgres — starting that container if it was stopped, leaving it running afterwards, and leaving
behind the cluster-level `nodera_app` role the migrations create. In CI the same lane gets an
ephemeral `services: postgres` container per job and none of this applies. A run that uses it says
so in the record instead of claiming the run left nothing;
[CI-02](../tickets/open/CI-02.md) is the package that gives the target an environment of its own.

**`make up` and the volume behind it are the developer's, not a test environment.** They hold work
in progress between sessions; `make down` stops the stack and removes the containers, and the volume
survives on purpose. A test that writes into them proves something about that machine's state rather
than about the code — and it is the one failure mode nobody notices until the state it depended on
is gone.

At the end of a run, what it created is removed — containers, volumes, networks — and nothing it
started is left running. The closure record names what was created and what was removed
([`../docs/PROJECT_MANAGEMENT.md`](../docs/PROJECT_MANAGEMENT.md) § 9); a package that needed none
says that instead, and one that used a mechanic with a limit above names the limit rather than the
claim.

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
