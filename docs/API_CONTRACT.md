---
summary: The REST contract — the authentication and session routes, resource shapes, the actor envelope every response carries, error bodies, pagination, idempotency, and the rule that the OpenAPI document is written before the routes rather than derived from them.
read_when:
  - Before adding or changing a REST route, a DTO or an error code.
  - Before writing a frontend data hook.
  - During review of anything under `backend/api-rest/` or `frontend/src/api/`.
---

# REST API contract — Nodera

**Every route here is specified. Only the ones § 2b lists are served.** The rest are the contract the
packages after API-01 build against, and each one says where it is blocked. Where this document and
the code disagree the code is wrong — the contract is written first, on purpose — but a route this
document specifies is not thereby a route that answers.

The machine-readable document is `backend/api-rest/src/main/resources/openapi.yaml`, and it carries
**only what is served**: the server streams that file at `GET /openapi.yaml`, and a test asserts the
routing tree and the document's `paths` are the same set, so neither can gain an entry the other
lacks. This document is the wider one on purpose — a specification has to be able to describe what
does not exist yet, and a machine-readable contract that did would generate a client for endpoints
that answer `404`.

The frontend's types and Zod schemas are **generated** from the OpenAPI document
(`yarn api:generate`), and CI fails when the generated output is stale. Nothing in the client is
hand-written twice.

---

## 1. Conventions

| | |
|---|---|
| Base path | `/api/v1` |
| Format | JSON, UTF-8 |
| Timestamps | RFC 3339, always UTC (`2026-08-20T14:07:00Z`) |
| Identifiers | UUID v4 as strings; tickets are additionally addressable by `{projectKey}/{ticketKey}` |
| Authentication | `Authorization: Bearer <token>` — an access JWT or a personal access token |
| Correlation | `X-Request-Id`, a UUID, echoed on every response; it is the `request_id` in the audit trail, whose column is `uuid not null` |

**Versioning:** the path carries the major version. A breaking change means `/api/v2`, not a
silent reshape. Adding an optional field is not breaking; changing the meaning of an existing one
is, even when the type is unchanged.

**`X-Request-Id` that is not a UUID is replaced, not refused and not passed through.** The server
generates one, uses it, and echoes the one it used — so the response always says which id the trail
holds. Refusing the request would let a proxy that stamps its own correlation format break every
call; passing the value through would put a client-controlled string where `audit_event.request_id`
is `uuid not null`, and the transaction would fail on its last statement rather than at the edge.
The header correlates and authorises nothing: it is never read to decide anything, so a caller
reusing or colliding on one costs itself a confusing trail and nobody else anything.

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

## 2a. Health

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

## 2b. What is served today

| Method | Path | Since |
|---|---|---|
| `GET` | `/health/live` · `/health/ready` | OPS-01 |
| `GET` | `/openapi.yaml` | API-01 |
| `GET` | `/api/v1/me` | API-01 |
| `POST` | `/api/v1/me/credentials` | API-01 |
| `DELETE` | `/api/v1/me/credentials/{id}` | API-01 |
| `POST` | `/api/v1/auth/refresh` | API-01 |

Everything else in this document is specified and **not served**. Three things block the remainder,
and each one is named again where it applies:

1. **The project scope has no bootstrap.** `PermissionDirectory` has no implementation, nothing
   establishes `nodera.project_ids`, and establishing it needs the acting actor's memberships —
   which row-level security hides until the scope is already set. Breaking that circle is a schema
   decision. Every `/projects/…` route waits on it.
2. **A one-time code has nowhere to go.** `SignInCodeDelivery` is a port with no adapter, so
   `POST /auth/sign-in` cannot be constructed.
3. **There is no request budget.** Redeeming a sign-in code costs one Argon2id evaluation on every
   path by design, which at the shipped cost is 64 MiB per unauthenticated request. `rate_limited`
   is in § 4's table and nothing returns it yet, so `POST /auth/sign-in/redeem` is specified and not
   published.

   **`POST /auth/refresh` is served and is not free either, and the difference is the point.** An
   *unknown* selector is refused before any hashing, so an attacker with no relationship to the
   deployment costs it one indexed lookup — that asymmetry is what makes serving it defensible.
   A **live selector with a wrong verifier** is not free: it spends one Argon2id evaluation and
   writes one audit row per attempt, and revoked rows are never deleted, so anyone who has ever held
   a refresh token keeps a usable selector indefinitely. The residual is bounded by having held a
   credential; the budget that would close it is the same one blocker 3 names.

**One thing this list does not cover, said here rather than discovered.** An unmatched path under
`/api/v1` is answered by the single-page fallback — the web assets and the API share one origin
(ADR-0006), and the fallback is what makes a deep link survive a hard refresh. So a mistyped API
path returns `index.html` with `200`, not a problem document. It is wrong and it is not dangerous;
closing it means teaching the fallback where the API begins, and the package that adds the first
`/api/v1` collection does it.

## 3. Resources

### Authentication and sessions

| Method | Path | Capability | Served |
|---|---|---|---|
| `POST` | `/auth/sign-in` | none — unauthenticated | no — § 2b, blocker 2 |
| `POST` | `/auth/sign-in/redeem` | none — unauthenticated | no — § 2b, blocker 3 |
| `POST` | `/auth/refresh` | none — the refresh token *is* the credential | yes |

**An agent needs none of these.** It authenticates with `Authorization: Bearer nod_pat_…` on the
request it wanted to make. There is no sign-in step, no session and no token exchange in an agent's
path, which is invariant #1 at the level of the surface: the two actor kinds differ in how they
*prove* who they are, and in nothing after that.

#### `POST /auth/sign-in`

```json
{ "email": "anna@example.org" }
```

**Always `202 Accepted`, with an empty body**, whether or not the address belongs to anybody and
whether or not that actor may sign in. A response that varied is an account-enumeration oracle on an
unauthenticated endpoint, and the enumeration is the whole reason the answer carries nothing.

#### `POST /auth/sign-in/redeem`

```json
{ "email": "anna@example.org", "code": "48210736" }
```

`200` returns a session. Every refusal is `401` with code `unauthenticated` and **one** detail
string — an unknown address, a wrong code, an expired code, a code already used and an actor that
may not sign in are indistinguishable to the caller. They are told apart in the audit trail, which
is where the distinction is needed and where reading it is already a capability.

#### `POST /auth/refresh`

```json
{ "refreshToken": "nod_ref_6f1c…_9c2e…" }
```

`200` returns a **new** session, and the presented refresh token is revoked in the same transaction.
That rotation is what makes an opaque refresh token worth having: a captured one replayed afterwards
meets a revoked row and is refused, and the attempt is the most diagnostic event the identity code
produces.

A refresh token is **not** a bearer credential. Presenting one in `Authorization` is `401`: it is
spent here and nowhere else, and accepting it as a bearer token would mean a captured one served
requests for its whole lifetime without ever rotating.

**The `Authorization` header is ignored on this route, and on every other route marked with no
security.** A client keeps a default header; the access token expires; it calls refresh with that
stale header still attached — and refusing there would make renewing a session impossible for the
ordinary client, with a refusal naming the wrong credential. Everywhere the contract *does* declare
security, a presented credential that cannot be used is still `401` before the route runs.

Refusals here name which of `unknown`, `revoked` and `expired` applied, because the caller already
holds the credential and learns nothing it did not have. An unknown selector and a wrong verifier
share one answer, so the two halves of a token cannot be attacked separately.

#### The session body

```json
{
  "accessToken": "eyJ…",
  "expiresAt": "2026-09-10T14:22:00Z",
  "refreshToken": "nod_ref_6f1c…_9c2e…"
}
```

The refresh token is in the **body**, not in a `Set-Cookie` header. Nodera's first-class clients are
scripts, CI jobs and agents, and a cookie is invisible to all of them. A browser client that wants
the token in an `HttpOnly` cookie needs a CSRF story to go with it, which is a decision for the
package that builds one — not a default imposed on every other caller.

**Sign-out is deliberately not specified here.** Revoking one session and revoking every session an
actor holds are different operations with different blast radii, no use case implements either, and
inventing the route before the decision would fix the wrong one in a contract. Until then a client
ends a session by discarding the tokens it holds; the access token expires on its own.

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

| Method | Path | Capability | Served |
|---|---|---|---|
| `GET` | `/me` | none beyond authentication — it is the caller | yes |
| `GET` | `/actors` · `/actors/{id}` | `actor.read` | no — project-scoped |
| `POST` | `/agents` | `agent.create` | no — project-scoped |
| `GET` / `PUT` / `DELETE` | `/projects/{key}/members[/{actorId}]` | `member.grant` | no — project-scoped |
| `POST` / `DELETE` | `/me/credentials[/{id}]` | own actor only | yes |
| `GET` | `/me/credentials` | own actor only | no — no read port |
| `GET` | `/projects/{key}/audit` | `audit.read` | no — project-scoped |

**`GET /me` requires no capability**, and that is not an omission. A capability is held *in a
project*, and this route resolves none — it answers with the actor the presented credential belongs
to, which the caller demonstrably already knows. It returns the § 2 envelope and nothing more:

```json
{ "id": "3f0c…", "kind": "agent", "handle": "release-bot", "displayName": "Release Bot",
  "owner": { "id": "9a1e…", "kind": "human", "handle": "anna", "displayName": "Anna Weber" } }
```

It also answers **`404`**: a credential resolves and the actor behind it does not, because the actor
was deleted between authenticating and being read. Failing closed is deliberate — a partial envelope
would be an actor the rest of the API cannot resolve — and it is in the OpenAPI document, so a
generated client has the case.

`POST /me/credentials` mints a personal access token **for the caller and for nobody else** — there
is no field naming an actor, so minting one for somebody else is not a mistake the route can make.

```json
{ "label": "ci runner", "expiresAt": "2027-01-01T00:00:00Z" }
```

`expiresAt` is **required**, deliberately diverging from the nullable column beneath it. A token
that never expires is a token nobody rotates, and a default chosen at this layer would be a policy
invented where the deployment cannot be seen. The caller states the intent. One that has already
passed is `422`: a token minted expired is a token that never worked, and the server judges it
against the same clock the credential's own lifetime is judged against.

`201` returns the token plaintext **once**:

```json
{ "id": "7b2f…", "label": "ci runner", "token": "nod_pat_6f1c…_9c2e…",
  "expiresAt": "2027-01-01T00:00:00Z" }
```

It is never retrievable again, from any endpoint, in any form. No response anywhere in this contract
carries a selector, a verifier, a hash or a sign-in code — the listing route, when it exists, returns
labels and timestamps.

`DELETE /me/credentials/{id}` answers `204` when the credential was the caller's and `404`
(`not_found`) when it was absent **or** somebody else's. Those two are one answer on purpose, for the
reason § 4 gives about projects: a distinct `403` would turn the endpoint into an oracle for which
credential ids exist.

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
| `validation_failed` | 422 | Names the offending field where it can — a body that will not deserialise names none, on purpose |
| `closure_gate_failed` | 409 | See above |
| `dependency_cycle` | 409 | Returns the offending path |
| `idempotency_conflict` | 409 | Key reused with different arguments |
| `rate_limited` | 429 | Carries `Retry-After` |

**`detail` and `instance` are redacted on the way out.** `instance` is the request's own path, so a
caller that puts a token where an identifier belongs would otherwise have it copied into a problem
document and from there into every log between the server and them; it comes back as `nod_***`. The
second layer exists for the same reason the logging boundary has one: to contain a mistake made
upstream.

**`detail` is a fixed string at every call site, and each one is written to survive that redaction
unchanged** — a test asserts it for every detail this surface can emit. The rule is not free:
"a bearer credential" reads as a credential to the redactor and came back as "a bearer ***", so the
sentence is hyphenated instead. Where a sentence and the redactor disagree, the sentence changes.

**This table is the taxonomy both surfaces answer from.** MCP-01 depends on API-01 for exactly this
and for nothing else: the same domain result produces the same `code` on both, so an agent that
learns what `closure_gate_failed` means through one surface has learned it for the other. A code
that exists on one surface only would be the drift invariant #2 forbids for permissions, one layer
out. Adding or renaming one is a contract change here first.

`rate_limited` is in the table and **nothing returns it yet** — there is no request budget, which is
why the one unauthenticated endpoint that costs an Argon2id evaluation per call is specified and not
served (§ 2b).

**Why `not_found` rather than `forbidden` for an invisible project:** a distinct `forbidden` would
let any authenticated actor enumerate which project keys exist. The information leak is small and
the cost of closing it is one status code. The same reasoning governs a credential that is not the
caller's, which is the one place the rule is provable today.

**The itemised `unmet` block is not decoration.** A client that receives a bare 409 can only
guess; one that receives the list can finish the work. The same body is what the MCP surface
returns, from the same domain result.

## 5. Pagination

**Specified, not implemented** — no collection is served yet, so there is nothing to page.

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

**Specified, not implemented.** `idempotency_record` is in the schema from `V4` and nothing reads or
writes it. No route served today accepts `Idempotency-Key`, and none pretends to: a header parsed and
discarded advertises a replay protection that is not there, which is worse than the header being
absent. The mechanism belongs to `:application` — the record is written in the mutation's own
transaction or it guarantees nothing — so it lands with the first route that needs it.

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
