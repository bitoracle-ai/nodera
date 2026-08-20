# ADR-0003 — Ticket frontmatter is the source of truth; every table view is generated

- **Status:** Accepted (2026-08-20)
- **Context documents:** [`../PROJECT_MANAGEMENT.md`](../PROJECT_MANAGEMENT.md) § 1
- **Affects:** `tickets/`, `REVIEW_REPORT.md`, `scripts/tickets_index.py`, `scripts/check_tickets.py`.

## Context

A ticket system needs an index humans read and a per-ticket file humans edit. Keeping both by hand
means every closure touches two places, and the index is the one that silently goes stale — usually
in the direction of claiming work is further along than it is.

## Decision

**The YAML frontmatter of each ticket file is the only authoritative record.** `tickets/INDEX.md`
and `REVIEW_REPORT.md` are generated from it between explicit markers, and the freshness of those
views is a **gate error** rather than a convention.

The hand-written parts of `INDEX.md` — status narrative and working order — sit outside the markers
and are deliberately not derivable: an ordering decision is judgement, and generating a plausible
one would hide the fact that nobody made it.

## Consequences

- ✅ One place to edit. A closure cannot leave the index disagreeing with the tickets.
- ✅ `--json` gives tooling a machine-readable export for free, which is what the eventual import
  into Nodera itself will consume.
- ✅ A stale view fails CI, so drift is a red build rather than a misleading document.
- ⚠️ The generated regions must never be hand-edited; a contributor who does will have the change
  silently overwritten. Mitigated by the marker comment naming the regenerate command.
- ⚠️ The frontmatter parser is a small stdlib implementation rather than a YAML library, so it
  supports only the subset in use. A contributor writing exotic YAML gets a parse that ignores it,
  which is why `check_tickets.py` validates the fields it expects rather than trusting the parse.

## Alternatives considered

- **Hand-maintained index:** rejected — it is the thing that goes stale, and it goes stale
  optimistically.
- **Index as source of truth, tickets as detail:** rejected. The detail is where the work happens;
  making the summary authoritative inverts which document gets attention.
- **A database for the repository's own backlog:** rejected until Nodera can host it — see
  [ADR-0004](0004-markdown-tickets-until-self-hosting.md).
