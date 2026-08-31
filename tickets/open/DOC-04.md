---
id: DOC-04
title: Decide how the closed-ticket record handles another system's infrastructure detail
priority: P2
status: open
effort: ~0.25 d
depends_on: []
created: 2026-08-31
updated: 2026-08-31
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

## ⚠️ To decide before starting — this is the ticket

**It needs an owner's answer, not a contributor's edit** — `docs/PROJECT_MANAGEMENT.md` § 8 names
*structural decision* as one of the few criteria that justify a ticket of their own, and this is one.
The question is not the wording; it is what this project does with a closed record that says
something it would not say today.

Three options, with what each costs:

1. **Leave it as written.** Closed tickets are a record, and a record that gets edited when it
   becomes inconvenient is worth less than one that does not. Cost: the repository keeps saying it.
2. **Redact the clause in place**, with a marker saying a redaction happened and why. Keeps the
   decision legible — the argument the sentence supported still reads — while dropping the detail.
   Cost: a closed ticket is no longer exactly what it was when it closed.
3. **Annotate without redacting** — leave the text and add a note that it should not have been
   written. Cost: the detail stays, so this only helps future readers, not the disclosure.

**Recommendation: option 2.** The sentence's argument — that this project has no host and no timer to
hang a backup script off, so prose beat scripts — survives entirely without naming the other system
or its arrangements. Nothing about the recorded decision is lost.

Whichever is chosen, the outcome is worth a general rule rather than a one-off, since the same
question will recur the next time a closed ticket is found to say too much.

## Acceptance criteria

- [ ] The decision is made and recorded, with its reasoning, so the next occurrence is not re-argued.
- [ ] Applied to the occurrence DOC-03 found.
- [ ] If the answer is a general rule, it lands in a layer-2 document rather than only in this ticket.
- [ ] No new file restates the detail in the course of removing it.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `tickets/closed/OPS-03.md`, depending on the decision.
- A layer-2 document, if the answer is a general rule.

## Verification

Re-run the sweep from DOC-03's review result and confirm it comes back empty.
