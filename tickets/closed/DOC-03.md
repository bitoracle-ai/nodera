---
id: DOC-03
title: Remove organisation-internal layout detail from the entry files
priority: P1
status: closed
effort: ~0.25 d
depends_on: []
created: 2026-08-31
updated: 2026-08-31
closed: 2026-08-31
note: Public-surface disclosure, already published. Removing it stops it being current; it does not un-publish it.
---

# DOC-03 · Remove organisation-internal layout detail from the entry files

**Priority:** P1
**Effort:** ~0.25 d
**Skills:** `critical-invariants.md` (invariant #12 — entry files change only inside a work package)

## Motivation / context

`CLAUDE.md` and `AGENTS.md` each open with a paragraph describing where the maintainer's clone sits
on disk. It names a private management repository, three private sibling repositories, and the
parent-directory structure that resolves between them.

Two separate problems, and the second is the one that makes this P1:

1. **It is false for almost everyone who reads it.** A contributor who clones this repository on its
   own has no parent checkout and no siblings. Nothing resolves via `../`. The first thing the entry
   file tells them about the layout is wrong, in the document every tool loads first.
2. **It is organisation-internal detail on a public surface.** This repository is public and MIT. The
   paragraph discloses the existence, names and relative arrangement of four private repositories.

P1 under `docs/PROJECT_MANAGEMENT.md` § 5 is a judgement, not an automatic fit — the ladder's P1 rows
are about security defects, data loss and broken first-class-actor capabilities, and this is none of
those. It is filed P1 on one ground only: a live disclosure on a published surface is the class of
thing that should not wait behind a backlog. **Effort is deliberately not part of that argument** —
§ 5's ladder is impact-based throughout, and "it is quick" is a reason to schedule something now, not
a reason to rank it. A reader who disagrees with the priority can check that reasoning here rather
than guess at it.

## Current state (honest)

`CLAUDE.md:12-13` and `AGENTS.md:13-14`, identical text in both:

> Local layout: this clone sits inside the `bitoracle-ai/hq` umbrella checkout — the hq management
> repo resolves via `../`, sibling project repos via `../oracleai`, `../webadmin`, `../studio`.

**It is already published.** This is a correction of what the repository currently says, not a
containment measure: the text is in the published history, and removing it from the working tree
does not remove it from there. That is understood and is not a reason to leave it standing.

**A sweep of the tracked tree found one further occurrence, which this package deliberately does not
touch** — see § Out of scope.

## Approach

1. Replace the paragraph in both files. **Replace rather than delete:** the paragraph answers a real
   question — where do the paths in this documentation resolve — and a contributor who reads nothing
   in its place is left with the same question and no answer. The replacement says the repository
   stands alone and its documentation paths are relative to its own root.
2. Both root adapters are rewritten in the same change. They are independent documents by design and
   neither may reference the other, so each carries its own copy in its own voice.
3. Re-run the sweep afterwards to confirm the class is closed in the files this package owns.

## Out of scope — reported, not fixed

`tickets/closed/OPS-03.md:64-67` names one of the private sibling repositories and describes its
production arrangements — that it has a host, a scheduled timer, an off-box backup repository, and
two named backup scripts. That is infrastructure detail about a different, private system, and it is
a heavier disclosure than the layout paragraph rather than a lighter one.

It is **not** fixed here, for two reasons. It is a second finding of a different kind, and the scope
of this package was set before it was known. And it sits in the body of a **closed** ticket, which is
a historical record: whether such a record is redacted, annotated or left as written is a decision
about how this project treats its own history, not a wording fix.

Tracked as [DOC-04](DOC-04.md) rather than left in this ticket's prose — a pending decision recorded
only in a ticket that is about to close is a decision that disappears when it closes.

## Acceptance criteria

- [x] Neither `CLAUDE.md` nor `AGENTS.md` names a private repository, an umbrella or parent checkout,
      or any parent-directory resolution. Sweep returns zero matches in both files — and neither file
      now contains a `../` of any kind.
- [x] Each carries a replacement that answers the question the old paragraph answered.
- [x] The replacement is true for a contributor who cloned this repository on its own — checked by
      the reviewer against the relative links actually present, not asserted.
- [x] Nothing else in either file changed. One hunk per file; the push rule and DOC-02's three
      conventions verified byte-identical to `HEAD`.
- [x] `python scripts/lint_adapters.py` green; `make PY=py check` green (exit 0).
- [x] The sweep is re-run and its result recorded below, including what it did **not** find.
- [x] Independent review: 0 BLOCKING findings.

## Affected files

- `CLAUDE.md`, `AGENTS.md`.

## Verification

`python scripts/lint_adapters.py` · `make check`, plus the sweep in the review result. The sweep is
the part worth keeping: it is what turns "we fixed the line we knew about" into a statement about
the class.

## Review result

**2026-08-31 · APPROVED, 0 BLOCKING, 2 NON-BLOCKING.** Both non-blocking findings were fixed in the
same session; neither was dropped or deferred.

**N1 — the priority argument leaned on effort.** The ticket justified P1 partly with "the fix is a
quarter of a day". § 5's ladder is impact-based throughout and effort appears nowhere in it, so that
was a soft spot rather than an argument. Rewritten to rest on one ground only, with the point made
explicitly that speed is a reason to schedule something now and not a reason to rank it.

**N2 — the pending decision lived only in prose that was about to close.** The out-of-scope
occurrence was recorded as "raised to the maintainers" inside this ticket, which closes today. That
is how a finding evaporates. It is now [DOC-04](../open/DOC-04.md), which states the three options
and a recommendation, and which deliberately does not restate the detail it is about — copying it
into a second file to describe it would double the disclosure rather than resolve it.

### The sweep, and what it did not find

Run by the implementer and independently re-run by the reviewer over the whole tracked tree
(`git ls-files`, so untracked and ignored paths are out of scope by construction):

| Searched for | Found |
|---|---|
| Private repository names, the umbrella path, parent-directory resolution | **One** occurrence outside the two files fixed here — a closed ticket describing another system's backup arrangements. Left alone deliberately; now DOC-04. |
| Internal hostnames, internal IP ranges | none |
| Absolute developer paths | none |
| Personal identifiers | none beyond the maintainer's public GitHub handle in `CODEOWNERS`, which is what that file is for |

Every other hit on the organisation name was `bitoracle-ai/nodera` — this repository's own public
location — which is not a leak.

**What the sweep cannot say:** it covers the tracked tree at this commit. It says nothing about the
published history, where the removed paragraph remains, and nothing about untracked files.

### What the review could not verify

The reviewer could not run `make PY=py check` — make is outside a reviewer's limits here — and ran
the four documentation gates instead, all green. The `make` target was run by the implementer, exit 0.

The reviewer also noted, correctly, that `lint_docs_index.py` resolves links on a machine where the
directories named in the removed paragraph happen to exist, so that gate could not have caught a link
escaping the repository root. It does not affect the result: both entry files now contain no `../` at
all, so the claim was checked by reading the links rather than by trusting the gate.
