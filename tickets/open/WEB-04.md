---
id: WEB-04
title: Vite 8 needs a vitest migration, not a merge
priority: P2
status: open
effort: ~1 d
depends_on: []
created: 2026-08-24
updated: 2026-08-24
note: Parks #27. Also carries the react-router 8 floor bump that #28 needs.
---

# WEB-04 · Vite 8 needs a vitest migration, not a merge

**Priority:** P2
**Effort:** ~1 d
**Skills:** `critical-invariants.md` + `frontend-react.md`

## Motivation / context

[#27](https://github.com/bitoracle-ai/nodera/pull/27) bumps `vite` 6.4.3 → 8.2.2 and is green on
every lane. It is still not mergeable as proposed: it does not upgrade Vite, it *adds* a second
Vite. The gates cannot see that, which is why this is written down rather than left to whoever next
reads a green tick.

The same session's assessment of [#28](https://github.com/bitoracle-ai/nodera/pull/28) (react-router
7 → 8) found a related defect: three declared floors the bump makes untrue, one of which stops
`yarn install` dead for anyone on the Node `.nvmrc` names, while CI stays green on a runner that
happens to be newer. That fix is carried here too, because it is a `frontend/` change and the
package that found it was a CI and documentation package.

This is the second frontend-toolchain ticket in two days, after [WEB-03](../closed/WEB-03.md).
§ 8 asks why WEB-03's rule was not simply widened instead: WEB-03's decision was "migrate forward
the majors that are **already in `main`**", taken to make `main` green; #27 is not in `main` and
reddens nothing, so absorbing it would have meant rewriting a closure criterion rather than
extending one.

**Ticket test:** *Foreign subtree*. The package that found this had `.github/workflows/` and `docs/`
open; rewriting `frontend/package.json` and `frontend/yarn.lock` from there is scope creep with a
rationale. § "To decide before starting" also carries a *structural decision* about the
`resolutions` pin that needs an owner's answer.

**Budget note:** this session closed no ticket and creates this one, so it overdraws § 8's net rule
by one — the same +1 OPS-01 ran and recorded, and that WEB-03 then settled. Recorded here rather
than left to be noticed.

## Current state (honest)

`main` resolves exactly one Vite: enumerating every `*/vite/package.json` under an installed
`frontend/node_modules` on `main` returns one file, version **6.4.3**.

Under #27's lockfile — checked out into a fresh worktree, `yarn install --frozen-lockfile` into an
empty `node_modules` — the same enumeration returns **five copies across two majors**:

| Path in `node_modules` | Version |
|---|---|
| `vite` | **8.2.2** |
| `vitest/node_modules/vite` | 6.4.3 |
| `vite-node/node_modules/vite` | 6.4.3 |
| `@vitest/mocker/node_modules/vite` | 6.4.3 |
| `@vitejs/plugin-react/node_modules/vite` | 6.4.3 |

The cause is a hard dependency, not a peer warning. `vitest@3.2.7` declares
`vite "^5.0.0 || ^6.0.0 || ^7.0.0-0"` in its `dependencies` — a range that excludes 8 — so npm-style
resolution gives it its own nested copy. A `createRequire` rooted at `vitest/package.json` resolves
`vite` to **6.4.3**, while the top-level `vite` is 8.2.2.

The consequence is that `yarn build` and `yarn dev` would run **Vite 8** while `yarn test`,
`yarn test:coverage` and the per-file coverage gate run the whole suite — `App.test.tsx`,
`main.test.tsx`, `invariants.test.ts` and the F1 paired negative — on **Vite 6**. A green pipeline on
#27 proves that Vite 6 still passes the tests and that Vite 8 can emit a bundle. It does not prove
the two agree, and no lane in this repository is capable of noticing that it is being asked two
different questions.

`frontend/package.json` still carries `"resolutions": { "vite": "^6.4.3" }`, added in
[OPS-01](../closed/OPS-01.md) § 9.4 so that "`vitest` does not bring a second Vite major whose
`Plugin` type is not assignable to the declared one". **#27 does not touch it.** Yarn 1's
`resolutions` does not override a top-level direct dependency, so after #27 the pin constrains only
the transitive copies and stops covering the thing it was written for — while still reading, to
anyone who opens the file, as though Vite were held at 6.

One correction to [WEB-03](../closed/WEB-03.md) § Not in scope, which named
`@vitest/mocker@3.2.7` as what excludes Vite 8: mocker's `vite` peer is declared
`peerDependenciesMeta: { vite: { optional: true } }`, and Yarn 1 does not enforce peer ranges in
any case, so it constrains nothing. The blocker is `vitest@3.2.7`'s `dependencies` entry, which
Yarn must satisfy. WEB-03 was right about the other half — `@vitejs/plugin-react@5.2.0` already
accepts Vite 8, so the plugin is not the obstacle.

For #28: `react-router@8.3.0` declares `engines.node ">=22.22.0"` and peers
`react`/`react-dom` `">=19.2.7"`. This repository declares `.nvmrc` **22.20.0**,
`engines.node ">=22.12"` and `react`/`react-dom` `"^19.1.0"` — three floors below what the package
requires. The peers are latent: Yarn 1 does not enforce peer ranges, and the lockfile happens to
resolve react 19.2.8. **`engines` is not latent.** Yarn 1 refuses the install outright on an
`engines.node` mismatch rather than warning:

```
error react-router@8.3.0: The engine "node" is incompatible with this module. Expected version ">=22.22.0". Got "20.20.0"
error Found incompatible module.
```

That is a real paste, from yarn 1.22.22 against the published package — but on Node 20.20.0, the
Node the machine that ran it had, not `.nvmrc`'s. Nobody has run it on 22.20.0 and this ticket does
not claim otherwise; 22.20.0 fails the same `>=22.22.0` comparison, so only the version in the last
field would differ.

Yarn aborts before linking anything, and `yarn install --frozen-lockfile` runs from `make frontend`
(Makefile:65), `make check-frontend` (:132) and `make test` (:138) — so it is also reached by
`make dev`, whose last line is `$(MAKE) -j2 backend frontend` (:39), and by `make check`, which
depends on `check-frontend`. That is every command a contributor starts with. Once #28 lands, nobody
following this repository's own `.nvmrc` can install the frontend, run its tests, or start it. CI
stays green only because `NODE_VERSION: "22"` resolves whatever 22.x the runner happens to carry,
currently ≥ 22.22.0 — luck, not a floor. **#28 must not merge before the floors below are raised.**

## Approach

1. **Move vitest with Vite, in one change.** `vitest` 3 → 4 and `@vitest/coverage-v8` 3 → 4;
   vitest 4 declares `vite: ^6 || ^7 || ^8`. Confirm from the installed `package.json`, not from
   release notes. Then bump `vite` to 8.
2. **Settle the `resolutions` entry** per § "To decide" and make `package.json` say what is true.
3. **Prove single resolution rather than assume it.** After a clean `yarn install --frozen-lockfile`
   into an empty `node_modules`, `node_modules` must contain exactly one `vite/package.json`, and
   `vitest`'s own require context must resolve that same copy.
4. **Read vitest 4's own breaking changes** against `frontend/vite.config.ts` — the `test.coverage`
   block with `thresholds.perFile` and the replaced-not-merged `exclude` list is the part most
   likely to have moved.
5. **Raise the three floors #28 needs**, in the same package: `.nvmrc` to the Node major
   react-router 8 actually requires, `engines.node` to `>=22.22.0`, `react`/`react-dom` to
   `^19.2.7`. `NODE_VERSION` in both workflows should say the same thing rather than resolving to
   it by luck.

## ⚠️ To decide before starting

- **Keep the `resolutions` pin, or drop it.** Recommendation: **keep it and move it to `^8.2.2`.**
  OPS-01's reason for it has not expired — a second Vite major is exactly what #27 demonstrates can
  still happen — and a pin that names the current major is a gate that fails loudly when something
  drags in a different one. Dropping it makes the tree correct today and silent tomorrow. Whichever
  is chosen, the entry must not be left naming a major the repository no longer builds with.

## Acceptance criteria

- [ ] A clean `yarn install --frozen-lockfile` into an empty `frontend/node_modules` yields
      **exactly one** `vite/package.json`, and it is a Vite 8. The command and its output are
      recorded here — this is the criterion the pipeline cannot check for itself.
- [ ] `vitest`'s require context resolves that same copy, shown rather than asserted.
- [ ] `frontend/package.json` contains no version range naming a major the repository does not use,
      `resolutions` included.
- [ ] `.nvmrc`, `engines.node` and the `react`/`react-dom` ranges are all at or above what
      `react-router@8` declares, and `NODE_VERSION` in `ci.yml` and `release.yml` agrees with
      `.nvmrc`.
- [ ] `yarn typecheck`, `yarn lint`, `yarn test:coverage` and `yarn build` pass, and the coverage
      gate is still per-file.
- [ ] [#27](https://github.com/bitoracle-ai/nodera/pull/27) is closed or superseded, and
      [#28](https://github.com/bitoracle-ai/nodera/pull/28) is merged or superseded — neither is
      left open behind this ticket.
- [ ] `make check` green.
- [ ] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.

## Affected files

- `frontend/package.json` — `vite`, `vitest`, `@vitest/coverage-v8`, `react`, `react-dom`,
  `react-router`, `engines.node`, `resolutions`.
- `frontend/yarn.lock` — regenerated by the above.
- `frontend/vite.config.ts` — only if vitest 4 moved something the `test` block uses.
- `.nvmrc` — the Node floor.
- `.github/workflows/ci.yml`, `.github/workflows/release.yml` — `NODE_VERSION` / `node-version`.
- `CHANGELOG.md` — the toolchain move.

## Verification

The single-Vite criterion is the one that matters and the one CI cannot express: delete
`frontend/node_modules`, run `yarn install --frozen-lockfile`, then enumerate every
`*/vite/package.json` under it and print each version. One line of output, or the change is not
done. `make check` covers the rest.
