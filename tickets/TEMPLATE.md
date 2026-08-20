---
id: {{ID}}
title: {{TITLE}}
priority: {{PRIORITY}}
status: open
effort: {{EFFORT}}
depends_on: []
created: {{DATE}}
updated: {{DATE}}
---

# {{ID}} · {{TITLE}}

**Priority:** {{PRIORITY}}
**Effort:** {{EFFORT}}

<!-- Scaffold: `python scripts/ticket_new.py <ID> "<title>" [--priority P2] [--effort "~1 d"]`
     The INDEX row is DERIVED from this frontmatter — never paste it by hand. Add
     `depends_on: [ID, …]` and an optional one-line `note:` for a blocker, then run
     `python scripts/tickets_index.py --write`.
     Gate: `python scripts/check_tickets.py --check`.
     Structural decisions go to docs/adr/ as an ADR, not into this ticket.
     Language: English, like everything else committed here. -->

## Motivation / context

<Why this work package? Which problem, which benefit — 2–4 sentences.>

## Current state (honest)

<What exists today, what is concretely missing. Describe what is there, not what was
intended. This section is the one most often found to be wrong in review.>

## Approach

1. <First step.>

## ⚠️ To decide before starting

- <Open question + your recommendation. Delete this section if there is none.>

## Acceptance criteria

- [ ] <Verifiable criterion — a reader must be able to check it without asking you.>
- [ ] `make check` green.
- [ ] Independent review (phase 4, never the author): 0 BLOCKING findings.

## Affected files

- `<path>` — <reason>.

## Verification

<Which command, which test, what a reader should look at. "No code" is a valid answer;
"tested manually" without saying what was done is not.>
