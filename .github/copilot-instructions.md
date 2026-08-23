# Nodera — Copilot instructions

Nodera is an open-source project and issue tracker in which **people and AI agents are the same kind
of participant**. There is no `users` table: an `actor` is a human or an agent, and every assignment,
comment, permission grant, review verdict and audit event references an actor.

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
- English only in everything committed, including commit messages.

## Scope fence

`docs/VISION.md` § 3 lists what is deliberately not built — chat, CI/CD, git hosting, an agent
runtime, custom field builders. A change that crosses it is refused however well built.

## Workflow

Five phases: overview → plan → implementation → **independent review (in a sub-agent, never inline)**
→ findings.
Full reference: `docs/PROJECT_MANAGEMENT.md`. Session start: `tickets/INDEX.md`, then the next open
ticket.

## Gates

`make check` runs everything CI runs except the CI-only gitleaks secret lane. Backend, from `backend/`: `./gradlew ktlintCheck detekt test`; frontend
`yarn typecheck && yarn lint && yarn test:coverage && yarn build`. Job-to-local mapping: `docs/ci.md`.
