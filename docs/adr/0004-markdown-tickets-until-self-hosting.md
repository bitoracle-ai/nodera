# ADR-0004 — Markdown tickets until Nodera can host its own backlog

- **Status:** Accepted (2026-08-20)
- **Context documents:** [`../PROJECT_MANAGEMENT.md`](../PROJECT_MANAGEMENT.md) § 1 ·
  [`../DOMAIN_MODEL.md`](../DOMAIN_MODEL.md) § 10
- **Affects:** `tickets/`, `scripts/`, and the acceptance criteria of the interchange work package.

## Context

Nodera is a ticket system with no running instance. Its own backlog has to live somewhere, and the
two obvious options are both wrong: a competitor's tracker adds an account and an export problem, and
a half-built Nodera is the worst possible place to keep the plan for finishing Nodera.

## Decision

**Track Nodera's own development in Markdown files in this repository, and migrate to Nodera when
Nodera can host the backlog without loss.**

The Markdown system is not a stopgap that will be thrown away. It is:

1. **The specification.** The frontmatter fields are the domain model's ticket fields; the body
   sections are what a ticket must be able to hold; the review record is the hardest requirement in
   [`../VISION.md`](../VISION.md) § 5.
2. **The acceptance test.** The migration is what proves the round-trip invariant (M1). If the
   review history does not survive the import, the interchange format is not finished — and that is
   a defect in the product, not in the migration script.
3. **A supported format afterwards.** Export stays; the MCP ticket resource returns exactly it.

## Consequences

- ✅ The backlog is versioned with the code, reviewable in the same pull request, and readable by any
  contributor with a text editor and by any agent with file access.
- ✅ The dogfooding milestone is concrete and dated rather than aspirational.
- ✅ Teams already keeping tickets in git have a real adoption path, because it is the one this
  project used itself.
- ⚠️ No cross-ticket queries beyond what the scripts implement, and no notifications. Accepted:
  the backlog is small while this decision holds.
- ⚠️ Concurrent edits collide as merge conflicts. Accepted for the same reason.
- ⚠️ There is a temptation to keep extending the scripts instead of building the product. The ticket
  hygiene rule applies to tooling work too: a script feature needs a ticket and a reason.

## Alternatives considered

- **GitHub Issues:** rejected. It cannot express acceptance criteria as gated items or a multi-round
  review record, and the export would lose exactly the part worth migrating.
- **An existing tracker:** rejected. Same loss, plus an account dependency for every contributor.
- **Wait and keep the backlog in chat:** rejected. That is how a project loses the reasoning behind
  its own decisions.
