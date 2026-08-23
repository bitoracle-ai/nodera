---
id: WEB-03
title: Frontend toolchain majors that need a migration, not a merge
priority: P3
status: open
effort: ~1 d
depends_on: []
created: 2026-08-23
updated: 2026-08-23
---

# WEB-03 · Frontend toolchain majors that need a migration, not a merge

**Priority:** P3
**Effort:** ~1 d
**Skills:** `critical-invariants.md` + `frontend-react.md` + `design-system.md`

## Motivation / context

Two Dependabot pull requests cannot be merged as proposed. Neither is a version-number change:
each needs a source change in this repository that Dependabot cannot make, and each would leave
`main` red if merged. They are parked here rather than left in the queue looking mergeable.

Both are open on GitHub — [#17](https://github.com/bitoracle-ai/nodera/pull/17) and
[#14](https://github.com/bitoracle-ai/nodera/pull/14) — and stay open. This ticket carries the
diagnosis; the pull requests carry the diffs.

## Current state (honest)

**#17 — eslint 9.39.5 → 10.8.1 with eslint-plugin-react-hooks 5.2.0 → 7.1.1.** The frontend lane
fails at `yarn lint`, and the error is exact
([run 32663872559](https://github.com/bitoracle-ai/nodera/actions/runs/32663872559)):

```
ESLint: 10.9.0
A config object has a "plugins" key defined as an array of strings.
Flat config requires "plugins" to be an object
```

`frontend/eslint.config.js` consumes `reactHooks.configs['recommended-latest']`. Under v5.2 that
export is a flat config; under v7 it is eslintrc-shaped, and flat config rejects it. Separately,
`typescript-eslint` is pinned `^8.31.0` and the lockfile resolves 8.46.2, whose peer range is
`eslint: ^8.57.0 || ^9.0.0` — it does not admit eslint 10. Version 8.67.0 does
(`^8.57.0 || ^9.0.0 || ^10.0.0`) and the `^8.31.0` range already allows it, but this pull request
does not move the lockfile entry, so merging it would pair eslint 10 with a resolver that does not
support it.

**#14 — tailwindcss 3.4.19 → 4.3.3.** Three source files still speak v3, and Dependabot changed
none of them:

| File | Today | v4 requires |
|---|---|---|
| `frontend/postcss.config.js` | `plugins: { tailwindcss: {} }` | the separate `@tailwindcss/postcss` package, which is not in `package.json` |
| `frontend/src/index.css` | `@tailwind base/components/utilities` | `@import "tailwindcss"` |
| `frontend/tailwind.config.js` | JS config, incl. the `xs: 375px` screen | CSS-first `@theme` |

The config file is not the hard part. The design system is: two themes, semantic colour tokens that
must have a value in both ([`../skills/design-system.md`](../skills/design-system.md)), and the
`xs: 375px` breakpoint that encodes the mobile-first rule. Moving that token layer into `@theme` is
the work, and a green build would not prove it was done faithfully.

## Approach

1. **#17 first**, because it is bounded. Point `eslint.config.js` at v7's flat export, let
   `typescript-eslint` resolve to ≥ 8.67.0, then re-run `yarn lint`.
2. **Prove the rules still fire.** A green `yarn lint` after a linter major says the config loads,
   not that it still forbids anything. Invariant F1 — the `no-restricted-globals` ban on `fetch`
   outside `src/api/` — needs a paired-negative: a component calling `fetch` directly must be
   demonstrably red, and `src/api/` must stay exempt
   ([`../skills/testing.md`](../skills/testing.md)). The same applies to
   `react-refresh/only-export-components` and the `react-hooks` rules.
3. **#14 second, and separately.** Add `@tailwindcss/postcss`, move the directives, port the theme
   into `@theme`, then compare rendered output against both themes rather than trusting the build.

## ⚠️ To decide before starting

- **Is the tailwind v4 migration wanted at all right now?** WEB-01 and WEB-02 have not been built
  yet, so the design system has almost no surface to migrate — the frontend is still a placeholder.
  Recommendation: do #17 now and hold #14 until WEB-01 lands. Migrating a token layer before the
  views that consume it exist means doing it twice, and the second time against real components.

## Acceptance criteria

- [ ] `frontend/eslint.config.js` works under eslint 10 with eslint-plugin-react-hooks 7, and
      `typescript-eslint` resolves to a version whose peer range includes eslint 10.
- [ ] A paired-negative exists for invariant F1 under the new linter: a direct `fetch` in a
      component is red, and the same call inside `src/api/` is not.
- [ ] `make check` green.
- [ ] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.
- [ ] #17 merged or closed with a recorded reason; #14 likewise, or explicitly deferred here.

## Affected files

- `frontend/eslint.config.js` — the react-hooks flat-config export.
- `frontend/package.json`, `frontend/yarn.lock` — the resolved `typescript-eslint`.
- `frontend/postcss.config.js`, `frontend/src/index.css`, `frontend/tailwind.config.js` — only if
  #14 is taken now rather than deferred.

## Verification

`yarn lint` for the load, and the paired-negative for the rules. For #14, a rendered comparison in
both themes — `yarn build` succeeding proves the pipeline ran, not that a token survived it.

## Why this ticket exists

§ 8 asks which criterion carried a ticket, so: **structural decision** for #14 — moving the design
system's token layer to CSS-first `@theme` is an owner's call, not a contributor's edit — and
**external dependency** for #17, whose resolution waited on `typescript-eslint` publishing eslint 10
support.

Stated plainly rather than buried: this session closed one ticket ([CI-01](../closed/CI-01.md)) and
created two, this one included, so it runs a net +1 against the rule in § 8. The alternative was to
merge two pull requests that turn `main` red, or to leave them in the queue looking mergeable. Both
are worse than an honest overdraft.
