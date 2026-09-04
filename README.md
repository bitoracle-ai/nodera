# Nodera

**Project and issue tracking where people and AI agents are the same kind of participant.**

[![CI](https://github.com/bitoracle-ai/nodera/actions/workflows/ci.yml/badge.svg)](https://github.com/bitoracle-ai/nodera/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

Every tracker meets AI agents the same way: a bot account, a token borrowed from a human, a webhook.
The agent wears someone's identity — and from that moment you cannot tell who did what, cannot narrow
what the agent may do, cannot assign it work, and cannot treat its output as a peer's.

Those are not integration problems. They are data-model problems, and Nodera fixes them at the bottom:

> **There is no `users` table.** An `actor` is a human or an agent. Assignments, comments, permission
> grants, review verdicts and audit events all reference an *actor*. An agent has its own identity,
> its own credentials, its own scoped permissions and its own accountable history.

## What that buys you

| | |
|---|---|
| **Identity** | An agent authenticates as itself. Every surface shows whether an actor is a person or an agent — from a field, never from a name pattern. |
| **Permissions** | Per-project, per-capability grants. An agent can never exceed the permissions of whoever granted them, re-checked at *use* time — revoke a person's access and their agents lose it in the same instant. |
| **Assignments** | An agent holds a ticket the way a person does. Same column, same rules, one accountable assignee. |
| **Comments** | Agents are peers in the thread: mentioned, replied to, attributed, permanently linked to the work. |
| **Audit** | Append-only at the database level. Every change records the actor, the surface (web / REST / MCP), the tool used, and who it was acting on behalf of. |
| **MCP** | A first-class Model Context Protocol server, not a wrapper. Same domain services, same permission engine, same audit trail as the web app. `tools/list` is filtered by the caller's actual grants. |

Multi-project by design: one deployment, many independent projects, separate ticket key spaces,
enforced by Postgres row-level security rather than by a `WHERE` clause someone has to remember.

## Status

**Pre-alpha — the foundation is being built in the open.** The architecture, domain model, MCP surface
and database schema are specified and reviewable; the implementation is in progress. See
[`tickets/INDEX.md`](tickets/INDEX.md) for exactly what is done and what is next. Nothing here is
production-ready yet, and the documentation says so wherever it is true.

## Stack

| Layer | Technology |
|---|---|
| Frontend | React 19 · TypeScript · Vite · Tailwind CSS — mobile-first, one responsive app |
| Backend | Kotlin · Ktor · hexagonal, six Gradle modules |
| Database | PostgreSQL 16+ · row-level security · Flyway, forward-only migrations |
| Agent surface | Model Context Protocol — stdio and streamable HTTP |
| CI | GitHub Actions — one aggregated required check |

## Quick start

Requires Docker, JDK 21, Node ≥ 22.22.0 (`.nvmrc` pins it) with yarn 1 (classic), GNU make and
Python 3 — the full list and a Windows note are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

```bash
git clone https://github.com/bitoracle-ai/nodera.git
```

```bash
cd nodera && cp .env.example .env
```

```bash
make up && make migrate && make seed
```

That much works today: Postgres starts, the migrations apply, and the seed creates a project,
a human actor and an agent actor whose grants are deliberately narrower than its owner's — the
model, running, before any application code exists.

**`make dev` additionally starts the backend and frontend, and those do not run yet.** `backend/`
and `frontend/` hold build configuration and module structure; the implementations are the open
tickets. `make help` lists every target, and `make check` runs every gate that does work — except
`make verify-db`, which applies the migrations and is a target of its own.

### Connecting an agent

```json
{
  "mcpServers": {
    "nodera": {
      "command": "nodera-mcp",
      "args": ["--transport", "stdio"],
      "env": {
        "NODERA_API_BASE_URL": "http://localhost:8080",
        "NODERA_TOKEN": "nod_pat_…"
      }
    }
  }
}
```

Tokens will be minted in the web app under **Settings → Agents** once that surface exists — the
web app is one of the open tickets. The full tool and resource surface is in
[`docs/MCP.md`](docs/MCP.md).

## Documentation

Start at [`docs/INDEX.md`](docs/INDEX.md) — it routes to everything else.

| Document | Read it when |
|---|---|
| [`docs/VISION.md`](docs/VISION.md) | Before proposing a feature. Contains the scope fence. |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Before adding a module, dependency or surface. |
| [`docs/DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md) | Before touching a migration or a domain service. |
| [`docs/MCP.md`](docs/MCP.md) | Before changing an MCP tool, or when connecting an agent. |
| [`docs/PROJECT_MANAGEMENT.md`](docs/PROJECT_MANAGEMENT.md) | Before creating or closing a work package. |
| [`docs/AI_COLLABORATION.md`](docs/AI_COLLABORATION.md) | When contributing with any AI assistant. |
| [`skills/`](skills/README.md) | Domain-specific rules, loaded on demand. |

## Contributing

Contributions are welcome — from people and from agents. Both follow the same protocol, and both are
reviewed the same way.

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first, then
[`docs/AI_COLLABORATION.md`](docs/AI_COLLABORATION.md) if you are working with an AI assistant. The
repository is **tool-agnostic**: `CLAUDE.md`, `AGENTS.md` and `.github/copilot-instructions.md` are
thin adapters over one source of truth, so your choice of assistant is not a quality variable. Adding
support for another tool is one file.

The repository language is English — everything committed, including commit messages. The language you
speak with your assistant is your own business.

## Security

Please do not open a public issue for a vulnerability. [`SECURITY.md`](SECURITY.md) has the private
reporting route and what to expect.

## Licence

[MIT](LICENSE) — a contribution by bitoracle.ai to the open source community.
