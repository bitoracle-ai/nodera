---
id: DOC-04
title: Decide how the closed-ticket record handles another system's infrastructure detail
priority: P2
status: closed
effort: ~0.25 d
depends_on: []
created: 2026-08-31
updated: 2026-08-31
closed: 2026-08-31
note: Needs a maintainer's answer, not a contributor's edit — the question is how this project treats its own history.
---

# DOC-04 · Decide how the closed-ticket record handles another system's infrastructure detail

**Priority:** P2
**Effort:** ~0.25 d

## Motivation / context

A sweep run during [DOC-03](DOC-03.md) found one place in the tracked tree, outside the entry files
that package fixed, where a closed ticket describes a **different, non-public system**: it names that
system and summarises how its backups are arranged.

This repository is public and MIT. The detail is not about Nodera, it is not needed to understand the
decision the ticket recorded, and it would not be written that way today.

**This ticket deliberately does not restate the detail.** The location is
`tickets/closed/OPS-03.md`, in the *To decide before starting* section. Copying the sentence into a
second file to describe it would double the disclosure rather than resolve it, which is the whole
thing being avoided.

## Current state (honest)

The text is in the published history and removing it from the working tree does not remove it from
there. So this is a question about what the repository **currently says**, not a containment measure,
and nothing here should be written as though it were one.

DOC-03 fixed the two entry files and left this alone on purpose: those were live instructions every
session loads, this is a historical record of a decision, and the two are not the same kind of
document.

## The decision — made by the maintainers, implemented here

**Option 2: redact in place, with a visible marker.**

Option 1's concern is real and is answered rather than defeated. A record silently rewritten when it
becomes inconvenient is worth less than one that is not — which is exactly why the redaction is
**marked**: the reader sees that something was removed and why, so the record stays honest about its
own history instead of pretending it always read that way. Option 3 fails on the only thing that
matters here, because the detail would stay.

The deciding argument is consistency. [DOC-03](DOC-03.md) stopped the repository saying this class of
thing in its entry files. Leaving the same class standing in a closed ticket would make the rule
about *which file* it sits in rather than about what the repository discloses, and that distinction
will not survive the next occurrence.

The clause's own argument — this project has no host and no timer to hang a backup script off, so
prose beat scripts — survives intact. Nothing about the recorded decision is lost.

**And a general rule, not a one-off**, because the question recurs the next time a closed ticket is
found to say too much. It lands in layer 2 as `docs/PROJECT_MANAGEMENT.md` § 13.

## Also in this package, by direction

The `🤖` rule installed by [DOC-02](DOC-02.md) covers **commit subjects and pull-request titles** in
both layer-2 homes, but all three root adapters named only subjects. A contributor reading just an
adapter would install it for commits and forget it for pull requests — the exact split the rule
exists to prevent. Fixed here rather than filed, because these are entry files and this is an
entry-file package, so `docs/PROJECT_MANAGEMENT.md` § 8's *reserved surface* reservation is already
satisfied.

## Acceptance criteria

- [x] The decision is recorded with its reasoning — here, and as a general rule in
      `docs/PROJECT_MANAGEMENT.md` § 13.
- [x] Applied to the occurrence DOC-03 found, marked in place, dated and ticket-referenced.
- [x] The general rule lands in a layer-2 document, not only in this ticket.
- [x] No new file restates the detail. The marker names the **class** — a system outside this
      repository and how its backups were arranged — and not the system, the schedule or the scripts.
      A redaction that describes the removed detail precisely enough to reconstruct it is not one.
- [x] The redacted ticket's argument still reads. The original sentence leaned on a "none of those"
      whose antecedent was the removed clause; it now states the three conditions directly, so
      nothing dangles.
- [x] Nothing in this package is written as though it removed anything from published history — the
      marker, § 13 and this ticket all say the opposite in as many words.
- [x] All three root adapters name pull-request titles alongside commit subjects; all five carriers
      now agree.
- [x] `make PY=py check` green (exit 0).
- [x] Independent review: 0 BLOCKING findings.

## Affected files

- `tickets/closed/OPS-03.md` — the redaction.
- `docs/PROJECT_MANAGEMENT.md` — § 13, the general rule.
- `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md` — the pull-request-title gap.
- `docs/docs_map.md` — regenerated, one line for § 13.

## Verification

Re-run the sweep from DOC-03's review result and confirm it comes back empty.

## Review result

**2026-08-31 · APPROVED, 0 BLOCKING, 2 NON-BLOCKING.**

**N1 — the affected-files list was incomplete.** It omitted `docs/docs_map.md`, which the change also
touches. Generated rather than hand-written, and the gate for it is green either way, but a record of
what a package touched should be complete. Added.

**N2 — the reviewer flagged a close call rather than a defect, and it is worth keeping.** The
sentence left standing says *Nodera* has no host, no scheduled timer and no off-box backup target.
That is this project's own negative state, not the other system's arrangement, and it reconstructs
nothing — no name, no schedule, no script names. Judged not a leak by the reviewer and recorded here
because it is the kind of line that will look like a leak to the next person sweeping for one, and
because § 13 will be invoked again.

### The sweep, re-run independently

The reviewer swept the tracked tree for the removed detail and for the vocabulary around it. Six
hits, three files, none a leak:

- `docs/ops/backup-restore.md` — Nodera's own backup documentation, describing Nodera.
- `tickets/closed/DOC-03.md` — two hits, both pre-existing and untouched here. One quotes the
  original entry-file text as its own before-state evidence; the other is DOC-03's out-of-scope
  section, which described this clause's *class* without naming the system or the scripts. DOC-03
  applied redaction-shaped discipline before § 13 existed to require it.
- `tickets/closed/OPS-03.md` — the new marker itself, and an unrelated line about the local restore
  rehearsal on a laptop.

No other closed ticket was edited, confirmed by `git status`.

### What the review could not verify

`make PY=py check` — outside a reviewer's limits here. It ran the four documentation gates instead,
all green. The `make` target was run by the implementer, exit 0. This is a documentation-only diff,
so no backend, frontend or database lane is exercised by the change itself.
