---
summary: React and TypeScript conventions — mobile-first layout, the generated API layer, rendering actor kind, component testing, and the accessibility floor every view meets.
read_when:
  - Before any change under `frontend/`.
  - When adding a view, a data hook or a component that displays an actor.
  - During review of a frontend diff.
---

# Frontend (React) — conventions

## Mobile-first is a constraint, not a preference

Build every view at **375 px first**, then widen. Tailwind's unprefixed classes are the mobile case;
`sm:` and up are the additions.

The three operations from the vision's success criteria — read a ticket, comment, change status — are
reachable **one-handed**, with the primary action inside thumb reach (bottom third of the viewport).
A desktop-first layout that "also works" on a phone fails this in a way no breakpoint can repair.

Check at 375 px before opening a pull request. Not "it uses responsive classes" — actually look.

## Never call `fetch` in a component

All I/O goes through `src/api/`, whose types and Zod schemas are **generated** from the OpenAPI
document (`yarn api:generate`). A component that talks to the network directly bypasses error mapping,
token refresh and the type contract at once, and it will drift from the server the first day nobody
checks.

Server state is TanStack Query. Caching, retry and invalidation are decided in one place, not per
component.

## Render `actor.kind` — never infer it

Wherever an actor appears, show whether it is a person or an agent, **from the field**.

```tsx
// Correct
<ActorBadge kind={actor.kind} />

// FORBIDDEN — a heuristic standing in for a field that already exists
const isBot = actor.handle.endsWith('-bot')
```

Agent comments get the same visual weight as human comments. Do not de-emphasise them: if agent output
is noisy, that is a content problem to solve at the source, not a rendering problem to hide.

## Every unit ships with a test

Every new component, hook or utility gets a `*.test.tsx` next to it, in the same commit. Per-file
coverage is gated at 80 % — an aggregate number lets a well-tested file hide an untested one.

Test behaviour through the DOM (Testing Library), not implementation details. A test that asserts on
internal state passes through a refactor that broke the feature.

## Accessibility floor

Not optional, and not a later ticket:

- Every interactive element is reachable and operable by keyboard, with a visible focus ring.
- Every control has an accessible name. Icon-only buttons carry `aria-label`.
- Colour is never the only carrier of meaning — priority and status also have text or shape.
- Text contrast is at least 4.5:1; the palette is defined once so this is checked once.
- Forms associate labels, and errors are announced rather than only coloured.

## State

React state and context. **No global store until something actually needs one** — most apparent needs
for one are server state that belongs in TanStack Query.

## Gates

`yarn typecheck` · `yarn lint` (max-warnings 0) · `yarn test:coverage` · `yarn build`.
TypeScript runs in `strict` mode; `any` needs a comment naming why, and `@ts-ignore` is a review finding.
