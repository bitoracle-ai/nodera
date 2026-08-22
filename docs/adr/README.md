# Architecture decision records

A structural decision goes here, not into a ticket. A ticket records work; an ADR records **why the
shape is the way it is and what was rejected** — the part that stays useful after the work is done
and the only part that answers "why not do it the obvious way" two years later.

## When to write one

- The decision constrains future work (a module boundary, a protocol, a storage choice).
- The decision is not obvious, and a reasonable contributor would propose the alternative.
- The decision would otherwise be reconstructed from code archaeology.

Not for: a library version bump, a naming convention, anything a skill already governs.

## Format

`NNNN-short-kebab-title.md`, with: **Status** (Proposed / Accepted / Superseded, dated) · **Context**
(the forces) · **Decision** · **Consequences** (including the bad ones) · **Alternatives considered**
(and why each was rejected).

A superseded ADR is **not deleted** — it is stamped and linked to its successor. The rejected path is
information.

## Records

| ADR | Title | Status |
|---|---|---|
| [0001](0001-actor-not-user.md) | Actor, not user — one participant type for humans and agents | Accepted |
| [0002](0002-provider-agnostic-agent-adapters.md) | Provider-agnostic agent adapters: two-layer architecture | Accepted |
| [0003](0003-ticket-frontmatter-source-of-truth.md) | Ticket frontmatter is the source of truth | Accepted |
| [0004](0004-markdown-tickets-until-self-hosting.md) | Markdown tickets until Nodera can host its own backlog | Accepted |
| [0005](0005-mcp-as-sibling-surface.md) | MCP as a sibling surface, not a wrapper over REST | Accepted |
| [0006](0006-one-image-three-entrypoints.md) | One image, three entrypoints; migrations are a separate step | Accepted |
| [0007](0007-deployment-is-the-tenant-boundary.md) | The deployment is the tenant boundary | Accepted |
| [0008](0008-kotlin-on-the-jvm-for-the-backend.md) | Kotlin on the JVM for the backend | Accepted |
