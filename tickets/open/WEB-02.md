---
id: WEB-02
title: Ticket list and detail views, mobile-first
priority: P2
status: open
effort: ~3 d
depends_on: [WEB-01, CORE-04]
created: 2026-08-20
updated: 2026-08-20
---

# WEB-02 · Ticket list and detail views, mobile-first

**Priority:** P2
**Effort:** ~3 d

## Motivation / context

Three operations decide whether the product is usable: read a ticket, comment, change status. The
vision names them as success criteria and requires them to work one-handed on a phone.

## Current state (honest)

WEB-01 delivers the shell. No ticket views exist.

## Approach

1. List view: priority grouping, status filter, assignee filter with `ActorBadge` on each row.
2. Detail view: body, acceptance criteria with their provenance, dependencies, comment thread,
   review rounds.
3. Comment composer and status transition, both reachable one-handed.
4. The closure gate refusal rendered as the itemised list the API returns, not as a toast.

## Acceptance criteria

- [ ] Read, comment and change status are each reachable one-handed at 375 px, with the primary
      action in the bottom third.
- [ ] Agent and human comments have identical visual weight; the only difference is the badge.
- [ ] A refused closure shows every unmet criterion and unresolved finding, each linked to its item.
- [ ] Every interactive element is keyboard reachable with a visible focus ring; icon-only buttons
      carry an accessible name.
- [ ] Colour is never the only carrier of priority or status.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `frontend/src/features/tickets/`.

## Verification

`yarn test:coverage` plus a Playwright run of the three operations at 375 px, and a keyboard-only
pass through the detail view.
