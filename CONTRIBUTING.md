# Contributing to Nodera

Contributions are welcome — from people and from AI agents. Both follow the same protocol and
both are reviewed the same way. That is not a slogan; it is the product's premise applied to its
own development.

---

## Before you write anything

**Read [`docs/VISION.md`](docs/VISION.md) § 3 — the scope fence.** It lists what Nodera
deliberately does not build: chat, CI/CD, git hosting, an agent runtime, custom field builders.
A change that crosses it will be declined however well built, and reading the list first saves
us both the exchange.

Then read [`docs/INDEX.md`](docs/INDEX.md). Every rule in this repository is reachable from it.

## The short version

1. Pick or open a ticket. `tickets/INDEX.md` is the working order.
2. **Plan before code.** Files, justification, acceptance criteria, test plan.
3. Implement, running the gates after each chunk rather than at the end.
4. **Get an independent review** — in a sub-agent, not inline in the context that wrote the code.
5. Fix BLOCKING findings, re-test, review again.
6. Open a pull request with the template filled in honestly.

Full reference: [`docs/PROJECT_MANAGEMENT.md`](docs/PROJECT_MANAGEMENT.md).

## Setting up

Requires Docker, JDK 21 and Node 22.

```bash
cp .env.example .env && make up && make migrate && make seed
```

```bash
make check
```

`make check` runs everything CI runs. If it is green locally it will be green in CI, with one
exception worth knowing: the backend tests need a running Docker daemon for Testcontainers, and
skipping them locally is the most common cause of a surprise red build.

**`make dev` also starts the backend and frontend, and those do not run yet** — they are the open
tickets. The database, the migrations and every gate do work today.

## The rules that will get a pull request declined

These are the twelve in [`skills/critical-invariants.md`](skills/critical-invariants.md). The
five that catch newcomers most often:

1. **Never branch on `actor.kind` to decide what is permitted.** No `is_bot`, no
   `if (actor.isHuman)` gating a capability. It is for display and audit only. This is invariant
   #1 because it is the one that ends the product's premise, and it is usually broken by someone
   being helpful.
2. **One permission engine.** REST and MCP call the same `PermissionService`. No second path.
3. **Every mutation writes exactly one audit event, in its own transaction.**
4. **Migrations are forward-only.** Never edit an applied one; correct it with a new migration.
5. **No `TODO`/`FIXME` comments.** The linter breaks the build. Fix it, drop it with a recorded
   reason, or open a ticket — a comment is none of those.

## Tickets

This repository tracks its own development in Markdown, in `tickets/`. The reasoning is in
[ADR-0004](docs/adr/0004-markdown-tickets-until-self-hosting.md): a half-built ticket system is
the worst place to keep the plan for finishing it, and the Markdown format doubles as the
specification the product must be able to express.

```bash
python scripts/ticket_new.py CORE-06 "Short imperative title" --priority P2 --effort "~1 d"
```

Fill in the body, then regenerate the views and check:

```bash
python scripts/tickets_index.py --write && python scripts/check_tickets.py --check
```

**Never hand-edit the generated tables** in `tickets/INDEX.md`. They are derived from ticket
frontmatter and your edit will be overwritten.

## Language

**English is the only language in this repository** — code, comments, docs, tickets, commit
messages, everything committed.

**The language you speak with your AI assistant is your own business.** Prompt in German, Spanish
or anything else; ship English. This is a property of the artefact, not of the conversation that
produced it, and the gate (`python scripts/lint_language.py`) only ever looks at the artefact.

## Commits and pull requests

Commit message: `type(scope): TICKET-ID — short description`

```
feat(mcp): MCP-01 — orientation and read tools with capability-filtered discovery
fix(core): FIX-03 — closure gate missed blocking findings from earlier rounds
docs(adr): DOC-02 — record the sibling-surface decision
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`.

One logical change per pull request. A pull request that fixes a bug and refactors two unrelated
files is two pull requests, and the reviewer will ask for it to be split — not out of process
attachment, but because the bug fix will be reviewed with less attention than it deserves.

## Reviews

Phase 4 is not optional and runs in a **sub-agent** — a context that did not write the code. The same
session is fine; a fresh chat, a different tool or a person work too. What does not count is checking
the work inline in the conversation that produced it, because that context produced the blind spots
along with the code.

The canonical, tool-neutral prompt is
[`docs/prompts/code-review.prompt.md`](docs/prompts/code-review.prompt.md). Paste it into any
assistant.

Findings are **BLOCKING** or **NON-BLOCKING**. `APPROVED` only at zero BLOCKING. The rubric,
the evidence standard and the scope governor are in
[`skills/code-review.md`](skills/code-review.md).

## Contributing with an AI assistant

The repository is deliberately tool-agnostic. `CLAUDE.md`, `AGENTS.md` and
`.github/copilot-instructions.md` are thin adapters over one source of truth — use whichever your
tool reads, or point any other tool at `docs/INDEX.md` directly. Adding support for a new tool is
one file plus one entry in `scripts/lint_adapters.py`; open a ticket for it.

Read [`docs/AI_COLLABORATION.md`](docs/AI_COLLABORATION.md) for the working contract. Two things
matter more than the rest:

- **Say so in the pull request, and name the accountable human.** This mirrors the accountability
  chain the product is built on: the chain terminates at a person.
- **Never report a gate as green that was not run.** Say which you ran and which you did not.
  "Did not run the backend tests — no Docker in this environment" is honest and useful. A false
  green is the single most damaging thing an assistant can contribute, because it spends the
  reviewer's trust on something that was never checked.

## Reporting a vulnerability

Not here. [`SECURITY.md`](SECURITY.md) has the private route.

## Licence

By contributing you agree that your contributions are licensed under the
[MIT Licence](LICENSE).
