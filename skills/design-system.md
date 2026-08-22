---
summary: The visual system — exactly two themes, semantic tokens instead of literal colours, touch ergonomics that make the app usable one-handed, and what "modern and elegant" is allowed to mean here.
read_when:
  - Before adding a view, a component or any styling.
  - When choosing a colour, a size, a spacing value or a font size.
  - When a design decision would affect how an agent's contribution is displayed.
---

# Design system — Nodera

This skill owns the **visual** decisions. [`frontend-react.md`](frontend-react.md) owns the React
ones, and the accessibility floor lives there because it is a gate rather than a taste. Where the two
touch — contrast, colour as meaning, thumb reach — this file is the one that says what the value is.

## Exactly two themes

**Light and dark. Not three, not a per-project accent, not a customer palette.** Every additional
theme multiplies the surface on which contrast, focus rings and state colours have to be checked, and
the check is per theme rather than per token.

Following the operating system is **not a third theme.** It is a selection mode: `light`, `dark`, or
`system`, where `system` resolves to one of the two. The stored preference is the mode; what renders
is always one of the two themes.

- **A token defined in one theme only is a defect.** Both themes are complete or neither is.
- **Dark is not inverted light.** Pure black and pure white are both wrong. In dark, elevation reads
  as a *lighter* surface rather than a heavier shadow, and large saturated fills are dimmed rather
  than reused at the same intensity — the same colour that reads as confident on white glares on
  near-black.
- Both themes are checked at the same moment. A change that touches the palette is not finished when
  it looks right in the one the author happens to be using.

## Colour is a token, never a literal

No component contains a hex value, and no component contains a raw Tailwind colour like
`bg-blue-600`. It names a **semantic** token — what the colour *means*, not what it *is*:

```tsx
// Correct — the meaning survives a palette change and a theme switch
<div className="bg-surface-raised text-content-muted border-border-subtle">

// FORBIDDEN — a literal that is right in one theme by accident
<div className="bg-white text-gray-500 border-gray-200">
```

Tokens are defined once, as CSS custom properties, with a value per theme, and exposed to Tailwind
through the theme configuration. That is what makes "check contrast once" true rather than aspirational.

Name tokens by role: `surface`, `surface-raised`, `content`, `content-muted`, `border`, `border-subtle`,
`accent`, plus the state colours. A token called `blue` has already failed — it cannot be honoured in
a dark theme without lying.

## One accent, and restraint as the house style

"Modern and elegant" is unfalsifiable unless it says what it forbids. Here it means:

- **One accent colour.** It marks the primary action and the current selection. A second accent is a
  request to redesign, not an addition.
- **Few type steps.** A small scale, used consistently. Emphasis comes from weight and space before
  it comes from size, and never from colour alone.
- **Space is the main instrument.** A dense grid of boxes and rules is what this product must not look
  like; a tracker is read far more often than it is edited, and reading wants air.
- **Borders before shadows.** A subtle border separates in both themes at the same cost. Shadow is
  reserved for things that genuinely float — a sheet, a menu, a dialog.
- **Motion is short, and optional.** Nothing over ~200 ms, nothing that delays an action, and every
  transition honours `prefers-reduced-motion: reduce`.
- **State colours are semantic and few.** Success, warning, danger, info. Priority and status are not
  each given a colour of their own — see the next rule for why they cannot rely on one anyway.

## Usable on a phone, one-handed

Mobile-first is already a constraint in [`frontend-react.md`](frontend-react.md); this is what it means
in pixels.

- **Touch targets are at least 44×44 CSS pixels**, including their padding, with at least 8 px between
  two adjacent ones. A 24 px icon button is a 44 px target with a 24 px glyph in it.
- **The primary action sits in the bottom third**, inside thumb reach. Read a ticket, comment, change
  status — the three operations the vision names — are each reachable without the second hand.
- **Destructive actions do not sit in thumb reach.** The place easiest to hit by accident is the wrong
  place for the action that cannot be undone. This is the one deliberate exception to the rule above.
- **Nothing depends on hover.** Hover does not exist on a phone. A tooltip may add detail; it may never
  carry the only copy of a meaning.
- **Text does not require a horizontal scroll at 375 px.** Ticket keys, branch names and URLs are the
  usual offenders — truncate with a visible, tappable way to see the whole value.
- **Tap, don't drag.** Any reordering or status change offered as a drag also has a tap path.

## Colour never carries meaning alone

The accessibility floor states this as a rule; here is the design consequence. Every status, priority
and actor kind carries a **second** channel — text, an icon, or a shape — so it survives a
colour-blind reader, a monochrome display and a screenshot in a bug report.

Contrast is at least **4.5:1 for text and 3:1 for interactive boundaries and focus rings, in both
themes.** Checked when the token is defined, not when the component is written.

## Agent output is not styled as second-class

This one is a product rule wearing a design costume, and it is the easiest to violate with good
intentions.

An agent's comment, review or assignment gets the **same visual weight** as a human's: the same
typography, the same background, the same spacing. `actor.kind` is shown — that is invariant F2 — but
it is shown the way a name is shown, not the way a warning is shown.

- No muted text, no reduced opacity, no collapsed-by-default, no smaller font for agent content.
- No tinted background that reads as "machine-generated, skim this".
- The kind badge distinguishes by icon and label. It does not tint the whole message.

If agent output is noisy, that is a content problem at the source. Solving it in the stylesheet
demotes the participant the product exists to treat as equal, and does it where no invariant lint can
see it.

## What a review checks

1. No literal colour anywhere in a component.
2. Every new token has a value in both themes.
3. The view was actually opened in both themes and at 375 px — not assumed from the classes used.
4. Touch targets measure 44 px, including on icon-only buttons.
5. No status or priority distinguishable by colour alone.
6. No styling that makes agent content lighter, smaller, quieter or collapsed.
