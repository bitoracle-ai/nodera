---
id: DOC-05
title: Redact the disclosure DOC-03 quoted into its own record
priority: P1
status: closed
effort: ~0.25 d
depends_on: []
note: Same live disclosure DOC-03 removed from the entry files, still current because that package quoted it as evidence.
created: 2026-08-31
updated: 2026-08-31
closed: 2026-08-31
---

# DOC-05 · Redact the disclosure DOC-03 quoted into its own record

**Priority:** P1
**Effort:** ~0.25 d

## Motivation / context

[DOC-03](../closed/DOC-03.md) removed a paragraph from `CLAUDE.md` and `AGENTS.md` that described a
layout outside this repository and named other repositories. To show what it had removed, it quoted
that paragraph in full in its own **Current state (honest)** section.

So the disclosure is still current. It moved from an entry file into a closed ticket, and this
public repository still says the sentence DOC-03 was written to stop it saying.

This was a judged call rather than an oversight — a removal record that cannot show what was removed
is hard to check — and [DOC-04](../closed/DOC-04.md)'s reviewer swept the tree and reached the same
conclusion. Both were wrong, on two grounds:

1. **It is the exact arbitrariness DOC-04 was decided against.** DOC-04 chose to redact a closed
   ticket precisely because leaving a disclosure standing on account of *which file* it sits in makes
   the rule about the file rather than about the disclosure. The next occurrence of that distinction
   was one file away, in the package that created it.
2. **`docs/PROJECT_MANAGEMENT.md` § 13 is now this repository's own rule**, and it says a redaction
   must not restate the detail. A tree where the rule lands and a ticket quotes the thing in full on
   the same day is a rule with a precedent against it from birth.

**The verifiability objection is answered by the commit.** `aab816c` holds the exact before-and-after
diff. A record that cites the commit is complete and checkable; reproducing the string moves the
disclosure rather than ending it. That is the argument DOC-04 already used for keeping the detail out
of its own body, and it applies here unchanged.

## Priority

**P1, on the same single ground DOC-03 was filed P1:** a live disclosure on a published surface is
the class of thing that should not wait behind a backlog. It is the *same* disclosure, still current.

That § 13 already decides the substance — so this package applies an existing rule rather than making
a decision — bears on **effort and process, not on rank**. § 5's ladder is impact-based throughout,
which is why DOC-03's own priority argument was corrected in review for leaning on how quick the fix
was. "It is mechanical" is no more a reason to rank something down than "it is quick" was a reason to
rank it up.

**What the rank rests on, stated plainly:** the DOC-04 precedent that where a disclosure sits does
not gate the rule. It is *not* a claim that a closed ticket is read as often as an entry file — it
plainly is not. Someone who rejects that precedent should argue with the precedent rather than with
this rank.

## Current state (honest)

`tickets/closed/DOC-03.md`, in its **Current state (honest)** section, reproduces the removed
paragraph verbatim as a block quote. **This ticket deliberately does not reproduce it** — that is the
whole point, and a ticket about a quotation that quotes it again has done nothing.

A sweep of the tracked tree (`git ls-files`) returns this and nothing else.

## Approach

1. Replace the quoted block in `tickets/closed/DOC-03.md` with a marker under § 13, naming the class
   and citing `aab816c` for anyone who needs the exact text. DOC-03's argument must survive: a reader
   should still understand what was wrong with the old paragraph and why its replacement is right,
   without the old paragraph being present.
2. Add one sentence to § 13. Its "without restating the detail" clause governs **the marker**; it does
   not, on a plain reading, tell a package that it may not quote the disclosure it is removing in its
   own before-state record. That is the gap DOC-03 fell into in good faith, and one sentence closes it.
   One sentence only — the rule is not padded.
3. Re-run the sweep and record the result.

## Acceptance criteria

- [x] The tracked tree no longer reproduces the removed paragraph anywhere. The sweep, re-run
      independently by the reviewer over `git ls-files`, returns zero hits.
- [x] `tickets/closed/DOC-03.md` carries a marker under § 13 naming the class and citing `aab816c`.
      The reviewer confirmed the commit exists and that its content matches what the marker says.
- [x] DOC-03's argument still reads without the quotation — its motivation section is untouched and
      still states both problems, and the redacted section still says what the paragraph did.
- [x] § 13 states the removing-package rule in **one** sentence, contradicting and duplicating
      nothing already there.
- [x] Nothing in this package is written as though it removed anything from published history; the
      opposite is said at every touch point.
- [x] This ticket does not reproduce the paragraph — it describes the class only.
- [x] `make PY=py check` green (exit 0).
- [x] Independent review: 0 BLOCKING findings.

## Affected files

- `tickets/closed/DOC-03.md` — the redaction.
- `docs/PROJECT_MANAGEMENT.md` — one sentence in § 13.

## Verification

Re-run the sweep from DOC-03's review result and confirm it returns nothing.

## Review result

**2026-08-31 · APPROVED, 0 BLOCKING, 2 NON-BLOCKING.** Both fixed in session.

**N1 — § 13's title had stopped describing its contents.** The section is called *Redacting a closed
ticket*, but the sentence added here governs a different act: a still-open ticket's own before-state
record at the moment it performs a removal. No contradiction with the existing bullets, and the
reviewer judged there was no better home — but a reader asking "may I quote the thing I am removing?"
would not have found the answer under that title. Retitled to *Redacting a disclosure from the ticket
record*. Every existing citation is by number rather than by title, so nothing else moved.

**N2 — the P1 rank rests on precedent, and now says so.** The ticket argued the disclosure is "the
same, still current", which quietly treats a closed ticket and an entry file every tool loads as
equally exposed. They are not. What the rank actually rests on is DOC-04's precedent that where a
disclosure sits does not gate the rule, and the ticket now says that in as many words, so a reader
who disagrees can argue with the precedent rather than with the number.

### The finding this package exists for

The reviewer of [DOC-04](DOC-04.md) swept the tree, found this quotation, and judged it not a leak —
as had the package that wrote it. The judgement was wrong in both places for the same reason: it
turned on *which file* the disclosure sat in. DOC-04 had just decided that the file a disclosure sits
in does not gate the rule, and the very next occurrence was one file away, in the package that
created the distinction.

The verifiability instinct behind the original quotation was sound — a removal record that cannot
show what was removed is hard to check — and it is answered by the commit rather than by the string.
`aab816c` holds the exact before-and-after diff. That is a complete record, and § 13 now says so.

### What the review could not verify

`make PY=py check` — outside a reviewer's limits here; it ran the four documentation gates instead,
all green. The `make` target was run by the implementer, exit 0, and again after these two fixes.
