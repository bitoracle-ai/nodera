---
applyTo: "frontend/**"
---

# Frontend (React) — path-specific rules

- Never call `fetch` directly in a component. All I/O goes through `src/api/`, whose types are
  generated from the OpenAPI document.
- Render `actor.kind` to show whether an actor is a person or an agent. Never infer it from a name,
  a handle pattern or a heuristic.
- Every new component, hook or utility ships with a `*.test.ts(x)` next to it.
- Mobile-first: build at 375 px first, then widen. The primary action stays in thumb reach.
- Gates: `yarn typecheck` · `yarn lint` (max-warnings 0) · `yarn test:coverage` · `yarn build`.
