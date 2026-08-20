---
summary: Rules for the MCP server — how a tool is defined, the capability it declares, argument validation, idempotency, error shape, and the parity requirement that keeps MCP from becoming a weaker API.
read_when:
  - Before adding, renaming or changing an MCP tool, resource or prompt.
  - During review of anything under `backend/api-mcp/`.
  - When an agent reports behaviour that differs between MCP and REST.
---

# MCP integration — conventions

Read [`../docs/MCP.md`](../docs/MCP.md) in full before changing this surface. This skill is the
implementation-side companion to it.

## A tool is a thin translation, and nothing else

```
validate arguments against the schema
  -> build ActorContext(surface = MCP, toolName = "...", requestId, onBehalfOf)
  -> call the use case in :application
  -> map the sealed result to an MCP result or error
```

No permission decision, no domain logic, no SQL, no second validation of a business rule. If a tool
needs a rule the use case does not have, the rule goes in the use case — where REST gets it too.

## Parity is tested, not asserted

Every capability enforced on both surfaces has a **parity test**: the same denial, driven once through
REST and once through MCP, asserting the same outcome.

This is the test that catches the drift the whole design exists to prevent. Without it, "one permission
engine" is a claim in a document.

## Declaring capability

The capability a tool requires is the argument passed to `PermissionService.require(...)`. It is not
documentation and not an annotation read by nothing. `tools/list` filters by it, so an agent never sees
a tool it cannot call — which means the declaration is also what makes discovery honest.

## Argument validation

- JSON Schema per tool, generated from the kotlinx.serialization DTO. One definition, not two.
- Validation failures return `validation_failed` naming the offending fields. An agent that receives
  "invalid arguments" retries the same call; one that receives the field name fixes it.
- Never coerce silently. A string where an integer was declared is an error, not a parse attempt.

## Idempotency

`ticket_create` and `comment_create` **require** an `idempotency_key`; every other mutating tool
accepts one. Store the key with the resulting entity id for 24 hours and replay the original result
with `"idempotent_replay": true`.

A repeat with the same key and **different arguments** is `idempotency_conflict`, never a silent
overwrite: the two calls expressed different intents and only one of them happened.

## Errors carry structure

An error is a stable code, a human-readable message, and structured detail where any exists. The
closure gate is the model: it returns the unmet criteria and unresolved findings, itemised, so the
agent can finish the work rather than guess.

Error codes are part of the contract. Renaming one is a breaking change and needs a migration note.

## Audit every call

Including denied and malformed ones, with `surface = mcp`, `tool_name` set and `outcome` recorded.
What an agent *tried* is part of the record — a trail of successes cannot answer the question an
incident review actually asks.

## Pagination

Cursor-based, opaque cursors encoding sort position rather than an offset. `limit` is clamped
server-side by `NODERA_MCP_MAX_PAGE_SIZE` regardless of what was requested. An agent asking for the
whole backlog gets a page and a cursor, every time.

## What must not be added

No bulk mutation tool, no `ticket_delete`, no `capability_grant` tool, no human `actor_create`.
The reasoning for each is in [`../docs/MCP.md`](../docs/MCP.md) § 3.4 — read it before proposing one,
because each has a specific failure mode rather than a general reluctance behind it.
