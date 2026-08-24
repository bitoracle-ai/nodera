---
id: WEB-03
title: Frontend toolchain majors that need a migration, not a merge
priority: P1
status: closed
effort: ~1 d
depends_on: []
created: 2026-08-23
updated: 2026-08-24
closed: 2026-08-24
---

# WEB-03 · Frontend toolchain majors that need a migration, not a merge

**Priority:** P1
**Effort:** ~1 d
**Skills:** `critical-invariants.md` + `frontend-react.md` + `design-system.md`

## Motivation / context

This ticket was written to *park* two Dependabot pull requests that could not be merged as proposed.
They were merged anyway — [#17](https://github.com/bitoracle-ai/nodera/pull/17) and
[#14](https://github.com/bitoracle-ai/nodera/pull/14), by the owner, deliberately — so the ticket's
job changed from holding a queue to making `main` green. The decision on the record is **migrate
both halves forward, do not revert**.

## Current state (honest)

`main` fails at `Frontend (React)`, and `CI Gate` with it. Backend, database, repository checks and
the secret scan are green.

**Two claims in the previous version of this ticket were wrong, and are corrected here rather than
quietly dropped:**

1. It said the lockfile resolved `typescript-eslint` 8.46.2, whose peer range excludes eslint 10, so
   merging #17 "would pair eslint 10 with a resolver that does not support it". On `main` the
   lockfile resolves **8.67.0** — `git show HEAD:frontend/yarn.lock` — whose range is
   `^8.57.0 || ^9.0.0 || ^10.0.0`. The constraint was already satisfied and the work was zero.
2. Its § To decide recommended holding #14 "until WEB-01 lands, so the token layer is migrated
   once". There is no token layer. `frontend/src/index.css` was three `@tailwind` lines, defining
   zero semantic tokens, and nothing in `frontend/src/` referenced one. The sequencing argument
   rested on work that has not started — WEB-01 carries the token layer, as
   [`6e48469`](https://github.com/bitoracle-ai/nodera/commit/6e48469) says outright.

**#14 breaks the lane first**, and it is one line: `package.json` moved `tailwindcss` to `^4.3.3`
and carried **no lockfile change**, so `yarn install --frozen-lockfile` rejects the tree before any
script runs. It is not the only break, though — #17 merged before it (`7b8c41b` before `0396642`)
and reddened the lane on its own at `yarn lint`, which the previous version of this ticket recorded
against run 32663872559. Both halves need a source change; #14's simply fails earlier.

## Approach

1. **ESLint.** `eslint.config.js` consumed `reactHooks.configs['recommended-latest']`, which is
   eslintrc-shaped under v7; the flat export moved to `reactHooks.configs.flat[…]`. One line, plus a
   docstring that still said "ESLint 9".
2. **Tailwind.** `@tailwindcss/postcss` as the PostCSS plugin, `@import 'tailwindcss'` in place of
   the three directives, the `xs: 375px` breakpoint ported to `@theme`, `tailwind.config.js` deleted,
   `autoprefixer` dropped — v4 prefixes through Lightning CSS.
3. **Prove the rules still fire**, because a green `yarn lint` after a linter major proves the config
   loads and nothing more.

## ⚠️ Decisions taken, on the record

- **`--breakpoint-xs` is `23.4375rem`, not `375px`.** v3's breakpoints were px; v4's defaults are
  rem, and a lone px breakpoint in a rem scale stops tracking the root font size — at a 20 px root,
  `sm:` would fire at 800 CSS px while `xs:` still fired at 375. Identical at the default root, and
  it keeps the accessibility floor's behaviour consistent across the scale.
- **No v3 compatibility shim for the preflight changes.** v4 changes the default border colour to
  `currentColor`, `::placeholder` to the current colour at reduced opacity, `button` to
  `cursor: default`, and `dialog` padding. `App.tsx` contains no border, button, input, dialog or
  placeholder, so all four cost nothing today. The shim exists to preserve v3 semantics for an
  existing UI; carrying it into a placeholder would mean WEB-01 builds its real components against
  v3 defaults and someone removes the shim later, against components that then depend on it.
- **Source scanning is declared, not automatic.** v4 replaces `content` globs with automatic
  detection: everything under the project that git does not ignore. That is wider than v3 was, and
  it reached the prose — `frontend/CLAUDE.md` line 31 forbids `bg-blue-600` in a sentence, and v4
  compiled `.bg-blue-600` into the production bundle from that sentence, along with `.ring` and
  `.visible` from `AGENTS.md`. Three selectors of dead CSS is trivial; a rule that materialises the
  thing it forbids is not, and the two entry files invariant 12 protects from casual edits had
  quietly become build inputs. `@import 'tailwindcss' source(none)` plus the two `@source` globs
  restores exactly what `content` declared. Bundle: 8.87 kB → 7.11 kB.
- **Browser floor is now roughly Safari 16.4 / Chrome 111 / Firefox 128.** v4's output uses
  `@property`, `color-mix()` and `oklch()`; v3's used none of them. The repository declares no
  `browserslist` and no support target, so this is a floor being *adopted*, not a constraint being
  violated. Recorded because a self-hosted tool's operators do not all run current browsers, and the
  first person to discover this should not discover it from a bug report. No `browserslist` was
  added: nothing in this build reads one, and a config nothing enforces is a claim, not a control.
- **react-hooks v7 enables 17 rules where v5 enabled 2** — 14 at `error`, 3 at `warn`, and
  `--max-warnings 0` makes the warnings fatal too. Free today because the codebase has no hooks.
  WEB-01 is the package that first meets them; this is here so it is not ambushed by
  `react-hooks/purity`, `set-state-in-effect` and the thirteen other React Compiler rules — 15 of
  the 17 are Compiler rules, the classic two being `rules-of-hooks` and `exhaustive-deps`.
- **WEB-01's `darkMode` criterion was rewritten**, not deleted: `darkMode` is a v3 config option and
  v4 has no config file. The mechanism is now `@custom-variant dark (&:where(.dark, .dark *))` plus
  `@theme inline`. Today's behaviour is unchanged — `prefers-color-scheme` in both versions.

## Acceptance criteria

- [x] `yarn install --frozen-lockfile` succeeds — the lockfile agrees with `package.json`.
- [x] `frontend/eslint.config.js` loads under eslint 10 with eslint-plugin-react-hooks 7.
- [x] A paired-negative exists for invariant F1 under the new linter: a direct `fetch` in a
      component is red, and the same call inside `src/api/` is not. **Committed as a test**, not
      run by hand — a lint rule that stops firing does so silently, and a linter major is exactly
      when that happens.
- [x] The `xs` breakpoint survives the move to `@theme`, **demonstrated** by a generated
      `min-width: 23.4375rem` media query rather than by the config file looking right.
- [x] The `content` globs survive as `@source`, demonstrated by `index.html` still being scanned
      and by no class reaching the bundle from a Markdown file.
- [x] No utility class name in `frontend/src/` changed.
- [x] `make check-frontend` green.
- [x] The two false claims above are corrected in this ticket, not silently removed.
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.

## Affected files

- `frontend/eslint.config.js` — the react-hooks flat export and the version in the docstring.
- `frontend/src/invariants.test.ts` — new; the F1 paired negative, running ESLint's Node API over
  two fixtures.
- `frontend/src/index.css` — `@import`, the two `@source` globs, and the `@theme` breakpoint.
- `frontend/postcss.config.js` — `@tailwindcss/postcss`.
- `frontend/tailwind.config.js` — deleted; v4 does not read it.
- `frontend/package.json`, `frontend/yarn.lock` — `@tailwindcss/postcss` in, `autoprefixer` out.
- `tickets/open/WEB-01.md` — the `darkMode` criterion, which named a v3-only option.

## Verification

`make check-frontend` for the lane. For the rules, the paired negative. For the breakpoint, a
temporary `xs:` utility added to `App.tsx`, built, and grepped out of the emitted CSS — then removed.

## Why this ticket exists

§ 8 asks which criterion carried it: **live risk**. `main` is red, and nothing else in the backlog
makes it green.

Its predecessor ran a net +1 against § 8 and said so. Closing this settles that overdraft: this
session creates none and closes it.

## Not in scope

[#25–#28](https://github.com/bitoracle-ai/nodera/pulls) are four further frontend Dependabot pull
requests. All four already carry a lockfile containing `tailwindcss 4.3.3`, so they should merge
cleanly once this lands, but each is its own assessment. One correction for whoever takes them:
`@vitejs/plugin-react@5.2.0`'s peer range already accepts vite 8, so it is not the obstacle on #27 —
`@vitest/mocker@3.2.7` excludes vite 8, and `"resolutions": { "vite": "^6.4.3" }` in `package.json`
pins it regardless and is not updated by that pull request.

## Review result

**APPROVED, 0 BLOCKING**, one independent sub-agent round against the staged diff. Seven
NON-BLOCKING, all fixed here rather than ticketed — every one sat in a file this package already had
open, which is § 8's fix-now case.

The reviewer did not take the lane's greenness on trust. It ran a clean-room install of the staged
`package.json` + `yarn.lock` in an empty directory (exit 0) and the same pair from `HEAD` (fails at
resolution), reproducing the red lane and its fix. It audited the lockfile diff — 37 packages added,
54 removed, and exactly four version changes, none of them an unrelated bump riding along. And it
diffed resolved CSS declarations against a real v3.4.19 clean-room build: line-heights, spacing and
the physical-to-logical property change all compute identically, the hex-to-oklch palette move
differs by at most 3/255 in one channel, and contrast is unchanged to two decimals (slate-900 on
white 17.85 → 17.83:1). That is what "no utility class name changed" needed to mean.

**The finding worth the review** was N1, and it was invisible from the diff. v4 replaces `content`
globs with automatic source detection, so deleting `tailwind.config.js` silently widened scanning
from two globs to every non-ignored file in `frontend/`. The reviewer found `.bg-blue-600`, `.ring`
and `.visible` in the production bundle and traced all three to **prose in `CLAUDE.md` and
`AGENTS.md`** — including the sentence at `frontend/CLAUDE.md:31` that forbids `bg-blue-600`. A rule
compiling the thing it forbids into the shipped stylesheet, and the two entry files invariant 12
protects turned into build inputs. Fixed with `source(none)` plus the two explicit `@source` globs;
bundle 8.87 kB → 7.11 kB, and the three selectors are gone.

The other six: the breakpoint unit (px in a rem scale stops tracking the root font size — now
`23.4375rem`, recorded as a decision rather than silently changed); a ported comment that said `xs:`
where its four siblings say `sm:`; an undercount of the React Compiler rules WEB-01 will meet
(thirteen, not eleven); a stale sentence in WEB-01 naming the Tailwind config this package deletes;
an overstated claim that #14 alone broke the lane, when #17 had reddened it first; and N2 — the F1
paired negative existed only as a manual reproduction.

N2 is now `frontend/src/invariants.test.ts`, and it is a control rather than a claim: with
`no-restricted-globals` renamed out of `eslint.config.js` both cases fail, and with it restored both
pass. That is the guarantee `skills/testing.md` asks for, and it runs on every `yarn test`, which is
the point — the rule this ticket exists to restore was lost silently by exactly this kind of major.

**Honest note:** the seven fixes were not re-reviewed. Each was verified by me directly — the bundle
grepped before and after for the three stray selectors, `index.html` probed to confirm the `@source`
glob still scans it, a temporary `xs:italic` built to `@media (min-width:23.4375rem)`, and the F1
test driven red and green against the live config. `make check-frontend` and `make check-repo` are
green after them. The backend and database lanes were not run: no JDK on this machine.
