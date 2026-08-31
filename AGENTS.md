# Nodera — Agent Instructions

## Project

Nodera is an open-source project and issue tracker in which **people and AI agents are the same kind
of participant**. There is no `users` table: an `actor` is a human or an agent, and every assignment,
comment, permission grant, review verdict and audit event references an actor. Multi-project by
design, with a first-class Model Context Protocol server beside the REST API.

Scope fence — what is deliberately **not** built: `docs/VISION.md` § 3. A change that crosses it is
refused in review, however well built.

Layout: this repository stands alone. Every path in its documentation is relative to the repository
root, and nothing it references resolves outside it.

## Language

- **English is the only language in this repository** — docs, tickets, skills, plans, ADRs, code,
  comments, log and exception messages, test names, commit messages.
- The language you speak *with* an assistant is free and unrelated: prompt in any language, ship English.
- Only exception: product strings in a user's language (i18n catalogues, user-facing mail copy), listed
  with a mandatory reason in `scripts/language_allowlist.txt`; single lines via a `lang-ok:` marker.
- Gate: `python scripts/lint_language.py`.

## Stack

Kotlin 2.x / JDK 21 · Ktor · Exposed · Flyway · PostgreSQL 16+ with row-level security ·
kotlinx.serialization · Kotest + Testcontainers · React 19 / TypeScript / Vite / Tailwind ·
Vitest + Playwright · Model Context Protocol (stdio + streamable HTTP) · GitHub Actions · Docker Compose.

## Documentation

All rules, guidelines and workflows: **`docs/INDEX.md`** — read it first.

Session start: `tickets/INDEX.md` → working order → `tickets/open/<ID>.md`.
Ticket frontmatter is the source of truth: the INDEX/REVIEW_REPORT tables are **generated** — edit the
ticket, run `python scripts/tickets_index.py --write`, never edit between the markers.
Work-package lifecycle and closure protocol: `docs/PROJECT_MANAGEMENT.md`.

## Boundaries (hard — a violation is a BLOCKING finding)

- **Commit when the work is done, without being asked** — a finished package or task goes onto the
  current branch once its gates are green, its review has passed and, for a ticketed package,
  closure is complete. Never mid-task, never on a red gate, never on unreviewed work, never if the
  user said not to. Commit subjects **and pull-request titles** an agent authors start with
  **🤖**: `🤖 <type>(<area>): <ID> — <summary>`. **Never `git push`** unless the user asks explicitly in
  that turn — same for deploying, releasing and publishing. `docs/PROJECT_MANAGEMENT.md` § 12.
- **Comments minimal** — only at critical or genuinely complex spots, lean even there; rationale
  goes in the ticket, the ADR or `docs/`. **Do not imitate the tree**: several migrations and Kotlin
  files carry paragraph-length commentary written before this rule. They are not the standard.
  `docs/AI_COLLABORATION.md` § 1.
- **Never branch on an actor's kind to decide what is permitted.** No `is_bot`, no `if (actor.isHuman)`
  guarding a capability. `actor.kind` is for display and audit only. This is the premise of the whole
  product (invariant T2).
- **One permission engine.** REST and MCP both call `PermissionService`. No MCP-specific shortcut, no
  "trusted internal" bypass, no second code path.
- **The audit trail is append-only.** Never add an update or delete path to `audit_event`, in code, in
  a migration, or in a role grant. Every mutation writes exactly one event in the mutation's own
  transaction.
- **Never widen an agent's permissions beyond its grantor's** — attenuation is re-checked at use time,
  not only at grant time (invariant C1).
- No hardcoded credentials or tokens. Secrets fail closed, are never logged, and never appear in an API
  response. A token is stored only as an Argon2id hash.
- Tenant scoping is server-side: `project_id` comes from the authenticated context, never from a
  request parameter. Never bypass RLS.
- `db/migrations/`: forward-only, expand/contract. **Never edit an applied migration** — correct it
  with a new one.
- No `TODO` / `FIXME` comments (the linter breaks the build). A finding is fixed, dropped with a
  recorded reason, or ticketed per `docs/PROJECT_MANAGEMENT.md` § 8.
- Entry/adapter files (this file, `.github/copilot-instructions.md`, `.github/instructions/`) are
  changed **only within an explicit work package**, never in passing.
- Never use git locking flags; report a stale `.git/*.lock`, never remove it programmatically.

## Build & Test

```
make dev        # postgres + migrations + backend + frontend
make check      # every CI lane locally, except the CI-only gitleaks scan
make verify-db  # the CI database lane: migrations twice on a throwaway database + schema checks
make help       # all targets
```

- Backend, from `backend/`: `./gradlew build` · `./gradlew ktlintCheck detekt` · `./gradlew test`
  (Testcontainers needs a running Docker daemon; there is no root `gradlew`).
- Frontend (from `frontend/`): `yarn typecheck` · `yarn lint` · `yarn test:coverage` · `yarn build`.
- Database migrations, from `backend/`: `./gradlew :app:run --args=migrate` against the compose
  Postgres (there is no Gradle Flyway plugin — the app is its own migrator, `docs/ci.md`).
- Doc/ticket gates (always run in CI): `python scripts/docs_list.py` ·
  `python scripts/generate_docs_map.py --check` · `python scripts/check_tickets.py --check` ·
  `python scripts/lint_adapters.py` · `python scripts/lint_docs_index.py` ·
  `python scripts/lint_language.py`.
- Full job ↔ local equivalence table: `docs/ci.md`.
- Windows: run the gate scripts with `py` (`py scripts/lint_adapters.py`) or override `make PY=py`;
  use Git Bash — the Makefile assumes POSIX `sh`.

## User verbs (org protocol, pinned for this repo)

| The user says | The assistant does |
|---|---|
| "done" | Closure per `docs/PROJECT_MANAGEMENT.md` § 9: criteria checked, gates green, review recorded, ticket moved, views regenerated — **and committed**, § 12. |
| "commit" | Stage + commit the current branch, `🤖` subject. Never push. (It does not need asking — see the rule above.) |
| "review" | Independent phase-4 review in a sub-agent — canonical prompt `docs/prompts/code-review.prompt.md`. |
| "push" / "deploy" | Deliberately undefined — stop and ask; the user acts. |

## Workflow (short form — full reference `docs/PROJECT_MANAGEMENT.md` § 3)

1. **Overview:** read the ticket + the skills it routes to — always `skills/critical-invariants.md`
   plus the domain skill.
2. **Plan before any line of code** (files + justification, acceptance criteria, test plan). Package
   ≥ ~1 day or carrying a structural decision → persist as `docs/plan/<ID>.md`.
3. **Implementation** file by file; run the gates after each chunk, not at the end. Every new frontend
   component/hook/utility ships with a test file. A safety claim ships with a paired-negative test that
   is demonstrably red when the guard is disabled.
4. **Independent review** — run it in a **sub-agent**, i.e. a context that did not write the code. The
   same session is fine; reviewing inline in the implementing conversation is not. Findings as BLOCKING
   / NON-BLOCKING per `skills/code-review.md`. Tool-neutral prompt:
   `docs/prompts/code-review.prompt.md`.
5. **Findings:** fix BLOCKING → re-test → review again. NON-BLOCKING fixed in the same session by
   default; a ticket only through the ladder in `docs/PROJECT_MANAGEMENT.md` § 8. Never a TODO comment.

## Local guides (read before working in the subtree)

`backend/AGENTS.md` · `frontend/AGENTS.md` · `db/migrations/AGENTS.md`.

## Coding standards (distilled — full reference: `skills/`, entry point `docs/INDEX.md`)

- **Kotlin:** the `:domain` module is framework-free — no Ktor, no SQL, no JSON, no logging framework
  in it, enforced by the build. Adapters never decide permissions, never transition state, never write
  audit events, never issue SQL.
- Every use case takes `ActorContext` as its **first parameter**. It is never ambient, never a
  thread-local, never a global — that is what makes "who is acting" impossible to forget.
- **SQL:** identifiers are unquoted lowercase `snake_case`. No string interpolation into SQL, ever.
- **Frontend:** never call `fetch` directly in a component — only through `src/api/`, whose types are
  generated from the OpenAPI document. Render `actor.kind`; never infer it from a name.
- **Mobile-first:** build every view at 375 px first, then widen. Touch targets are at least
  44x44 px; the primary action sits in thumb reach, destructive actions deliberately do not.
- **Design:** exactly two themes, light and dark — following the OS is a selection mode, not a third
  theme. Colour is always a semantic token, never a literal; a token missing from either theme is a
  defect. Agent output gets the same visual weight as human output: no muting, no tinting, no
  collapsing.
- Timestamps are `timestamptz`, always UTC.
