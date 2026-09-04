# Nodera — Copilot instructions

Nodera is an open-source project and issue tracker in which **people and AI agents are the same kind
of participant**. There is no `users` table: an `actor` is a human or an agent, and every assignment,
comment, permission grant, review verdict and audit event references an actor.

Maintained by **bitoracle.ai**: direction, priorities and the standards enforced here are set by the
maintainers' management process, and its decisions bind this repository. A change runs through this
repository's own process — phases, review, gates, closure — and needs nothing outside this checkout
(`docs/PROJECT_MANAGEMENT.md` § 14).

**Read `docs/INDEX.md` first** — every rule in this repository is reachable from it. Path-specific
rules live in `.github/instructions/*.instructions.md` and apply automatically.

## Stack

Kotlin 2.x / JDK 21 · Ktor · Exposed · Flyway · PostgreSQL 16+ with row-level security ·
React 19 / TypeScript / Vite / Tailwind · Model Context Protocol · GitHub Actions.

## Hard rules — a violation is a BLOCKING review finding

- Never branch on `actor.kind` to decide what is permitted. No `is_bot`, no `if (actor.isHuman)`
  gating a capability. It is for display and audit only.
- One permission engine: REST and MCP both call `PermissionService`. No second code path.
- `audit_event` is append-only. Every mutation writes exactly one event in its own transaction.
- An agent never exceeds its grantor's permissions — attenuation is re-checked at use time.
- `project_id` comes from the authenticated context, never from a request parameter.
- Migrations are forward-only; never edit an applied one.
- No secrets in the tree. Tokens are stored only as Argon2id hashes.
- No `TODO`/`FIXME` comments — the linter breaks the build.
- A test that needs a database, a service or the stack runs in an environment created for that run
  — Testcontainers, or a compose project under its own `-p <name>` — never the developer's `make up`
  stack or the volume behind it. What it created is removed afterwards. `make verify-db` is the one
  documented exception: its own database, inside the developer's Postgres, which it leaves running.
  `skills/testing.md`.
- Never report a cached lane as a test run: on an unchanged tree Gradle serves the backend lane from
  its build cache or skips it as up to date, and `make check` is green without a test executing.
  `docs/ci.md`.
- English only in everything committed, including commit messages.
- Comments minimal — only at critical or genuinely complex spots, lean even there; rationale goes in
  the ticket, the ADR or `docs/`. Do not imitate the dense comments already in the tree; they predate
  this rule and are not the standard.
- Finished work is committed on the current branch without being asked, once the gates are green and
  the review has passed — never mid-task, never on a red gate, never on unreviewed work, never if the
  user said not to. Commit subjects and pull-request titles an agent authors start with `🤖`. **Never `git push`** unless the
  user asks in that turn. Full rule: `docs/PROJECT_MANAGEMENT.md` § 12.

## Scope fence

`docs/VISION.md` § 3 lists what is deliberately not built — chat, CI/CD, git hosting, an agent
runtime, custom field builders. A change that crosses it is refused however well built.

## Workflow

Five phases: overview → plan → implementation → **independent review (in a sub-agent, never inline)**
→ findings.
Full reference: `docs/PROJECT_MANAGEMENT.md`. Session start: `tickets/INDEX.md`, then the next open
ticket.

## Gates

`make check` runs everything CI runs except the CI-only gitleaks secret lane and `make verify-db`,
which applies the migrations and is its own target. Backend, from `backend/`:
`./gradlew ktlintCheck detekt test`; frontend
`yarn typecheck && yarn lint && yarn test:coverage && yarn build`. Job-to-local mapping: `docs/ci.md`.
