# Ticket index — Nodera

> **Entry point for every session.** Read this first, then the next open ticket by priority.
> Process rules: [`../docs/PROJECT_MANAGEMENT.md`](../docs/PROJECT_MANAGEMENT.md) · scope fence:
> [`../docs/VISION.md`](../docs/VISION.md) · invariants:
> [`../skills/critical-invariants.md`](../skills/critical-invariants.md).
>
> The tables below are **generated from ticket frontmatter**. Edit the ticket file, then run
> `python scripts/tickets_index.py --write`; never edit between the markers.
> Gate: `python scripts/check_tickets.py --check`.

## Status (hand-maintained)

**2026-08-20 — foundation laid, implementation not started, build chain not yet runnable.**

The repository scaffold is in place: vision and scope fence, domain model, architecture, MCP
surface, the database schema as four migrations, the rule set, the tool-agnostic adapter layer,
CI, and this ticket system. **No application code exists yet** — `backend/` and `frontend/` hold
build configuration and module structure, not implementations.

**The build chain does not run.** `backend/gradlew` and `frontend/yarn.lock` are absent, so every
command in the Makefile, in `docs/ci.md` and in both workflows fails at its first line. That is
OPS-01, and it precedes everything — the working order below is otherwise unrunnable rather than
merely unstarted.

The deployment shape was settled on the same day, before implementation rather than after:
[ADR-0006](../docs/adr/0006-one-image-three-entrypoints.md) (one image, three entrypoints,
migrations as their own step) and
[ADR-0007](../docs/adr/0007-deployment-is-the-tenant-boundary.md) (the deployment is the tenant
boundary). Both constrain OPS-01, CORE-01 and MCP-01.

The backlog below is the path to a running system. It is ordered so that the invariants that are
hardest to retrofit — actor identity, permissions, audit — land first, before anything depends on
their shape.

## Working order

0. **[OPS-01](open/OPS-01.md)** — the build chain and the release package. Not a preference about
   ordering: without a Gradle wrapper and a lockfile, no other package can run its own gates.
1. **[CORE-01](open/CORE-01.md)** — the actor model and the permission engine. Everything else
   references it, so it goes first and it goes in carefully.
2. **[DB-01](open/DB-01.md)** → **[CORE-02](open/CORE-02.md)** — schema applied and the audit
   recorder wired, in that order: the audit invariant is unenforceable without the privilege
   split the migration creates.
3. **[API-01](open/API-01.md)** and **[MCP-01](open/MCP-01.md)** — the two surfaces, built against
   the same use cases. MCP-01 depends on API-01 only for the shared error mapping, not for logic.
4. Everything after that is ordered by the table below.

## Open tickets

<!-- BEGIN GENERATED: open tickets (regenerate: python scripts/tickets_index.py --write) -->

_15 open (P1 5 · P2 7 · P3 3 · P4 0) · 0 closed → [REVIEW_REPORT.md](../REVIEW_REPORT.md)._

### 🔴 P1 — Highest (5)

| ID | Title | Effort | Depends on / note |
|---|---|---|---|
| [CORE-01](open/CORE-01.md) | Actor model and permission engine in the domain core | ~3 d | Everything references this — nothing else starts before it is reviewed. |
| [CORE-02](open/CORE-02.md) | Audit recorder — one event per mutation, in the mutation's transaction | ~2 d | CORE-01, DB-01 |
| [DB-01](open/DB-01.md) | Apply the baseline schema and prove row-level security with negative tests | ~2 d | — |
| [OPS-01](open/OPS-01.md) | Build chain and release package: wrapper, lockfile, image, signed multi-arch release | ~2 d | Blocks every backend and frontend package — no ./gradlew and no yarn.lock exist. |
| [SEC-01](open/SEC-01.md) | Credential issuance and authentication for humans and agents | ~3 d | CORE-01, DB-01 |

### 🟠 P2 — High (7)

| ID | Title | Effort | Depends on / note |
|---|---|---|---|
| [API-01](open/API-01.md) | REST API skeleton with a contract-first OpenAPI document | ~3 d | CORE-01, SEC-01, CORE-03 |
| [CORE-03](open/CORE-03.md) | Ticket lifecycle, key allocation and the closure gate | ~3 d | CORE-01, CORE-02 |
| [CORE-04](open/CORE-04.md) | Comments, mentions and the review record | ~2 d | CORE-02, CORE-03 |
| [MCP-01](open/MCP-01.md) | MCP server with the orientation and read tools | ~3 d | CORE-01, SEC-01, CORE-03 · Depends on API-01 only for the shared error taxonomy, not for logic. |
| [MCP-02](open/MCP-02.md) | MCP mutating tools with idempotency and structured gate errors | ~2 d | MCP-01, CORE-04 |
| [WEB-01](open/WEB-01.md) | Frontend shell — routing, authentication, generated API client | ~2 d | API-01 |
| [WEB-02](open/WEB-02.md) | Ticket list and detail views, mobile-first | ~3 d | WEB-01, CORE-04 |

### 🟡 P3 — Medium (3)

| ID | Title | Effort | Depends on / note |
|---|---|---|---|
| [CORE-05](open/CORE-05.md) | Markdown ticket import and export with round-trip fidelity | ~2 d | CORE-04 |
| [DOC-01](open/DOC-01.md) | Deployment guide and the self-hosting path | ~1 d | API-01, WEB-01 |
| [MCP-03](open/MCP-03.md) | MCP resources and prompts | ~1 d | MCP-02, CORE-05 |

### ⚪ P4 — Nice-to-have (0)

_none._
<!-- END GENERATED: open tickets -->
