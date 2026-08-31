---
id: DOC-02
title: Install the three org-wide conventions in the entry files
priority: P2
status: closed
effort: ~0.5 d
depends_on: []
created: 2026-08-31
updated: 2026-08-31
closed: 2026-08-31
note: Changes the closure protocol every other open package will run — hence P2 rather than P3.
---

# DOC-02 · Install the three org-wide conventions in the entry files

**Priority:** P2
**Effort:** ~0.5 d
**Skills:** `critical-invariants.md` (invariant #12 — entry files change only inside a work package)

## Motivation / context

Three conventions govern how work is finished in this repository, and none of them is written
down here. They have been followed anyway — every commit in the log already carries the `🤖`
marker — which is precisely the problem: a rule that is obeyed by habit rather than stated is a
rule the next contributor cannot find, and the first person to break it will be right to.

The priority is P2 rather than P3 because this changes the **last step of the closure protocol**,
which every other open package will run. It is not a feature improvement waiting behind the open
P1s; it is the definition of "finished" that those packages will be closed against.

## Current state (honest)

Verified by reading, not assumed:

| Convention | Where it should be | State |
|---|---|---|
| Commit when the work is done | `docs/PROJECT_MANAGEMENT.md` § 9 step 9, all three entry files | **Absent, and the opposite is written.** `AGENTS.md:42` says `git commit` is allowed "when the user asks for it"; `CLAUDE.md:60` says "`git commit` only when I ask"; § 9 step 9 says "Propose a commit message … Commit only when the user asks". |
| The `🤖` marker on agent-authored subjects | `CONTRIBUTING.md` § Commits and pull requests, the entry files | **Absent everywhere.** Followed in practice by every commit in the log; stated nowhere. |
| Comments minimal | a layer-2 document, the entry files | **Absent everywhere**, and the tree models the opposite — the migrations and several Kotlin files carry paragraph-length commentary. |

The `"done"` verb row is a nodera-specific difference worth naming: `AGENTS.md:92` and
`CLAUDE.md:108` both end at "views regenerated". There is no commit clause to reword — the step
has to be **added**, not rewritten.

## Approach

Layer 2 first, then the distillates, in the same change — [`../docs/INDEX.md`](../docs/INDEX.md)
§ Maintenance.

1. **`docs/PROJECT_MANAGEMENT.md`** — a new § 12 for committing, carrying the commit-on-completion
   rule, the `🤖` marker and the push rule; § 9 step 9 repointed at it so the closure protocol ends
   in a commit rather than in a proposal.
2. **`docs/AI_COLLABORATION.md`** § 1 — the comments rule, beside "write English into the
   repository", because both are rules about what goes into the artefact and both bind humans and
   assistants equally.
3. **`CONTRIBUTING.md`** — the contributor-facing half: the `🤖` marker with the meaning it carries
   for a reader of a public history, and a short section on comments beside the language rule.
4. **The three root entry files** — `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md` —
   distillates of all three, each standing alone.

## ⚠️ To decide before starting

**Does the public wording need to differ from the internal rule?** Decided: **no**. All three are
process rules about how this repository is worked; none names a person, a system, a path or a
policy outside it, so adopting the meaning unchanged costs nothing and keeps four repositories
saying one thing. Two adaptations are made for the audience rather than for the content:

- The `🤖` marker is given its **outward** meaning as well as its authoring rule. In a public
  repository it is how a reader tells agent-authored history from human-authored history, and that
  is worth more to an outside contributor than the instruction to type it.
- The comments rule states that **the existing tree is not the standard to imitate**. Without that,
  the surrounding evidence beats the rule — a contributor reads the migrations and concludes that
  dense commentary is the house style.

Not a structural decision, so it stays here rather than becoming an ADR.

## Acceptance criteria

- [x] Commit-on-completion is present with the same meaning as the org rule, **all four conditions
      in all five files that carry it**. Round 1 caught two files carrying only three: dropping
      "never if the user said not to" removes the clause that keeps a user's refusal binding on an
      agent that otherwise commits by itself, which is a different rule rather than a shorter one.
- [x] The `🤖` marker is stated for commit subjects and pull-request titles an agent authors, with
      its outward meaning for a reader of a public history.
- [x] The comments rule is present and says in as many words that the dense comments already in the
      tree are not the standard to imitate.
- [x] The push rule is unchanged and unmistakable — verified word for word in all five carriers and
      in both `"push" / "deploy"` verb rows after every edit, because the fixes were made directly
      beside it.
- [x] The `"done"` row in both root adapters ends in the commit.
- [x] Layer 2 changed first; every adapter statement traces to `docs/PROJECT_MANAGEMENT.md` § 12 or
      `docs/AI_COLLABORATION.md` § 1. No adapter invents a rule.
- [x] `python scripts/lint_adapters.py` green; `make PY=py check` green.
- [x] No comment sweep — the diff contains no source file.
- [x] Independent review: 0 BLOCKING. Two rounds; round 1 CHANGES REQUIRED, round 2 APPROVED.

## Affected files

- `docs/PROJECT_MANAGEMENT.md`, `docs/AI_COLLABORATION.md` — layer 2.
- `CONTRIBUTING.md` — the contributor-facing half.
- `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md` — the adapters.

## Verification

`python scripts/lint_adapters.py` · `python scripts/lint_docs_index.py` ·
`python scripts/lint_language.py` · `make check`. The semantic half — that the distillates say what
layer 2 says — is what the phase-4 review is for; no linter sees it, and
[`../scripts/lint_adapters.py`](../scripts/lint_adapters.py) says so in its own docstring.

## Review result

**2026-08-31 · Two rounds, one independent context. Round 1 CHANGES REQUIRED (2 BLOCKING,
2 NON-BLOCKING); round 2 APPROVED (0 BLOCKING, 0 NON-BLOCKING).**

**Both BLOCKING findings were the same defect in two files, and it was the right catch.**
`.github/copilot-instructions.md` and `CONTRIBUTING.md` carried three of the four conditions that
bound the unasked commit, dropping *"never if the user said not to"*. Three of four is not a
shorter version of the rule; it is a different rule, and the clause omitted is exactly the one that
keeps a user's refusal binding on an agent that otherwise commits by itself. Fixed in both. The
non-blocking pair: the commit bullet in the two root adapters carried no pointer to its layer-2
source while the bullet below it did, and the date correction described below rode along in the
same change.

**Two further defects were found before the reviewer saw them, and both were introduced by the
round-1 fixes.** The "Comments minimal" bullet in `CLAUDE.md` and `AGENTS.md` cited
`docs/PROJECT_MANAGEMENT.md` § 12 — the *committing* section, which says nothing about comments —
because the pointer had been copied from the bullet above it. And the B1 fix left the copilot
adapter wrapping mid-phrase. Both corrected and both put in front of round 2, which verified the
rewrap word for word against the pre-rewrap text for dropped or duplicated words.

Round 2 confirmed rather than assumed: all four conditions in all five files; the push rule
byte-identical to round 1 in all five carriers and both `"commit"` table rows; the section pointers
now accurate; and no adapter stating a rule layer 2 does not.

### One thing the review could not cover, and who closed it

The reviewer could not run `make PY=py check` — make is outside a reviewer's limits here — and ran
the five underlying gates instead, green before and after the follow-up edits. The `make` target
itself was run by the implementer and is recorded above.

Round 1 also left "does any file outside the diff still carry the superseded wording?" unverified,
as out of scope for a diff-only review. Closed afterwards by sweeping the tracked tree: the only
surviving copy of *"commit only when the user asks"* is inside this ticket's own **Current state
(honest)** table, where it is quoted as the before-state and belongs.

### Why the date correction is in this commit

`tickets/closed/DB-01.md` recorded `closed: 2026-08-30`; the closure actually landed on 2026-08-31,
the session having crossed midnight. Corrected here rather than in its own cycle. It is a separate
logical change and the reviewer said so, but `tickets/INDEX.md` carries both the corrected date in
its hand-written head and this ticket's row in its generated table, so splitting them would mean
regenerating the index into a state that never existed and in which `check_tickets.py` would be
inconsistent. One commit, named.
