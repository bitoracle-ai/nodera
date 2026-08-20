---
summary: What Nodera is, the one premise it is built on (humans and AI agents are the same kind of participant), the scope fence of what will and will not be built, and how the project judges whether it succeeded.
read_when:
  - Before proposing a feature, an integration or an architectural change.
  - When a request seems to require a new actor type, a new permission dimension or a new surface.
  - Before writing anything that describes Nodera to people outside the project.
---

# Vision — Nodera

> **Nodera is a project and issue tracker in which people and AI agents are the same kind of
> participant.** Not a tracker with an AI feature. Not a tracker with a bot integration. A tracker
> whose identity, permission, assignment, discussion and audit model was designed, from the first
> migration, to have two kinds of actor in it.

---

## 1. The premise

Software is increasingly written by mixed teams: people and autonomous agents working the same
backlog. Every established tracker meets that reality the same way — a bot account, an API token
owned by a human, a webhook. The agent borrows a person's identity, and the moment it does, four
things become impossible:

1. **You cannot tell who did it.** The audit trail says a human moved the ticket. A human did not.
2. **You cannot scope what it may do.** The token inherits the person's permissions, all of them. An
   agent that should only comment can close, reassign and delete.
3. **You cannot assign work to it.** Assignment means a person, so agent work is tracked beside the
   system instead of in it — in a chat log, a session transcript, someone's memory.
4. **You cannot review it as a peer.** Its comments are second-class: unattributed, unthreaded,
   invisible to the notification model.

Nodera's premise is that these are not integration problems. They are **data model** problems, and
they can only be fixed at the bottom. So the bottom is where Nodera fixes them:

**An actor is a human or an agent.** Everything that references a participant — an assignment, a
comment, a permission grant, an audit event, a mention, a review verdict — references an *actor*,
never a `user`. An agent has its own identity, its own credentials, its own permission grants and its
own accountable history. It is not a costume worn by a person.

That single decision is what this repository exists to get right. Everything below follows from it.

## 2. What Nodera is

- A **multi-project** work tracker: one deployment serves many independent projects, each with its
  own members, ticket key space, workflow states and permission grants.
- A **web application**, usable on a phone. One responsive React application, not a desktop product
  with a reduced mobile companion.
- A **Model Context Protocol server**, shipped as a first-class surface alongside the REST API. An
  agent talks to Nodera through MCP the way a person talks to it through the web app. Neither is a
  wrapper around the other; both sit on the same domain services, the same permission checks and the
  same audit trail.
- **Self-hostable and open source** under the MIT licence. One Postgres database, one backend
  process, static frontend assets.

## 3. What Nodera is deliberately not

The scope fence. A change that crosses it is refused in review, however well built.

| Not building | Why |
|---|---|
| A chat product | Comments are attached to work. A general messaging surface is a different product with a different retention, moderation and notification model. |
| A CI/CD system | Nodera *records* build and review outcomes reported to it. It does not schedule, run or host them. |
| A git host | Nodera links to commits, branches and pull requests by URL. It does not store, mirror or serve repositories. |
| A document editor | Tickets carry Markdown. Collaborative rich-text editing is a product of its own. |
| An agent runtime | Nodera is what an agent *talks to*. It does not run, schedule, sandbox or bill agents, and it ships no model-provider integration. |
| A time tracker, invoicing or HR tool | Adjacent, unbounded, and not the problem. |
| Per-project custom field builders | A configurable schema engine consumes the entire product. Fields are added by migration, deliberately. |

**On the agent-runtime line specifically:** the temptation to add "just a small executor" is the
single most likely way this project loses its shape. Nodera exposes work and accepts results. What
runs the agent is the operator's business, and keeping it that way is what lets Nodera be useful to
every agent framework instead of to one.

## 4. The five capabilities that define "first-class"

An agent actor is first-class when all five hold. They are the acceptance criteria for the premise
itself, and any one of them missing collapses the model back into a bot account.

1. **Identity** — an agent is a distinct principal with its own credentials, lifecycle and owner. It
   authenticates as itself. Its identity is unambiguous everywhere it appears, so no reader ever has
   to guess whether a human wrote something.
2. **Permissions** — an agent holds its own grants, scoped per project and per capability, bounded by
   the grantor. An agent can never exceed the permissions of the actor that granted them, and a grant
   can carry an expiry.
3. **Assignments** — an agent can be assigned a ticket, hold it, and hand it back. Assignment carries
   the same semantics for both actor kinds, including a single accountable assignee.
4. **Comments** — an agent participates in the discussion thread as a peer: it can be mentioned, it
   can reply, and its output is attributed, addressable and permanently linked to the work.
5. **Audit** — every state change records the acting actor, the surface it came through (web, REST,
   MCP) and the tool or endpoint used. The trail is append-only and answers "who changed this, how,
   and on whose authority" for both actor kinds identically.

A sixth property is a consequence rather than a capability: **MCP access is the agent's native
surface**, and it exposes exactly the five above — nothing an agent can do through MCP is outside
what its grants permit through any other surface.

## 5. The reference workflow Nodera must serve

Nodera is not designed against an imagined team. It is designed against a documented, demanding
working method that predates it and currently runs on Markdown files in git repositories. That
method is the acceptance test: if Nodera cannot express it without loss, Nodera is not finished.

What the method requires, and therefore what the domain model must carry:

- **A ticket is a specification, not a title.** Motivation, honest current state, approach, acceptance
  criteria as individually checkable items, affected files, verification plan.
- **Four priorities** (P1–P4) with a strict working order, and dependencies between tickets that a
  tool can read rather than a human infer.
- **Effort as an estimate**, deliberately coarse.
- **Independent review before closure**, recorded on the ticket itself: a verdict, findings classified
  BLOCKING or NON-BLOCKING, and the review history preserved across several rounds — including a
  verdict that contradicted an earlier one.
- **Closure is a protocol, not a status change** — every acceptance criterion met, gates green, review
  returned, and the record of what actually happened kept with the ticket.
- **Structural decisions live outside the ticket**, as architecture decision records.
- **Plans for larger work packages** persist and are stamped when implemented, never deleted.
- **Retrospectives** for expensive defects, linked to the work that produced them.
- **Several projects, one method**, with independent key spaces and no shared backlog.

This is where the requirement in § 6.5 comes from: the method exists as Markdown-with-frontmatter
today, and a migration that dropped the review history would lose the most valuable part of it.

## 6. Success criteria

The project is on track when these hold, and not before.

1. A human and an agent can be assigned the same ticket type, and the audit trail distinguishes them
   without a naming convention or a heuristic.
2. An agent's permissions can be narrowed below its grantor's, per project, and the narrowing is
   enforced identically on the REST API and the MCP server — proved by a test that exercises the same
   denial through both surfaces.
3. Three independent projects run in one deployment without a shared ticket key space, and no query
   can return another project's rows.
4. Every mutation appears in the audit trail with actor, surface and tool, and the trail cannot be
   edited through any application path.
5. The Markdown ticket format round-trips: import → export produces a semantically identical file,
   review history included.
6. The web application is usable one-handed on a phone for the three operations that happen most:
   read a ticket, comment, change status.

## 7. Non-goals for version 1.0

Deferred deliberately, so the shape stays defensible. Not refused forever — a v1.0 that is small and
coherent is worth more than a v1.0 that is broad and vague.

- Real-time collaborative presence (who is looking at this ticket right now).
- Sprint, board and burndown views. The data model must not preclude them; the UI does not ship them.
- Federation between Nodera deployments.
- A plugin system. Extension happens through the MCP server and the REST API.
- Field-level permissions.
- Email ingestion (creating a ticket by sending mail).
