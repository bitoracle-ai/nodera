---
applyTo: "frontend/**"
---

# Frontend (React) — path-specific rules

- Never call `fetch` directly in a component. All I/O goes through `src/api/`, whose types are
  generated from the OpenAPI document.
- Render `actor.kind` to show whether an actor is a person or an agent. Never infer it from a name,
  a handle pattern or a heuristic.
- Every new component, hook or utility ships with a `*.test.ts(x)` next to it.
- Mobile-first: build at 375 px first, then widen. The primary action stays in thumb reach;
  destructive actions deliberately do not. Touch targets are at least 44x44 px.
- Exactly two themes, light and dark. Colour is a semantic token (`bg-surface-raised`), never a
  literal (`bg-white`, `bg-blue-600`). Every token has a value in both themes.
- Agent output is styled exactly like human output. No muted text, no tint, no collapsed-by-default.
- A component renders; a hook decides. Full reference: `skills/design-system.md`.
- Gates: `yarn typecheck` · `yarn lint` (max-warnings 0) · `yarn test:coverage` · `yarn build`.
