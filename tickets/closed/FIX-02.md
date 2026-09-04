---
id: FIX-02
title: The invariant F1 paired negative times out under load
priority: P2
status: closed
effort: ~0.5 d
depends_on: []
created: 2026-08-31
updated: 2026-09-01
closed: 2026-09-01
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
[`../../skills/testing.md`](../../skills/testing.md) names outright: *fails randomly, gets marked
flaky, gets ignored*. `CI Gate` is a required check, so a random red blocks a merge that has nothing
wrong with it — and the fix people reach for is re-running until green, which is how a real red gets
waved through.

## Current state (honest)

**Fixed.** The proof left vitest: `frontend/eslint.selftest.mjs` lints the same two fixtures against
the repository's real ESLint configuration, and `yarn lint` runs it first — the shape
`lint_invariants.py --self-test` established, where a gate is proved to still fire before the lane
trusts it. `frontend/src/invariants.test.ts` is gone.

The asymmetry recorded when this ticket was opened is real, but "cold start" understates the cost,
and the cost fits under no clock vitest offers. Measured here (16 cores; *load* = N `node` processes
in a busy loop):

| Variant | Conditions | Result |
|---|---|---|
| As committed | warm, idle | pass — first case 1303 ms, second 12 ms |
| As committed | cold file cache, machine busy | **fail** — first case 22 763 ms against a 5 s `testTimeout` |
| As committed | 24 load processes | **fail** — first case 7037 ms, second 183 ms |
| One shared `ESLint` instance | 24 load processes | **fail** — first case 7492 ms |
| Warmed in `beforeAll` | 24 load processes | pass — the hook absorbed it; cases 1088 ms and 109 ms |
| Warmed in `beforeAll` | 48 load processes | **fail** — `Hook timed out in 10000ms`, both cases reported **skipped** |
| `yarn lint` (this package) | 48 load processes | pass — 55.7 s |
| `yarn lint` (this package) | warm, idle | pass — 3.25 s, of which the probe is ~1.6 s |

In a plain `node` process the same two lints cost 1243 ms and 10 ms, and a second `new ESLint()`
costs 10 ms. So the cost belongs to neither vitest nor the instance: ESLint loads the flat
configuration and its plugin graph once per process, and machine load multiplies that by an order of
magnitude.

**That is what disqualifies the two cheaper options this ticket listed.** Sharing one instance saves
the 10 ms of construction, not the 1.2 s of loading — the first case pays it however much is shared,
and it still timed out, at 7492 ms. Warming in `beforeAll` does not remove the cost either; it moves
it under `hookTimeout`, a 10 s clock instead of a 5 s one, and when a heavier load exceeds that one,
vitest reports both cases as **skipped** rather than failed. A paired negative that stops running
without going red is worse than the flake it replaces. Raising `testTimeout` was never reached: it
would have to clear 23 s to cover what was observed here, which is a five-fold clock over a cost
nobody would notice growing.

`yarn lint` carries no per-test clock. The only budget left on that path is the frontend job's own
`timeout-minutes: 20`, against a worst recorded run of 55.7 s, so the proof takes as long as the
machine needs and the only thing that can fail it is the answer changing.

**The gate that would not run at first, and why it runs now.** `make check` initially exited **2** at
`check-backend`, on `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`.
That is machine configuration, not an absence: `JAVA_HOME` is unset and no `java` or `javac` is on
`PATH`, but a full JDK 21 ships inside an IDE installation on this machine, and
`backend/build.gradle.kts` pins `jvmToolchain(21)` while CI pins `JAVA_VERSION: "21"`, so that
runtime satisfies the toolchain. With `JAVA_HOME` pointed at it, `make PY=py check` exits **0**
across all four lanes.

Two details recorded rather than glossed. In that run Gradle reported the backend test tasks
`UP-TO-DATE`, correctly — this package changes no backend input — so the lane was additionally re-run
forced (`./gradlew test --rerun-tasks`): 24 tasks executed, **118 tests, 0 failures**, including the
Testcontainers suite in `:persistence`. And no toolchain resolver is configured, so Gradle cannot
provision a JDK itself: on a machine without one, the backend lane stops rather than self-heals.

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

- [x] The F1 proof passes with the machine under load, demonstrated rather than assumed — reproduced
      red first (7037 ms against a 5 s budget under 24 load processes; 22 763 ms on a cold, busy
      machine), then green under 48, the load that breaks every in-vitest variant.
- [x] It is still demonstrably red when the `no-restricted-globals` rule for `fetch` is removed —
      run, output below. Red in the other direction too, with the `src/api/**` exemption removed, and
      re-run after the exit mechanism changed in review.
- [x] No timeout was raised, so nothing needs excusing; the table above records why the two cheaper
      fixes fail anyway, since neither is obviously wrong until it is measured.
- [x] `make check` green — `make PY=py check` exit **0**, four lanes, with a JDK 21 on `JAVA_HOME`.
      The first attempt exited 2 at `check-backend` for want of one; recorded above.
- [x] Independent review: 0 BLOCKING findings — four independent sub-agent rounds, below.

## Affected files

- `frontend/eslint.selftest.mjs` — new. The probe, beside the configuration it puts on trial.
- `frontend/package.json` — `yarn lint` runs the probe, then `eslint .`.
- `frontend/src/invariants.test.ts` — removed.
- `docs/ci.md` — the frontend lane's row, why the proof lives in the lint step, and what that trades.
- `docs/plan/CORE-01.md` — one parenthetical: it cited the deleted file as F1's exemplar.
- `tickets/INDEX.md` — the WEB-03 paragraph called the proof a committed *test*; it is now a gate.
- `docs/docs_map.md`, `REVIEW_REPORT.md` — regenerated (`scripts/generate_docs_map.py`,
  `scripts/tickets_index.py --write`).
- `CHANGELOG.md` — `Unreleased` → `Fixed`.

Not `frontend/vite.config.ts`: no timeout was changed. The probe sits at the frontend root rather
than in `frontend/scripts/` because `coverage.include` sweeps `scripts/**` under a per-file 80 %
threshold and a gate script has no unit test — tried there first under the name
`scripts/lint-selftest.mjs`, it failed `yarn test:coverage` with `ERROR: Coverage for lines (0%) does
not meet global threshold (80%)`, which was run rather than predicted. Beside `eslint.config.js` is
also where it belongs: it is that file's proof, and it resolves the configuration from its own
directory, so it passes from any working directory.

## Verification

Reproduction, then the fix, under artificial load (`node` busy loops on a 16-core machine):

```
$ yarn vitest run src/invariants.test.ts          # 24 load processes, before
 x rejects a direct fetch in a component 7037ms
   -> Test timed out in 5000ms.
 v allows the same call inside src/api/ 183ms

$ yarn lint                                        # 48 load processes, after
OK - invariant F1 fires on a component and stays silent in src/api/.
Done in 55.71s.

$ yarn test:coverage                               # 48 load processes, after
 Test Files  3 passed (3) · Tests  15 passed (15) · tests 2.42s
```

The paired negative, both directions, by editing `frontend/eslint.config.js` and reverting it. Run
again after review changed `process.exit(1)` to `process.exitCode`, because that is the line the
gate's redness depends on:

```
$ yarn lint                                        # the F1 rule deleted from the config
$ node eslint.selftest.mjs && eslint . --max-warnings 0
src/probe-f1.tsx: expected 1 no-restricted-globals finding(s), got 0.
Invariant F1 is not enforced the way eslint.config.js claims it is.
error Command failed with exit code 1.
exit=1

$ node eslint.selftest.mjs                         # the src/api/** exemption deleted instead
src/api/probe-f1.tsx: expected 0 no-restricted-globals finding(s), got 1.
Invariant F1 is not enforced the way eslint.config.js claims it is.
exit=1
```

`eslint .` never runs in that first case: `&&` short-circuits, so the lane fails on the probe rather
than on a lint pass that would have been green.

Gates on the final tree:

```
$ make PY=py check-repo       exit 0
$ make PY=py check-db         exit 0
$ make PY=py check-frontend   exit 0   (install, generated client fresh, lint, typecheck, coverage, build)
$ make PY=py check            exit 0   (four lanes, JAVA_HOME set to a JDK 21)
$ ./gradlew test --rerun-tasks         118 tests, 0 failures — the backend lane forced rather than UP-TO-DATE
```

## Review result

**Four independent sub-agent rounds, all against the staged diff, none in the context that wrote
it. Every round returned `APPROVED`, 0 BLOCKING.** Three reviewed the implementation — two read-only
in parallel, one in the foreground — and they overlapped on findings, which is the useful part: the
three that repeated are the three that were fixed first. The fourth reviewed the closure after those
fixes, because one of them changed how the gate exits.

**Round 1 — APPROVED, 0 BLOCKING, 1 NON-BLOCKING.** `docs/plan/CORE-01.md` still cited the deleted
test as invariant F1's exemplar, in the present tense. The reviewer stated the counter-argument
itself (§ 10 keeps an implemented plan as a record) and left the decision open. Fixed: the
parenthetical is repointed, one line, nothing else in that document touched.

**Round 2 — APPROVED, 0 BLOCKING, 4 NON-BLOCKING.** The one worth the round: `process.exit(1)`
immediately after `console.error` can discard a pending stderr write on a runner, leaving a red lint
step with no line saying which fixture disagreed — the exact shape this ticket exists to eliminate.
Fixed with `process.exitCode = 1`, and the paired negative re-run in both directions afterwards to
confirm the lane still goes red and now prints both lines. Also fixed from this round: the "no
clock" claim overstated the case (the frontend job carries `timeout-minutes: 20`); the coverage
transcript quoted the trial filename `scripts/lint-selftest.mjs`, which exists nowhere in the tree;
and `tickets/INDEX.md` still called the proof a committed test.

**Round 3 — APPROVED, 0 BLOCKING, 5 NON-BLOCKING.** Overlapped with round 2 on the plan reference,
the quoted filename and the clock wording. New: the ticket's `yarn lint` warm/idle row read 14.1 s,
where the reviewer measured 3.7 s and 4.1 s. Re-measured warm and idle here: 3.25 s, probe 1.63 s —
the 14.1 s was the first invocation after the load runs, i.e. a cold cache, not the stated condition.
The row is corrected. Also recorded, at the reviewer's suggestion: the proof no longer runs under
`yarn test` or `make test`, only under `yarn lint`; both gate paths run `yarn lint`, so no gate lost
it, and `docs/ci.md` now says so rather than leaving it to be rediscovered.

**Round 4 — APPROVED, 0 BLOCKING, 2 NON-BLOCKING**, run on the closure itself. It re-proved the exit
path in both directions read-only, ran the lanes itself — `check-repo`, `check-db` and `check-backend`
each exit 0, and the forced `./gradlew test --rerun-tasks` at 118 tests, 0 failures — and checked
that this section does not overstate what the earlier rounds did. One finding is fixed here:
`REVIEW_REPORT.md` is regenerated by the same change and was missing from Affected files.

The other is **left deliberately, and recorded rather than fixed**: `tickets/closed/DB-01.md` links
this ticket as `../open/FIX-02.md`, which the move makes dead. Editing it is what
[`../../docs/PROJECT_MANAGEMENT.md`](../../docs/PROJECT_MANAGEMENT.md) § 13 refuses — a closed ticket
is a record, not something tidied when it later reads badly — and the same shape already exists at
`tickets/closed/DOC-03.md`, which links `../open/DOC-04.md`. The real answer is a gate: no check
resolves links inside `tickets/` at all (`lint_docs_index.py` walks `docs/`), and one that did would
catch both. That is outside this package's scope, so it goes to the maintainers as a proposal rather
than being opened here.

What the rounds could not check, in their own words: the load measurements, which need the deleted
file and an artificial load none of them could recreate. Each verified the mechanism instead — that
nothing on the new path carries a per-test clock — and two of them re-proved both directions of the
negative read-only, by driving the real configuration through ESLint with the guard removed in
memory rather than editing the file. Round 2 also proved the exit path end to end, including running
`scripts/ci_gate.py` with the frontend lane marked failed to confirm the required check goes red.

## Why this is its own ticket

`docs/PROJECT_MANAGEMENT.md` § 8, **foreign subtree**: found while closing DB-01, a `DB-` package,
which must not edit `frontend/`. DB-01 closes one ticket and opens this one, so the net rule holds.
