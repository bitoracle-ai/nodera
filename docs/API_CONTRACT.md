---
summary: The REST contract — resource shapes, the actor envelope every response carries, error bodies, pagination, idempotency, and the rule that the OpenAPI document is written before the routes rather than derived from them.
read_when:
  - Before adding or changing a REST route, a DTO or an error code.
  - Before writing a frontend data hook.
  - During review of anything under `backend/api-rest/` or `frontend/src/api/`.
---

# REST API contract — Nodera

**Status: specified, not implemented.** The routes below are the contract API-01 builds against.
Where this document and the code disagree, the code is wrong — the contract is written first, on
purpose.

The machine-readable document is `backend/api-rest/src/main/resources/openapi.yaml`. The frontend's
types and Zod schemas are **generated** from it (`yarn api:generate`), and CI fails when the
generated output is stale. Nothing in the client is hand-written twice.

---

## 1. Conventions

| | |
|---|---|
| Base path | `/api/v1` |
| Format | JSON, UTF-8 |
| Timestamps | RFC 3339, always UTC (`2026-08-20T14:07:00Z`) |
| Identifiers | UUID v4 as strings; tickets are additionally addressable by `{projectKey}/{ticketKey}` |
| Authentication | `Authorization: Bearer <token>` — an access JWT or a personal access token |
| Correlation | `X-Request-Id` echoed on every response; it is the `request_id` in the audit trail |

**Versioning:** the path carries the major version. A breaking change means `/api/v2`, not a
silent reshape. Adding an optional field is not breaking; changing the meaning of an existing one
is, even when the type is unchanged.

## 2. The actor envelope

Every actor reference, anywhere in any response, has this shape:

```json
{
  "id": "3f0c…",
  "kind": "agent",
  "handle": "release-bot",
  "displayName": "Release Bot",
  "owner": { "id": "9a1e…", "kind": "human", "handle": "anna", "displayName": "Anna Weber" }
}
```

**`kind` is always present.** No client should ever have to infer whether an actor is a person —
not from the handle, not from a naming convention, not from a heuristic. `owner` appears only on
agents and is not recursive beyond one level; the full chain is available from `/actors/{id}`.

This envelope is the API-side expression of the product's premise. A response that omits `kind`,
or a client that ignores it in favour of a name pattern, is a BLOCKING review finding.

## 2a. Health — the only implemented endpoints today

Deliberately **outside `/api/v1`**, and unauthenticated. An orchestrator's probe configuration must
not have to change when the API's major version does, and these two carry no domain data, so they
have nothing to version and nothing to protect.

| Method | Path | Capability |
|---|---|---|
| `GET` | `/health/live` | none — unauthenticated |
| `GET` | `/health/ready` | none — unauthenticated |

`/health/live` answers whether the process is running and **never consults the database**. It is the
signal an orchestrator uses to destroy and replace a container; wiring a dependency's state into it
turns a wait — a pending migration, a database that blinked — into a crash loop.

`/health/ready` answers whether this instance may receive traffic. It reports `503` while migrations
from this build are unapplied or the database cannot be read. Fail closed: a probe that cannot read
the migration history does not know the schema is current, and "unknown" is never reported as ready.

```json
{ "status": "not_ready", "version": "1.4.2", "detail": "2 migration(s) pending" }
```

`detail` is a short fixed category, never a driver message — this endpoint is unauthenticated, and a
JDBC exception string routinely carries the host, the port and the connecting user.

Everything below this point is **specified and not yet implemented**; API-01 builds it.

## 3. Resources

### Projects

| Method | Path | Capability |
|---|---|---|
| `GET` | `/projects` | `project.read` |
| `GET` | `/projects/{key}` | `project.read` |
| `POST` | `/projects` | deployment admin |
| `PATCH` | `/projects/{key}` | `project.admin` |

### Tickets

| Method | Path | Capability |
|---|---|---|
| `GET` | `/projects/{key}/tickets` | `ticket.read` |
| `POST` | `/projects/{key}/tickets` | `ticket.create` |
| `GET` | `/projects/{key}/tickets/{ticketKey}` | `ticket.read` |
| `PATCH` | `/projects/{key}/tickets/{ticketKey}` | `ticket.update` |
| `POST` | `/projects/{key}/tickets/{ticketKey}/transition` | `ticket.transition` (+ `ticket.close` for `closed`) |
| `PUT` | `/projects/{key}/tickets/{ticketKey}/assignee` | `ticket.assign` |
| `GET` | `/projects/{key}/tickets/next` | `ticket.read` |

There is **no `DELETE`**. Tickets close with a resolution; a deleted key breaks every external
reference to it (invariant #10).

`PATCH` does not accept `status`. A status change is a transition with its own gate, and allowing
it as a field edit would route around that gate — which is precisely how a closure gate stops
being one.

### Acceptance criteria, dependencies, labels

| Method | Path | Capability |
|---|---|---|
| `GET` / `PUT` | `…/tickets/{ticketKey}/criteria` | `ticket.read` / `ticket.update` |
| `PATCH` | `…/tickets/{ticketKey}/criteria/{ordinal}` | `ticket.update` |
| `POST` / `DELETE` | `…/tickets/{ticketKey}/dependencies` | `ticket.update` |
| `PUT` | `…/tickets/{ticketKey}/labels` | `ticket.update` |

### Comments and reviews

| Method | Path | Capability |
|---|---|---|
| `GET` / `POST` | `…/tickets/{ticketKey}/comments` | `comment.read` / `comment.create` |
| `PATCH` / `DELETE` | `…/comments/{id}` | author only, or `comment.moderate` |
| `GET` / `POST` | `…/tickets/{ticketKey}/reviews` | `ticket.read` / `review.submit` |
| `PATCH` | `…/reviews/{id}/findings/{findingId}` | `review.submit` |

Reviews have no `PATCH` and no `DELETE`. A changed opinion is a new round (invariant #9).

### Actors, membership, credentials, audit

| Method | Path | Capability |
|---|---|---|
| `GET` | `/actors` · `/actors/{id}` · `/me` | `actor.read` |
| `POST` | `/agents` | `agent.create` |
| `GET` / `PUT` / `DELETE` | `/projects/{key}/members[/{actorId}]` | `member.grant` |
| `GET` / `POST` / `DELETE` | `/me/credentials[/{id}]` | own actor only |
| `GET` | `/projects/{key}/audit` | `audit.read` |

`POST /me/credentials` returns the token plaintext **once**. It is never retrievable again, from
any endpoint, in any form.

## 4. Errors

RFC 9457 problem details, plus a stable `code` that clients switch on. Codes are part of the
contract; renaming one is a breaking change.

```json
{
  "type": "https://nodera.dev/errors/closure-gate-failed",
  "title": "Ticket cannot be closed",
  "status": 409,
  "code": "closure_gate_failed",
  "detail": "2 acceptance criteria are unmet and 1 blocking finding is unresolved.",
  "instance": "/api/v1/projects/nodera/tickets/core-12/transition",
  "unmet": {
    "acceptanceCriteria": [{ "ordinal": 3, "text": "Round-trip property test passes." }],
    "unresolvedBlockingFindings": [{ "id": "…", "title": "RLS policy missing on comment." }],
    "reviews": "present"
  }
}
```

| Code | Status | Meaning |
|---|---|---|
| `unauthenticated` | 401 | Missing, malformed, expired or revoked credential |
| `forbidden` | 403 | Authenticated, capability absent. Names the capability required. |
| `not_found` | 404 | Absent, **or** invisible to this caller — indistinguishable on purpose |
| `validation_failed` | 422 | Names the offending fields |
| `closure_gate_failed` | 409 | See above |
| `dependency_cycle` | 409 | Returns the offending path |
| `idempotency_conflict` | 409 | Key reused with different arguments |
| `rate_limited` | 429 | Carries `Retry-After` |

**Why `not_found` rather than `forbidden` for an invisible project:** a distinct `forbidden` would
let any authenticated actor enumerate which project keys exist. The information leak is small and
the cost of closing it is one status code.

**The itemised `unmet` block is not decoration.** A client that receives a bare 409 can only
guess; one that receives the list can finish the work. The same body is what the MCP surface
returns, from the same domain result.

## 5. Pagination

Cursor-based on every collection.

```
GET /projects/nodera/tickets?status=open&limit=50&cursor=eyJ…
```

```json
{ "items": [ … ], "nextCursor": "eyJ…", "hasMore": true }
```

`limit` is clamped server-side regardless of what was requested. Cursors are opaque and encode the
sort position, not an offset — a ticket updated during a walk cannot cause another to be skipped.

Offset pagination is deliberately not offered. It is correct only over a table nobody is writing
to, which is not a backlog.

## 6. Idempotency

Every mutating request accepts `Idempotency-Key`. It is **required** on `POST /tickets` and
`POST /comments`, the two whose duplicates are both most likely and most visible.

The key is stored with the resulting entity for 24 hours. A repeat returns the original result with
`Idempotency-Replayed: true`. A repeat with the same key and different arguments returns
`idempotency_conflict` — never a silent overwrite, because the two calls expressed different
intents and only one of them happened.

## 7. What the API layer must never do

The adapter translates. It does not decide.

- No permission decision — it carries `ActorContext` and passes it on.
- No domain state transition.
- No audit write.
- No SQL. `:api-rest` does not depend on `:persistence`, so this one is a compile error rather
  than a review finding.
