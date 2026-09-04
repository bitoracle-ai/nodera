---
id: WEB-01
title: Frontend shell — routing, authentication, generated API client
priority: P2
status: open
effort: ~2 d
depends_on: [API-01]
created: 2026-08-20
updated: 2026-09-03
---

# WEB-01 · Frontend shell — routing, authentication, generated API client

**Priority:** P2
**Effort:** ~2 d

## Motivation / context

The generated API layer has to exist before any view, or the first view hand-writes types and every
later one copies the pattern.

## Current state (honest)

`frontend/` holds `package.json`, the Vite configuration and an empty `src/`. No
routing, no auth, no API layer.

## Approach

1. `yarn api:generate` producing types and Zod schemas from the committed OpenAPI document.
2. The `src/api/` layer: one client, token refresh, error mapping. Nothing else calls `fetch`.
3. Sign-in flow, route guard, project switcher.
4. The `ActorBadge` component — one place that renders `actor.kind`, used everywhere an actor appears.

**Not in this package: Markdown rendering.** The renderer — and with it both halves of Markdown
safety, code-aware escaping and link-destination filtering (`javascript:`) — is WEB-02's (maintainer
decision, 2026-09-03). The shell ships no Markdown component, so no later view inherits an
unfiltered one.

## Acceptance criteria

- [ ] `yarn api:generate` is reproducible and CI fails when the generated output is stale.
- [ ] No component calls `fetch`; a lint rule enforces it and fails on a deliberate violation.
- [ ] `ActorBadge` renders from `actor.kind` alone; no name pattern or heuristic appears anywhere.
- [ ] The shell is usable at 375 px, checked at that width rather than assumed.
- [ ] The token layer exists before any view does: semantic CSS custom properties with a value in
      **both** themes, exposed to Tailwind through `@theme inline`, and the class strategy declared
      with `@custom-variant dark (&:where(.dark, .dark *))`. Tailwind is v4 since WEB-03, so there is
      no `darkMode` option and no `tailwind.config.js` to put it in; the mechanism is CSS. A token
      defined in one theme only fails the build or a test — not review.
- [ ] Exactly two themes plus a `system` selection mode that resolves to one of them. No third theme
      and no per-project palette (`skills/design-system.md`).
- [ ] No component in the diff contains a literal colour — no hex value, no `bg-white`, no
      `bg-blue-600`. A lint rule enforces it and fails on a deliberate violation.
- [ ] Contrast is verified at token-definition time in both themes: 4.5:1 for text, 3:1 for
      interactive boundaries and focus rings.
- [ ] Touch targets measure at least 44x44 px including padding, icon-only buttons included.
- [ ] Every new component and hook ships with a test file; per-file coverage at least 80 percent.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `frontend/src/api/`, `frontend/src/routes/`, `frontend/src/components/ActorBadge.tsx`.

## Verification

`yarn typecheck && yarn lint && yarn test:coverage && yarn build`, plus a look at 375 px.
