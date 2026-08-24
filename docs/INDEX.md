---
summary: The documentation hub — the single entry point that routes every contributor, human or agent, to the right source of truth for the work in front of them.
read_when:
  - At the start of every session, before changing anything.
  - Whenever it is unclear which document governs a decision.
  - When adding a document, so it is reachable rather than merely present.
---

# Documentation index — Nodera

This is the **hub**. Every rule, guide and decision in this repository is reachable from here. Tool
entry files (`CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md`) are thin adapters that point
here; they never carry knowledge of their own.

---

## Session start

1. **[`../tickets/INDEX.md`](../tickets/INDEX.md)** — the working order. No work before this.
2. **The next open ticket** — `tickets/open/<ID>.md`, the full specification of that work package.
3. **The relevant skills** — [`../skills/README.md`](../skills/README.md), always including
   [`critical-invariants.md`](../skills/critical-invariants.md).
4. Only then: work.

## The two-layer architecture

Knowledge lives in **layer 2** and is written once. **Layer 1** is a thin adapter per tool, holding
distillates and pointers only.

| Layer | Files | Rule |
|---|---|---|
| **2 — source of truth** | `docs/`, `skills/` | Change a rule here first. |
| **1 — adapters** | `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md`, `.github/instructions/*.instructions.md` | Distillates and pointers. Pull them along in the same work package. |

Rationale and alternatives: [`adr/0002-provider-agnostic-agent-adapters.md`](adr/0002-provider-agnostic-agent-adapters.md).
Mechanical check: `python scripts/lint_adapters.py`.

## Product and scope

| Document | Governs |
|---|---|
| [`VISION.md`](VISION.md) | What Nodera is, the first-class-actor premise, and the **scope fence**. A change that crosses it is refused in review. |
| [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) | Entities and their invariants. The contract every migration and domain service answers to. |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module boundaries, chosen libraries, what may never live in an adapter. |
| [`MCP.md`](MCP.md) | The Model Context Protocol surface — tools, resources, prompts, errors. |
| [`API_CONTRACT.md`](API_CONTRACT.md) | The REST contract. Frontend types are generated from it; it is not written by hand twice. |

## Process

| Document | Governs |
|---|---|
| [`PROJECT_MANAGEMENT.md`](PROJECT_MANAGEMENT.md) | Work-package lifecycle, priorities, closure protocol, repository language. **Mandatory before creating or closing a ticket.** |
| [`AI_COLLABORATION.md`](AI_COLLABORATION.md) | The working contract for humans and AI assistants: minimum capabilities, role routes, handoff. |
| [`ci.md`](ci.md) | Every CI job and its local equivalent. |
| [`adr/README.md`](adr/README.md) | Architecture decision records — structural choices and what was rejected. |
| [`plan/README.md`](plan/README.md) | Persisted plans for larger work packages. |
| [`prompts/README.md`](prompts/README.md) | Reusable, tool-neutral prompts — the canonical review prompt lives here, not in a tool's private config. |

## Operations

| Document | Governs |
|---|---|
| [`ops/deploy.md`](ops/deploy.md) | Running `compose.prod.yml`: install, verification, update, rollback, and the boundaries the topology must keep. |
| [`ops/backup-restore.md`](ops/backup-restore.md) | The `pgdata` volume — what a dump does **not** carry, the restore sequence that was walked, and the drill. |

## Skills — loaded on demand, not permanently in context

Catalogue: [`../skills/README.md`](../skills/README.md).

| Skill | Load before |
|---|---|
| [`critical-invariants.md`](../skills/critical-invariants.md) | **Every** change, without exception. |
| [`agent-actors.md`](../skills/agent-actors.md) | Anything touching identity, permissions or the audit trail. |
| [`backend-kotlin.md`](../skills/backend-kotlin.md) | Any change under `backend/`. |
| [`frontend-react.md`](../skills/frontend-react.md) | Any change under `frontend/`. |
| [`design-system.md`](../skills/design-system.md) | Any view, component or styling decision. |
| [`database-design.md`](../skills/database-design.md) | Any migration. |
| [`mcp-integration.md`](../skills/mcp-integration.md) | Any change under `backend/api-mcp/`. |
| [`secure-coding.md`](../skills/secure-coding.md) | Auth, tokens, input handling, anything reaching the network. |
| [`testing.md`](../skills/testing.md) | Writing or reviewing tests. |
| [`code-review.md`](../skills/code-review.md) | Every phase-4 review. |

## Mandatory-reading rules

Some changes carry consequences a reviewer cannot reconstruct from the diff. For these, the listed
source is **not optional**:

| If the change touches | You must first read |
|---|---|
| Identity, credentials, permissions, audit | [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) §§ 2, 4, 9 + [`../skills/agent-actors.md`](../skills/agent-actors.md) |
| A database migration | [`../skills/database-design.md`](../skills/database-design.md) + [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) |
| An MCP tool or resource | [`MCP.md`](MCP.md) in full |
| The scope of the product | [`VISION.md`](VISION.md) § 3 |
| A tool entry file or scoped guide | [`adr/0002-provider-agnostic-agent-adapters.md`](adr/0002-provider-agnostic-agent-adapters.md) |

## Maintenance

- A rule changes in **layer 2 first**, then its distillates are pulled along in the same work package.
- Root adapters (`CLAUDE.md`, `AGENTS.md`) never reference each other — each stands alone.
- A new document is **linked from this hub in the same commit**. Unreachable knowledge is knowledge no
  tool has; `python scripts/lint_docs_index.py` fails otherwise.
- Every knowledge document carries `summary` and `read_when` frontmatter
  (`python scripts/docs_list.py`).
- Entry files and this hub are edited **only within an explicit work package**, never in passing.
