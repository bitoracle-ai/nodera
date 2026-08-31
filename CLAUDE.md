# Nodera — Claude Code

## What this project is

Nodera is an open-source project and issue tracker in which **people and AI agents are the same kind
of participant**. There is no `users` table: an `actor` is a human or an agent, and every assignment,
comment, permission grant, review verdict and audit event references an actor. Multi-project, with a
first-class Model Context Protocol server beside the REST API.

Read the scope fence before proposing anything: `docs/VISION.md` § 3.

Layout: this repository stands alone. Every path in its documentation is relative to the repository
root, and nothing it references resolves outside it.

## Start here, every session

1. **`docs/INDEX.md`** — the hub. Every rule is reachable from it.
2. **`tickets/INDEX.md`** — the working order. No work before this.
3. The next open ticket: `tickets/open/<ID>.md`.
4. The skills it routes to — always `skills/critical-invariants.md`.

Ticket frontmatter is the source of truth; the INDEX tables are generated. Edit the ticket, then
`python scripts/tickets_index.py --write`. Never edit between the generated markers.

## Skills index

Load on demand — not permanently in context.

| Skill | Load before |
|---|---|
| `skills/critical-invariants.md` | **Every** change, without exception. |
| `skills/agent-actors.md` | Identity, permissions, assignment, audit. |
| `skills/backend-kotlin.md` | Any change under `backend/`. |
| `skills/frontend-react.md` | Any change under `frontend/`. |
| `skills/design-system.md` | Any view, component or styling decision. |
| `skills/database-design.md` | Any migration. |
| `skills/mcp-integration.md` | Any change under `backend/api-mcp/`. |
| `skills/secure-coding.md` | Auth, tokens, input handling, anything on the network. |
| `skills/testing.md` | Writing or reviewing tests. |
| `skills/code-review.md` | Every phase-4 review. |

## Hard boundaries — a violation is a BLOCKING finding

- **Never branch on an actor's kind to decide what is permitted.** No `is_bot`, no
  `if (actor.isHuman)` gating a capability. `actor.kind` is for display and audit only. The day a code
  path asks "is this a human?" before deciding what is allowed, the product's premise is gone.
- **One permission engine** — REST and MCP both call `PermissionService`. No second path, no
  MCP-specific shortcut, no trusted-internal bypass.
- **`audit_event` is append-only.** Never add an update or delete path in code, migration or grant.
  Every mutation writes exactly one event, in the mutation's own transaction.
- **Attenuation** — an agent never exceeds its grantor's permissions, re-checked at use time.
- **Server-side scoping** — `project_id` comes from the authenticated context, never from a request
  parameter. Never bypass RLS.
- **Migrations are forward-only.** Never edit an applied migration; correct it with a new one.
- **No secrets in the tree.** Tokens are stored only as Argon2id hashes, never logged, never returned.
- **No `TODO`/`FIXME` comments** — the linter breaks the build. Fix it, drop it with a recorded
  reason, or ticket it per `docs/PROJECT_MANAGEMENT.md` § 8.
- **English only** in everything committed. Speak German with me if you like; write English into the
  repository.
- **Commit when the work is done, without being asked** — a finished package or task goes onto the
  current branch once its gates are green, its review has passed and, for a ticketed package,
  closure is complete. Never mid-task, never on a red gate, never on unreviewed work, never if I
  said not to. Commit subjects **and pull-request titles** you author start with **🤖**:
  `🤖 <type>(<area>): <ID> — <summary>`.
  **Never `git push`** unless I ask in that turn — same for deploying, releasing and publishing.
  `docs/PROJECT_MANAGEMENT.md` § 12.
- **Comments minimal** — only at critical or genuinely complex spots, lean even there; rationale
  goes in the ticket, the ADR or `docs/`. **Do not imitate the tree**: several migrations and Kotlin
  files carry paragraph-length commentary written before this rule. They are not the standard.
  `docs/AI_COLLABORATION.md` § 1.
- Tool entry files (this one and the other adapters listed in `docs/INDEX.md`) change only inside an
  explicit work package, never in passing.

## Commands

```
make dev        # postgres + migrations + backend + frontend
make check      # every CI lane locally, except the CI-only gitleaks scan
make verify-db  # the CI database lane: migrations twice on a throwaway database + schema checks
make help       # all targets
```

Backend, from `backend/`: `./gradlew build` · `./gradlew ktlintCheck detekt` · `./gradlew test`
(Testcontainers needs Docker running; there is no root `gradlew`).
Frontend, from `frontend/`: `yarn typecheck` · `yarn lint` · `yarn test:coverage` · `yarn build`.
Doc/ticket gates: `python scripts/check_tickets.py --check` · `python scripts/lint_adapters.py` ·
`python scripts/lint_docs_index.py` · `python scripts/lint_language.py` · `python scripts/docs_list.py`.
Job ↔ local equivalence: `docs/ci.md`.

Windows gotcha: run the gate scripts with `py` (`py scripts/lint_adapters.py`) or override
`make PY=py`; Git Bash is the recommended shell — the Makefile assumes POSIX `sh`.

## How I want you to work

**Five phases, and phase 4 is not optional** (`docs/PROJECT_MANAGEMENT.md` § 3):

1. **Overview** — read the ticket and the skills before touching anything.
2. **Plan before code** — files, justification, acceptance criteria, test plan. Package ≥ ~1 day or
   carrying a structural decision → persist it as `docs/plan/<ID>.md`.
3. **Implement** file by file, gates after each chunk rather than at the end.
4. **Independent review** — spawn the `reviewer` subagent (`.claude/agents/reviewer.md`). The same
   session is fine; a subagent has its own context, and that is the whole point. What is not a review:
   checking the work inline in the conversation that wrote it.
5. **Findings** — fix BLOCKING, re-test, review again. NON-BLOCKING fixed in the same session by
   default. A session creates at most as many tickets as it closes.

**Honesty rules that matter more here than the code style:**

- Never report a gate as green that you did not run. Say which you ran and which you did not.
- "Current state (honest)" in a ticket means honest — describe what is there, not what was intended.
- A safety claim ("this guard prevents X") ships with a paired-negative test that is demonstrably red
  when the guard is disabled. Otherwise it is an assertion, not a guarantee.

## When I say — you do

| I say | You do |
|---|---|
| "done" | Closure per `docs/PROJECT_MANAGEMENT.md` § 9: criteria checked, gates green, review recorded, ticket moved, views regenerated — **and committed**, § 12. |
| "commit" | Stage + commit the current branch, `🤖` subject. Never push. (You do not need me to say it — see the rule above.) |
| "review" | Phase-4 via the `reviewer` subagent (`/nodera-review`). |
| "push" / "deploy" | Deliberately undefined — stop and ask; I act. |

## Slash commands

`/nodera-start` · `/nodera-check` · `/nodera-ticket` · `/nodera-review` · `/nodera-migrate` —
see `.claude/commands/`.
