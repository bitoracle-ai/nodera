---
id: WEB-02
title: Ticket list and detail views, mobile-first
priority: P2
status: open
effort: ~3 d
depends_on: [WEB-01, CORE-04]
created: 2026-08-20
updated: 2026-09-03
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
5. The Markdown renderer for ticket bodies, comments and review text — and it is the home of both
   halves of Markdown safety (maintainer decision, 2026-09-03). Bodies are stored escaped today:
   CORE-04's sanitiser turns every `<` into `&lt;` on construction, with no exemption for code, so a
   `<` in a code sample renders as `&lt;` until the renderer takes that half over. The other half
   has no owner at all yet: link destinations are not filtered, so a `javascript:` URL in a body is
   stored untouched. The renderer filters link destinations by scheme (`javascript:` is the case
   that motivated it; the allowlist is this package's to decide), never interprets a body as HTML,
   and is where code-aware escaping lands when the write-side rule is reduced — that reduction is a
   later package, not this one.

## Acceptance criteria

- [ ] Read, comment and change status are each reachable one-handed at 375 px, with the primary
      action in the bottom third.
- [ ] Agent and human comments have identical visual weight; the only difference is the badge.
- [ ] A refused closure shows every unmet criterion and unresolved finding, each linked to its item.
- [ ] Every interactive element is keyboard reachable with a visible focus ring; icon-only buttons
      carry an accessible name.
- [ ] Colour is never the only carrier of priority or status.
- [ ] The renderer never emits a live link for a `javascript:` destination and never interprets a
      body as HTML; both proved by tests that are red with the filter removed.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `frontend/src/features/tickets/`.

## Verification

`yarn test:coverage` plus a Playwright run of the three operations at 375 px, and a keyboard-only
pass through the detail view.
