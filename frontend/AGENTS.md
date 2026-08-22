# Local guide — `frontend/`

Read before any change in this subtree. Root rules still apply; these are the additions that
only matter here.

Full reference: [`../skills/frontend-react.md`](../skills/frontend-react.md) ·
[`../skills/design-system.md`](../skills/design-system.md) ·
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

## Two themes, and colour is always a token

Light and dark, exactly. Following the OS is a selection mode, not a third theme. A component
never contains a literal colour — not a hex value and not `bg-white` or `bg-blue-600` — only a
semantic token such as `bg-surface-raised` or `text-content-muted`. A token without a value in
**both** themes is a defect, and dark is not inverted light: elevation there is a lighter
surface, not a heavier shadow.

Touch targets are at least 44x44 px including padding. The primary action sits in the bottom
third; destructive actions deliberately do not, because the easiest place to hit by accident is
the wrong place for what cannot be undone.

**Agent output is styled exactly like human output** — same typography, same background, same
spacing. No muting, no tint, no reduced opacity, no collapsed-by-default. The kind badge
distinguishes by icon and label; it never tints the message. Demoting agent content in the
stylesheet demotes the participant this product exists to treat as equal, somewhere no lint can
see it.

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
