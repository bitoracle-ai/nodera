---
summary: Nodera's Model Context Protocol server — the complete tool and resource surface, the capability each tool requires, authentication, pagination, idempotency and the rules that keep MCP from becoming a second, weaker API.
read_when:
  - Before adding, renaming or changing an MCP tool, resource or prompt.
  - When connecting an agent to Nodera for the first time.
  - During review of anything under `backend/api-mcp/`.
---

# MCP server — Nodera

**Status: specified, not implemented.** The surface below is the contract the MCP work packages
(MCP-01 onward) build against. Where this document and the code disagree, the code is wrong —
the contract is written first, on purpose.

Nodera ships a Model Context Protocol server as a **first-class surface**, not an add-on. It is how
an agent actor works: the same domain services, the same permission engine and the same audit trail
that serve the web application.

---

## 1. The four rules

Everything in this document follows from these. A change that breaks one is a BLOCKING review finding.

1. **No capability exists only in MCP.** Every tool maps to a use case the REST API also exposes. MCP
   is a different way to reach the same behaviour, never a different behaviour.
2. **No capability is weaker in MCP.** The permission check, the closure gate and the invariants are
   the same objects, not a re-implementation. A denial on REST is a denial on MCP.
3. **`tools/list` is filtered by the caller's grants.** An agent does not see a tool it may not call.
   Discovery is scoped, so an agent's own view of what it can do matches what it can actually do.
4. **Every call is audited, including denied and malformed ones,** with `surface = mcp` and
   `tool_name` set. What an agent *tried* is part of the record.

## 2. Connecting

### Transports

| Transport | Use |
|---|---|
| `stdio` | Server spawned locally by the agent runtime. Default for a developer machine. |
| Streamable HTTP | Shared deployment. Bearer authentication on every request. |

### Authentication

The same personal access token as the REST API, in `Authorization: Bearer nod_pat_…`. There is no
MCP-only credential, no anonymous mode and no "local means trusted" exception — a locally spawned
server authenticates exactly like a remote one.

### Client configuration

```json
{
  "mcpServers": {
    "nodera": {
      "command": "nodera-mcp",
      "args": ["--transport", "stdio"],
      "env": {
        "NODERA_API_BASE_URL": "https://nodera.example.com",
        "NODERA_TOKEN": "nod_pat_…"
      }
    }
  }
}
```

The token is read from the environment. Nodera's own configuration never contains a literal token,
and the server refuses to start if one is passed as a command-line argument, where it would land in
the process table and every shell history on the machine.

## 3. Tool surface

Naming: `<entity>_<verb>`, lower snake case. The required capability is what
`PermissionService` checks — it is not documentation, it is the argument passed to the check.

### 3.1 Orientation

| Tool | Capability | Purpose |
|---|---|---|
| `whoami` | — | The calling actor: id, handle, kind, owner, and the effective capability set per project. The first call any agent should make. |
| `project_list` | `project.read` | Projects visible to the caller. |
| `project_get` | `project.read` | One project: key, name, ticket prefixes in use, workflow states. |
| `actor_search` | `actor.read` | Find actors by handle or name, to assign or mention. Returns `kind` on every result. |

`whoami` deliberately returns capabilities rather than a role name. An agent that must guess what
`contributor` means will guess wrong; a list of verbs is unambiguous and is exactly what the server
will enforce.

### 3.2 Reading work

| Tool | Capability | Purpose |
|---|---|---|
| `ticket_search` | `ticket.read` | Filter by project, status, priority, assignee, label, text, updated-since. Cursor-paginated. |
| `ticket_get` | `ticket.read` | One ticket in full: body, acceptance criteria, dependencies, assignee, review state. |
| `ticket_next` | `ticket.read` | **The working-order query**: the next ticket the caller should start, honouring priority order, dependency readiness and existing assignment. Returns the ticket plus the reason it was chosen. |
| `comment_list` | `comment.read` | The discussion thread, oldest first, with each author's `kind`. |
| `review_list` | `ticket.read` | Every review round on a ticket, with findings and resolution state. |
| `audit_query` | `audit.read` | Audit events by entity, actor, action or time window. |

`ticket_next` exists because the alternative is every agent re-implementing the prioritisation rules
from prose, each slightly differently. The rule belongs in one place, and that place is the server.

### 3.3 Changing work

| Tool | Capability | Notes |
|---|---|---|
| `ticket_create` | `ticket.create` | Requires `idempotency_key` (§ 5). |
| `ticket_update` | `ticket.update` | Title, body, priority, effort, labels. Not status — that is a transition. |
| `ticket_transition` | `ticket.transition` | Status change. `closed` additionally requires `ticket.close` and passes the closure gate (§ 4). |
| `ticket_assign` | `ticket.assign` | Assign to any actor, human or agent. Assigning to oneself needs `ticket.assign_self` only. |
| `ticket_dependency_add` / `_remove` | `ticket.update` | Rejects a cycle with the offending path in the error. |
| `criterion_set` | `ticket.update` | Mark one acceptance criterion met or unmet. Records who and when. |
| `comment_create` | `comment.create` | Markdown body, optional `in_reply_to`. Mentions are parsed server-side. |
| `review_submit` | `review.submit` | Verdict plus findings. Refused when the caller is the author or the assignee (invariant R1). |
| `finding_resolve` | `review.submit` | Marks one finding resolved, with a note. |

### 3.4 Deliberately absent

- **No `ticket_delete`.** Tickets are closed with a resolution. `wont_do` is an outcome; erasure is
  not, and a deleted key breaks every external reference to it (invariant T3).
- **No `actor_create` for humans.** Inviting a person is a human act, through the web application.
- **No `capability_grant` tool.** An agent granting permissions — even within its own set — is the
  privilege-escalation path this model exists to close (invariant C3). Grants happen in the web
  application, by a human, on the record.
- **No bulk mutation tool.** A single call that transitions forty tickets produces one intent and
  forty consequences, which is exactly the shape that is hard to review and impossible to undo. Loop
  over `ticket_transition`; the audit trail then reads as what actually happened.
- **No `comment.update` capability.** Editing a comment is authoring: the check is `comment.create`
  plus the rule that only the author edits (invariant CM1), and deleting one's own comment is the
  same verb, with `comment.moderate` for another's. A verb that would only ever appear beside
  `comment.create` adds a row to two tables and decides nothing. Decided by the maintainers on
  2026-09-03 and not re-raised.

## 4. The closure gate, as an agent experiences it

`ticket_transition` to `closed` with resolution `done` is refused unless every acceptance criterion is
met, no `blocking` finding is unresolved, and at least one review exists (invariant T4).

The other three resolutions do not run the gate, and an `open` ticket may take them directly — a
duplicate recognised the moment it is filed closes in one call. `done` is never accepted from
`open`: the machine refuses it before the gate is consulted ([`DOMAIN_MODEL.md`](DOMAIN_MODEL.md)
§ 5.1).

A refusal is not a flat error. It returns what is missing, so the agent can act rather than retry:

```json
{
  "error": "closure_gate_failed",
  "message": "Ticket NODERA/CORE-12 cannot close as done.",
  "unmet": {
    "acceptance_criteria": [
      { "ordinal": 3, "text": "Round-trip property test passes over generated tickets." }
    ],
    "unresolved_blocking_findings": [
      { "id": "…", "title": "Review round 2: RLS policy missing on comment table." }
    ],
    "reviews": "present"
  }
}
```

This shape is the point. An agent that receives "409 Conflict" guesses; an agent that receives the
list finishes the work.

## 5. Idempotency

Agents retry. A retried `ticket_create` that produces a second ticket is a defect, not a rough edge.

Every mutating tool accepts an `idempotency_key` (client-generated, unique per intent). It is
**required** on `ticket_create` and `comment_create`, the two whose duplicates are both most likely and
most visible. The server stores the key with the resulting entity id for 24 hours and returns the
original result — with `"idempotent_replay": true` — for a repeat.

A repeat with the same key but different arguments is an error, never a silent overwrite: the two
calls expressed different intents and only one of them happened.

## 6. Pagination

Cursor-based. `ticket_search` and `comment_list` accept `cursor` and `limit`; `limit` is clamped to
`NODERA_MCP_MAX_PAGE_SIZE` regardless of what was asked for. The response carries `next_cursor`, null
at the end.

Cursors are opaque and encode the sort position, not an offset — a ticket updated during a walk cannot
cause another to be skipped.

## 7. Resources

Read-only, addressable, for an agent that wants context rather than a query result.

| URI | Content |
|---|---|
| `nodera://projects` | Projects visible to the caller. |
| `nodera://projects/{key}` | Project overview: prefixes, counts by status and priority. |
| `nodera://projects/{key}/backlog` | The working order — open tickets, sorted the way `ticket_next` sorts them. |
| `nodera://projects/{key}/tickets/{ticketKey}` | One ticket as Markdown with frontmatter — the interchange format of § 10 in the [domain model](DOMAIN_MODEL.md), byte-identical to what export produces. |
| `nodera://projects/{key}/tickets/{ticketKey}/thread` | Comments and review rounds as one readable transcript. |

The ticket resource returning the same Markdown as the file export is deliberate: an agent that has
read tickets from a git repository sees exactly the format it already knows.

## 8. Prompts

MCP prompts ship with the server so a workflow is reproducible across agent runtimes instead of being
re-invented per contributor.

| Prompt | Purpose |
|---|---|
| `start_work_package` | Fetch the next ticket, read its criteria and dependencies, and produce a plan before any change. |
| `review_work_package` | Run an independent review against the ticket's acceptance criteria and return findings classified BLOCKING / NON-BLOCKING. |
| `close_work_package` | Walk the closure protocol: criteria, gates, review, record. |

## 9. Errors

Every error carries a stable `error` code, a human-readable `message`, and structured detail where
there is any. Codes are part of the contract and are not renamed without a migration note.

| Code | Meaning |
|---|---|
| `unauthenticated` | Missing, malformed or revoked token. |
| `forbidden` | Authenticated, but the capability is absent. Names the capability required. |
| `not_found` | Absent, or present in a project the caller cannot see — the two are indistinguishable on purpose. |
| `validation_failed` | Arguments failed the tool schema. Names the fields. |
| `closure_gate_failed` | See § 4. |
| `dependency_cycle` | The dependency would create a cycle. Returns the path. |
| `idempotency_conflict` | Key reused with different arguments. |
| `rate_limited` | Includes `retry_after_seconds`. |

**`not_found` over `forbidden` for invisible projects** is a deliberate information-leak trade-off: a
distinct `forbidden` would let any actor enumerate which project keys exist.

## 10. Rate limits

Per actor, not per token, so minting a second token gains nothing. Read tools and write tools have
separate budgets — an agent polling `ticket_search` must not be able to exhaust its own ability to
comment. Limits are configuration, and the response says how long to wait.
