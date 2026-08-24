---
id: WEB-04
title: Vite 8 needs a vitest migration, not a merge
priority: P2
status: closed
effort: ~1 d
depends_on: []
created: 2026-08-24
updated: 2026-08-24
closed: 2026-08-24
---

# WEB-04 · Vite 8 needs a vitest migration, not a merge

**Priority:** P2
**Effort:** ~1 d
**Skills:** `critical-invariants.md` + `frontend-react.md`

## Motivation / context

This ticket was written to **park** [#27](https://github.com/bitoracle-ai/nodera/pull/27). The
decision then changed: the CEO's instruction is to take every open update, and to do the
accompanying work rather than defer it, because the project is young enough that the surface a
major would force back through verification is still small. So the ticket did the migration instead
of holding a queue — the same reversal WEB-03 went through, and for the same reason.

What made #27 unmergeable as proposed is unchanged, and is recorded below rather than deleted: it
did not upgrade Vite, it added a second one, and no lane in this repository could see that.

**§ 8 apparatus, kept because the reasons still need checking rather than trusting.** The criterion
that carried this as a ticket was *Foreign subtree*: the package that found the defect had
`.github/workflows/` and `docs/` open, and rewriting `frontend/package.json` and `frontend/yarn.lock`
from there would have been scope creep with a rationale. It also carried a *structural decision* —
whether to keep the `resolutions` pin at all — which § "What was done" settles. On § 8's depth
clause, this is the second frontend-toolchain ticket in two days after [WEB-03](WEB-03.md), and the
reason WEB-03's rule was not simply widened is that its decision was "migrate forward the majors
that are **already in `main`**", taken to make `main` green; #27 was not in `main` and reddened
nothing, so absorbing it would have meant rewriting a closure criterion rather than extending one.
**Budget: net 0** — this session created this ticket and closed it. The earlier draft recorded a +1
overdraw; the instruction to do the work rather than park it is what removed it.

## Current state (honest) — as found, before this package

`main` resolved exactly one Vite: enumerating every `*/vite/package.json` under an installed
`frontend/node_modules` on `main` returned one file, version **6.4.3**.

Under #27's lockfile — checked out into a fresh worktree, `yarn install --frozen-lockfile` into an
empty `node_modules` — the same enumeration returned **five copies across two majors**:

| Path in `node_modules` | Version |
|---|---|
| `vite` | **8.2.2** |
| `vitest/node_modules/vite` | 6.4.3 |
| `vite-node/node_modules/vite` | 6.4.3 |
| `@vitest/mocker/node_modules/vite` | 6.4.3 |
| `@vitejs/plugin-react/node_modules/vite` | 6.4.3 |

The cause is a hard dependency, not a peer warning. `vitest@3.2.7` declares
`vite "^5.0.0 || ^6.0.0 || ^7.0.0-0"` in its `dependencies` — a range that excludes 8 — so
resolution gives it its own nested copy. A `createRequire` rooted at `vitest/package.json` resolved
`vite` to **6.4.3** while the top-level `vite` was 8.2.2.

The consequence was that `yarn build` and `yarn dev` would have run **Vite 8** while `yarn test`,
`yarn test:coverage` and the per-file coverage gate ran the whole suite on **Vite 6**. The green
pipeline on #27 proved that Vite 6 still passed the tests and that Vite 8 could emit a bundle. It
did not prove the two agreed, and no lane here is capable of noticing that it is being asked two
different questions.

`frontend/package.json` carried `"resolutions": { "vite": "^6.4.3" }`, added in
[OPS-01](OPS-01.md) § 9.4 so that "`vitest` does not bring a second Vite major whose `Plugin` type
is not assignable to the declared one". **#27 did not touch it.** Yarn 1's `resolutions` does not
override a top-level direct dependency, so under #27 the pin constrained only the transitive copies
and stopped covering the thing it was written for — while still reading, to anyone who opened the
file, as though Vite were held at 6.

One correction to [WEB-03](WEB-03.md) § Not in scope, which named `@vitest/mocker@3.2.7` as what
excludes Vite 8: mocker's `vite` peer is declared `peerDependenciesMeta: { vite: { optional: true } }`,
and Yarn 1 does not enforce peer ranges in any case, so it constrained nothing. The blocker was
`vitest@3.2.7`'s `dependencies` entry, which Yarn must satisfy. WEB-03 was right about the other
half — `@vitejs/plugin-react@5.2.0` already accepts Vite 8, so the plugin was not the obstacle.

For [#28](https://github.com/bitoracle-ai/nodera/pull/28): `react-router@8.3.0` declares
`engines.node ">=22.22.0"` and peers `react`/`react-dom` `">=19.2.7"`. The repository declared
`.nvmrc` **22.20.0**, `engines.node ">=22.12"` and `react`/`react-dom` `"^19.1.0"` — three floors
below what the package requires. The peers were latent: Yarn 1 does not enforce peer ranges, and the
lockfile happened to resolve react 19.2.8. **`engines` was not latent.** Yarn 1 refuses the install
outright on an `engines.node` mismatch rather than warning:

```
error react-router@8.3.0: The engine "node" is incompatible with this module. Expected version ">=22.22.0". Got "20.20.0"
error Found incompatible module.
```

That is a real paste, from yarn 1.22.22 against the published package — but on Node 20.20.0, the
Node the machine that ran it had, not `.nvmrc`'s. Nobody ran it on 22.20.0 and this ticket does not
claim otherwise; 22.20.0 fails the same `>=22.22.0` comparison, so only the version in the last
field would differ.

Yarn aborts before linking anything, and `yarn install --frozen-lockfile` runs from `make frontend`
(Makefile:65), `make check-frontend` (:132) and `make test` (:138) — so it is also reached by
`make dev`, whose last line is `$(MAKE) -j2 backend frontend` (:39), and by `make check`, which
depends on `check-frontend`. That is every command a contributor starts with. Merging #28 alone
would have stopped all of them for anyone following the repository's own `.nvmrc`, while CI stayed
green because `NODE_VERSION: "22"` resolved whatever 22.x the runner happened to carry.

## What was done

1. **vitest 3 → 4.1.11**, with `@vitest/coverage-v8` alongside it. `vitest@4.1.11` declares
   `vite: "^6.0.0 || ^7.0.0 || ^8.0.0"` as both a dependency and a peer, which is what makes a
   single Vite possible.
2. **`vite` 6.4.3 → 8.2.2**, and `resolutions.vite` moved to `^8.2.2` in the same edit rather than
   left contradicting it. The pin is kept, not dropped: OPS-01's reason has not expired. It is not
   a tripwire, though — a Yarn 1 resolution rewrites a transitive version *silently*, as § "Current
   state" describes. What it buys is that the rewrite lands on the major the repository actually
   builds with, and that a reader of `package.json` is told which one that is.
3. **The three floors #28 needs:** `.nvmrc` 22.20.0 → **22.23.2** (current 22.x LTS),
   `engines.node` `">=22.12"` → `">=22.22.0"`, `react`/`react-dom` `^19.1.0` → `^19.2.7`.
4. **CI stops guessing the Node version.** Both workflows now pass `node-version-file: .nvmrc` to
   `actions/setup-node` instead of `"22"`, and `ci.yml`'s now-unused `NODE_VERSION` env var is
   gone. The runner's floor was being met by luck; it is now read from `.nvmrc`. That binds the
   runner only — nothing forces a contributor onto that Node — so `CONTRIBUTING.md` and `README.md`
   now name the floor instead of "Node 22", and the `Dockerfile` pins `node:22.23-alpine` rather
   than a floating major that may be cached below it.
5. **The remaining bumps carried in the same change**, so the lockfile is regenerated once:
   `zod` 3.25.76 → 4.4.3, `react-router` 7.18.2 → 8.3.0, `react-hook-form` 7.85 → 7.86,
   `@testing-library/user-event` 14.6.5 → 14.6.6.

## Acceptance criteria

- [x] A clean `yarn install` into an empty `frontend/node_modules` yields **exactly one**
      `vite/package.json`, and it is a Vite 8. Enumerating `*/vite/package.json` after
      `rm -rf node_modules && yarn install` returns a single line: `8.2.2  <-  vite/package.json`.
- [x] `vitest`'s require context resolves that same copy, shown rather than asserted. A
      `createRequire` rooted at each of `vitest`, `@vitejs/plugin-react` and `@vitest/coverage-v8`
      resolves `vite` to **8.2.2**.
- [x] `frontend/package.json` contains no version range naming a major the repository does not use,
      `resolutions` included.
- [x] `.nvmrc`, `engines.node` and the `react`/`react-dom` ranges are all at or above what
      `react-router@8` declares, and the workflows read `.nvmrc` rather than agreeing with it by
      coincidence.
- [x] `yarn typecheck`, `yarn lint`, `yarn test:coverage` and `yarn build` pass, and the coverage
      gate still measures every file under `src/` and `scripts/` that is not explicitly excluded —
      not only the ones a test imports. 17 tests in 4 files. Two paired negatives, both run: raising `statements` to 99 fails
      with `ERROR: Coverage for statements (85.45%) … for scripts/generate-zod.mjs` and exit 1,
      naming one file rather than the aggregate, so `perFile` bites; and dropping an untested
      `src/ZzzUntested.tsx` into the tree fails with four errors naming it at 0 %, so the
      untested-file sweep bites. The second one is the regression this package shipped and then
      fixed — see § Verification.
- [x] `yarn api:generate` produces no diff under zod 4 — the drift check CI runs.
- [x] #26, #27, #28 and #29 are superseded in content: every version they propose is in this
      branch's `package.json` and `yarn.lock`, and #27's is there in the only form that resolves a
      single Vite. **Pressing the button is not this session's to do** — merging or closing a pull
      request was denied by its permission system, and it is a two-click owner action rather than a
      work package, so it is recorded here and in `tickets/INDEX.md` instead of ticketed. § 8 asks
      for a recorded decision over a ticket nobody runs.
- [x] `make PY=py check-repo` green.
- [x] Independent review (phase 4, run in a sub-agent): **four rounds run, every finding of every
      round fixed.** No round has returned 0 BLOCKING on first reading — rounds 3 and 4 each found
      defects, and three across the four were introduced by a previous round's fix. § Review result
      names which rounds saw what, and states plainly that round 4's own fixes were not re-reviewed.
      Ticked for the work; read that section before trusting the record.

## Affected files

- `frontend/package.json` — `vite`, `vitest`, `@vitest/coverage-v8`, `react`, `react-dom`,
  `react-router`, `react-hook-form`, `@testing-library/user-event`, `zod`, `engines.node`,
  `resolutions`.
- `frontend/yarn.lock` — regenerated.
- `frontend/vite.config.ts` — `coverage.include`, without which vitest 4 stops measuring untested
  files; `coverage.exclude` narrowed to the entries that still bite under it.
- `.nvmrc` — the Node floor.
- `.github/workflows/ci.yml`, `.github/workflows/release.yml` — `node-version-file: .nvmrc`.
- `Dockerfile` — `node:22.23-alpine`, since `node:22-alpine` may be cached below the new floor.
- `CONTRIBUTING.md`, `README.md` — both said "Node 22", which this package's floor makes wrong.
- `CHANGELOG.md`, `docs/ci.md`, `tickets/INDEX.md`, `REVIEW_REPORT.md` — the record.

## Verification

The single-Vite criterion is the one that matters and the one CI cannot express, because a green
pipeline is exactly what a two-major tree produces. Delete `frontend/node_modules`, run
`yarn install`, then enumerate every `*/vite/package.json` under it and print each version. One line
of output, or the change is not done.

**The coverage gate is where this package went wrong, and the record should say so plainly.** The
first version of this migration left `vite.config.ts` untouched, on the reasoning that
`coverage/lcov.info` still carried `src/App.tsx` and `src/main.tsx`, so nothing had narrowed. That
reasoning was wrong. Vitest 3 swept untested files into the report via `coverage.all`, which
defaulted to true; **vitest 4 removed `all`** and gates the sweep on `coverage.include`, which has
no default at all — `coverageConfigDefaults` in 4.1.11 has no `include` key. This repository never
set one. So under vitest 4 the gate measured only files a test already imports: `App.tsx` and
`main.tsx` survived because the tests import them, which is exactly why the lcov check looked
reassuring and proved nothing.

Demonstrated rather than argued: dropping a five-line `src/ZzzUntested.tsx` with no test into the
tree left it **absent from `coverage/lcov.info` entirely** and `yarn test:coverage` exited 0. Under
the repository's own rule — a test beside every unit, gated per file at 80 % — that file should have
failed at 0 %. `coverage.include: ['src/**', 'scripts/**']` restores it: the same file now produces
four errors naming it, and exit 1. WEB-01 and WEB-02 are the next two packages and add precisely
such files, so this would have gone unnoticed for exactly as long as it took to matter.

The text reporter separately stops printing rows for files at 100 %, which makes the table look
narrower than the measurement is. That part is cosmetic — but it is what made the regression look
fine, so it is worth knowing when reading a vitest 4 coverage table.

Vite 8 changes its own output: the production bundle moves from 195.46 kB to **191.31 kB** and both
asset hashes change. That is the bundler major, not a source change — no file under `frontend/src/`
was touched by this package.

## Review result

**Four independent sub-agent rounds. Which round saw which content matters, so it is stated rather
than merged into one verdict.**

**Rounds 1 and 2 did not see this migration.** They reviewed the earlier half of the branch — the
Gradle `cache-provider` change and the cosign corrections — and are recorded here because the branch
carries both. Round 1: **CHANGES REQUIRED, 3 BLOCKING, 5 NON-BLOCKING**; round 2: **APPROVED, 0
BLOCKING, 6 NON-BLOCKING**. Every BLOCKING finding was a false claim in prose, which no gate here can
see, and two of the three sat in the cosign documentation. Both factual ones were reproduced before
being accepted: that Yarn 1 *aborts* on an `engines` mismatch rather than warning — a synthetic
dependency declaring `node: ">=99.0.0"` gives `error Found incompatible module.` and writes no
`node_modules` — and that cosign 2.6.0 already reads the 3.x bundle format when passed
`--new-bundle-format`, falsifying a flat claim that no cosign 2.x could read it. Round 2's most
useful finding was a false claim that a *round-1 fix* had introduced.

**Round 3 was the first to see the migration, and it should have been.** It returned **CHANGES
REQUIRED, 4 BLOCKING, 6 NON-BLOCKING** — all fixed here. The finding that justifies the whole
practice is the coverage regression now narrated in § Verification: that section's first version
claimed the per-file gate's scope was unchanged, and it was not. A reviewer who had accepted that
sentence would have approved a silently weakened check. Round 3 also caught
that this section had been written to describe rounds 1 and 2 as though they had reviewed the
migration — a transplanted verdict, which is the same defect as a false gate claim; that this
section now distinguishes them is its doing. It further caught that raising `engines.node` to
`>=22.22.0` while `CONTRIBUTING.md` and `README.md` still said "Node 22" moved the yarn abort from
the runner onto contributors on 22.12–22.21 — the exact failure this ticket exists to prevent,
relocated — and that closing this ticket with an unticked acceptance box would have made the
closure gate advisory.

**Round 4** returned **CHANGES REQUIRED, 1 BLOCKING, 4 NON-BLOCKING**, all fixed here. It confirmed
every round-3 fix as correct rather than merely different — including, by measurement, that the
narrowed `coverage.exclude` drops nothing that bites, with `frontend/dist`, `frontend/coverage` and
`frontend/node_modules` all present on disk during the run and none appearing in the report. Its
BLOCKING finding was that round 3's B2 fix had been applied to this ticket and **not** to its
sibling in `tickets/INDEX.md`, which still presented rounds 1 and 2 as this migration's review —
the more-read of the two files, since `CLAUDE.md` puts the index ahead of the ticket in every
session's reading order. A corrected claim has siblings; that one was missed.

**Round 5 has not run.** Round 4's findings were fixed after it reported, so the final text of this
ticket, of `tickets/INDEX.md`, of `frontend/vite.config.ts`'s comments and of `CHANGELOG.md` carries
one author's check rather than two. Every one of those fixes is prose or a comment; no code, no
configuration value and no dependency version changed after round 4 read them, and the gates were
re-run green afterwards. That is the honest state of this criterion, recorded rather than rounded up.

Three further errors were caught by self-check between rounds 1 and 2, including a `make` target list
above and a quoted console paste that had been rendered across two lines when yarn emits one.

**What no review could settle**, carried into [OPS-02](../open/OPS-02.md) rather than lost: that a
cosign client older than 2.6.0 cannot read the signature *at all* is an inference from the changelog,
not a quoted statement. It errs safe — the cost of being wrong is publishing a higher minimum than
necessary, never an operator trusting a signature they cannot check — and OPS-02 now says to settle
it by trying an old client rather than by reasoning again.
