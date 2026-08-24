---
summary: How work is organised in this repository — the ticket system, the session protocol, priorities, the closure protocol, ticket hygiene and the repository language rule.
read_when:
  - Before every session that changes files.
  - Whenever a ticket is created or closed.
  - When unsure about priority, numbering, or which language something must be written in.
---

# Project management — Nodera

> Mandatory before any session that changes files or creates tickets.
> Loaded **on demand**, not permanently in context.

---

## 1. The ticket system, and why it is Markdown

Nodera is a ticket system. It tracks its own development in Markdown files.

That is not irony, it is sequencing: a system cannot host its own backlog before it runs, and a
half-built tracker is the worst possible place to keep the plan for finishing it. The Markdown system
is also the **specification**. The frontmatter fields are the domain model's ticket fields, the body
sections are what a ticket must be able to hold, and the review record is the hardest requirement in
[`VISION.md`](VISION.md) § 5.

When Nodera can host this backlog without loss, it migrates — and the migration is the acceptance test
for the Markdown interchange format (invariant M1). Recorded as
[`adr/0004-markdown-tickets-until-self-hosting.md`](adr/0004-markdown-tickets-until-self-hosting.md).

**The YAML frontmatter is the single source of truth.** Every table view is generated from it:

| File | Role | When to read |
|---|---|---|
| `tickets/INDEX.md` | **Entry point** — hand-written head (status, working order) + **generated** open-ticket tables | **Every session, first** |
| `tickets/open/<ID>.md` | Full specification of that work package | Right after INDEX.md, for the next package |
| `tickets/closed/<ID>.md` | Full record of a closed package, including the review result | When detail on that package is needed |
| `REVIEW_REPORT.md` | **Generated** lean index of closed packages (ID · title · closed date) | On demand |

Regenerate views: `python scripts/tickets_index.py --write`.
Consistency gate: `python scripts/check_tickets.py --check`.
Machine-readable export: `python scripts/tickets_index.py --json`.

> **Token efficiency:** load `tickets/INDEX.md` plus the one ticket file you need. Closure narratives
> live in the closed tickets, never duplicated into an index.

## 2. Session start protocol

1. Read `tickets/INDEX.md`. No work before this step.
2. The first open ticket in the working order is the next package → open `tickets/open/<ID>.md`.
3. Read the relevant skills ([`../skills/README.md`](../skills/README.md)), always including
   `critical-invariants.md`.
4. Only then: work.

## 3. The five-phase workflow

Every work package runs through the same five phases. Phase 4 is not optional and never runs in the
context that wrote the code.

| Phase | What happens | Output |
|---|---|---|
| **1 · Overview** | Read the ticket, the skills it routes to, and the code it names. | Understanding of what is actually there. |
| **2 · Plan** | Files to change and why, acceptance criteria, test plan — **before any line of code**. | A plan. Packages ≥ ~1 day or carrying a structural decision persist it as `docs/plan/<ID>.md`. |
| **3 · Implementation** | File by file. Gates after each chunk, not at the end. | A verifiable diff. |
| **4 · Independent review** | Runs in a **sub-agent** — a fresh context that did not write the code — against the acceptance criteria. | Findings, each classified **BLOCKING** or **NON-BLOCKING**. |
| **5 · Findings** | BLOCKING → fix, re-test, review again. NON-BLOCKING → fix in the same session by default. | 0 BLOCKING, then closure. |

### What "independent" means here

**Phase 4 runs in a sub-agent. The same session is fine; the same context is not.**

The reason is about context, not about calendars: the context that produced the code produced its
blind spots too, and a reviewer carrying it reads the diff looking for confirmation. A sub-agent
starts clean by construction, which is why it is *the* mechanism rather than one option among
several — you do not have to remember to be impartial, and a reviewer who has not seen the
reasoning cannot be persuaded by it.

So this is a valid phase 4: implement in a session, then spawn a reviewer sub-agent from that same
session. This is not: continue the implementation conversation and ask it to check its own work.

A human reviewer, a different tool or a separate session all satisfy the rule too — they are just
not required. What is required is that the reviewing context did not write the code.

## 4. Repository language

**English is the only language in this repository.** Documentation, tickets, plans, ADRs, skills,
code, comments, log and exception messages, test names, file and directory names, commit messages and
pull-request descriptions.

**The language you speak with an AI assistant is free and unrelated.** Prompting, planning and
reviewing in German, Spanish or anything else is fine and expected; what is written *into the
repository* is English. This is a property of the artefact, not of the conversation that produced it.

The one exception this repository actually needs: **product strings in a user's language** — i18n
catalogues, user-facing mail copy, fixtures asserting a reply language. Listed with a reason in
`scripts/language_allowlist.txt`; single lines via a `lang-ok:` marker on that line.

**The reason is mandatory** — the gate rejects an allowlist entry without one, so an exception can
never be added silently.

Gate: `python scripts/lint_language.py`. Two honest limits, stated because they are properties of the
detector rather than of the project:

1. It detects non-English **prose**, not non-English **labels**. A terse foreign word in a heading or
   table cell can slip through. A green run means "no foreign prose"; it is not proof of absence, and
   the diff still wants a reader.
2. A new file can be added to the baseline in the same commit that introduces it. That is a visible,
   reviewable edit — nothing enforces it away, and a reviewer should push back.

## 5. Priorities

| Criterion | Priority |
|---|---|
| A security defect is exploitable, or a credential reached the repository | **P1** |
| Data loss, corruption, or an audit trail that can be falsified | **P1** |
| A first-class-actor capability ([`VISION.md`](VISION.md) § 4) is broken or absent | **P1** |
| Blocks a release, or blocks another open work package | **P2** |
| Improvement to an existing system, new capability inside the scope fence | **P3** |
| Nice-to-have, cosmetic, experimental | **P4** |

**Rules:**

1. **P1 before P2 before P3 before P4.** No low-priority package while a P1 is open.
2. **Respect dependencies** — `depends_on` in the frontmatter names the preconditions.
3. **Never several packages at once.** Finish one completely, then start the next. Independent
   packages may run in parallel across separate sessions, never within one.

## 6. ID prefixes

| Area | Prefix | Example |
|---|---|---|
| Domain model, core services | `CORE-` | `CORE-01` |
| Database schema and migrations | `DB-` | `DB-01` |
| REST API | `API-` | `API-01` |
| MCP server | `MCP-` | `MCP-01` |
| Frontend | `WEB-` | `WEB-01` |
| Identity, permissions, audit | `SEC-` | `SEC-01` |
| CI/CD, build, release | `CI-` | `CI-01` |
| Deployment, operations, release execution | `OPS-` | `OPS-01` |
| Documentation and process | `DOC-` | `DOC-01` |
| Defect in committed code | `FIX-` | `FIX-01` |

## 7. Registering a new work package

When a piece of work, an idea or a defect is mentioned, it is **entered into the system immediately** —
not merely acknowledged in a reply.

1. Determine the priority (§ 5).
2. Assign the next free ID in the prefix range (§ 6).
3. Scaffold: `python scripts/ticket_new.py <ID> "<title>" [--priority P2] [--effort "~1 d"]` — creates
   the file from `tickets/TEMPLATE.md`, refuses an ID collision, and regenerates the views.
4. Fill the body and the frontmatter facts (`depends_on`, and a one-line `note:` where a blocker
   matters), then `python scripts/tickets_index.py --write`.
5. `python scripts/check_tickets.py --check` must be green.

Structural decisions go to `docs/adr/` as an ADR, **not** into the ticket body.

## 8. Ticket hygiene — when a review finding becomes a ticket

**The default is fix-now-or-drop. A follow-up ticket is the exception and has to earn itself.** Filing
a ticket never *feels* wrong, which is exactly why it needs a gate — without one the count only rises,
because every review yields findings and every finding looks like it wants a home.

### The primary rule: scope the relief to the reasoning

**A decision covers the class its argument covers, and states the corollaries it creates.** A general
premise with a narrow fix guarantees a follow-up for the remainder. A rule that creates a new
obligation states it in the same breath. Ask this *before* filing anything — it prevents chains that
the test below would wave through, because each link is individually justified.

### The ticket test, for what is left over

A non-blocking finding gets its own ticket **only** when it hits one of these. Name the one that
carried it, in the ticket, so a reader can check the judgement rather than trust it.

| Criterion | Why it justifies a ticket |
|---|---|
| **Foreign subtree** | The closing package must not edit it. A `WEB-` package changing `backend/persistence/` is scope creep with a rationale. |
| **Reserved surface** | Another rule reserves the change to a package of a particular *kind* — entry files, this hub, the scope fence — **and the closing package is not of that kind.** Where it already is, the reservation is *already satisfied*: fix it in place and file nothing. |
| **Structural decision** | It needs an owner's answer, not a contributor's edit. |
| **External dependency** | It cannot be done from this repository at all. |
| **Live risk** | Something deployed or imminent is exposed. |

Everything else is **fixed in the same session** — it is adjacent work in files the package already
has open — or **dropped with a recorded reason**. "Noted, not doing this, because …" in the review
result is a legitimate and preferable outcome; a ticket nobody will ever run is worse than a recorded
decision not to act.

**Net rule:** a session creates at most as many tickets as it closes.

**Depth is a diagnostic, not a prohibition.** When a review-spawned package is about to spawn another
in the same subtree, that is evidence the primary rule was missed upstream: widen the current rule
instead of filing. It is not forbidden — but such a ticket **records, in its own file, why the upstream
rule could not simply be widened.** One sentence. That makes a miss visible when it happens rather
than a session later, which is the difference between a rule and an exhortation.

**Do not achieve this by reviewing less.** The reviewer's job is unchanged and every blocking finding
still stops closure. The change is downstream of the finding — in what the closing contributor does
with it — never upstream in what the reviewer is willing to say.

**Never a `TODO` comment.** The linter breaks the build on `TODO`/`FIXME`. A finding is fixed, dropped
with a reason, or ticketed. A comment is none of those; it is a finding hidden where no index can
reach it.

## 9. The closure protocol

All steps mandatory.

1. **Every acceptance criterion met** — each `[ ]` becomes `[x]`, and each is actually true.
2. **Gates green:** `make check` — no error.
3. **Independent review returned with 0 BLOCKING** — phase 4, run in a sub-agent (§ 3).
4. **Move the file** `tickets/open/<ID>.md` → `tickets/closed/<ID>.md`; set `status: closed` and
   `closed: YYYY-MM-DD` (mandatory — the closed index sorts by it).
5. **Record the review result** as `## Review result` in that same file. `REVIEW_REPORT.md` carries no
   narrative.
6. **Regenerate views:** `python scripts/tickets_index.py --write`.
7. **Update hand-written mentions** — if the INDEX head links the ticket as `open/<ID>.md`, repoint it.
8. **`python scripts/check_tickets.py --check` green.**
9. **Propose a commit message** (`feat(<area>): <ID> — <short description>`). Commit only when the user
   asks; never push unasked.

## 10. Plans

A work package **≥ ~1 day** or carrying a **structural decision** persists its phase-2 plan as
`docs/plan/<ID>.md` with a status header (`draft | active | implemented | superseded`). After
implementation it is **stamped `implemented`, never deleted** — the reasoning is the part that stays
useful. Trivial packages keep the plan in chat, no ceremony.

## 11. Retrospectives

A P1 or P2 defect that took ≥ ~0.5 day to understand gets `docs/retro/<slug>.md` when it closes:
symptom · cause with exact file paths · fix with the commit reference · a repeatable check · what to
watch for.
