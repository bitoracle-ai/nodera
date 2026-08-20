# Local guide — `frontend/`

Read before any change in this subtree. Root rules still apply; these are the additions that
only matter here.

Full reference: [`../skills/frontend-react.md`](../skills/frontend-react.md) ·
[`../docs/API_CONTRACT.md`](../docs/API_CONTRACT.md).

## The three rules broken most often here

1. **Never call `fetch` in a component.** All I/O goes through `src/api/`, whose types come
   from `yarn api:generate` against the committed OpenAPI document. A direct call bypasses
   error mapping, token refresh and the type contract at once.
2. **Render `actor.kind`; never infer it.** No `handle.endsWith('-bot')`, no name pattern, no
   heuristic. The field exists on every actor in every response.
3. **Mobile-first, at 375 px, actually looked at.** Unprefixed Tailwind classes are the phone
   case; `sm:` and up are additions. The primary action stays in the bottom third.

## Every unit ships with a test

A `*.test.tsx` next to every new component, hook and utility, in the same commit. Coverage is
gated **per file** at 80 % — an aggregate number lets a well-tested file hide an untested one.

Test behaviour through the DOM. A test asserting on internal state passes through a refactor
that broke the feature.

## Accessibility floor — not a later ticket

Keyboard reachable with a visible focus ring · accessible name on every control ·
colour never the only carrier of meaning · 4.5:1 text contrast · errors announced, not only
coloured.

## Generated code

`src/api/generated/` is produced by `yarn api:generate`. Never edit it by hand — CI regenerates
and fails on a diff, which is the drift the generation step exists to catch.

## Gates

```
yarn lint && yarn typecheck && yarn test:coverage && yarn build
```

TypeScript runs in `strict` mode with `noUncheckedIndexedAccess`. `any` needs a comment naming
why; `@ts-ignore` is a review finding.
