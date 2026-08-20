---
summary: How Nodera is built — hexagonal Kotlin backend with REST and MCP as two adapters over one domain core, React/TypeScript frontend, PostgreSQL with row-level security, and the reasoning behind each choice.
read_when:
  - Before adding a module, a dependency, a surface or a cross-cutting mechanism.
  - When a change would put logic in an adapter rather than in the domain.
  - When onboarding to the codebase for the first time.
---

# Architecture — Nodera

## 1. The shape in one picture

```
   ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
   │  React web app   │   │   REST clients   │   │   AI agents      │
   │  (browser, PWA)  │   │   (scripts, CI)  │   │   (MCP clients)  │
   └────────┬─────────┘   └────────┬─────────┘   └────────┬─────────┘
            │  HTTPS/JSON          │  HTTPS/JSON          │  MCP
            └──────────┬───────────┘                      │  (stdio | streamable HTTP)
                       │                                  │
        ┌──────────────▼──────────────┐    ┌──────────────▼──────────────┐
        │   :api-rest  (Ktor)         │    │   :api-mcp  (MCP server)    │
        │   routes, DTOs, HTTP codes  │    │   tools, resources, schemas │
        └──────────────┬──────────────┘    └──────────────┬──────────────┘
                       │                                  │
                       └───────────────┬──────────────────┘
                                       │  the SAME calls
                       ┌───────────────▼────────────────┐
                       │        :application            │
                       │  use cases · PermissionService │
                       │  AuditRecorder · ports         │
                       └───────────────┬────────────────┘
                                       │
                       ┌───────────────▼────────────────┐
                       │          :domain               │
                       │  actor · ticket · review …     │
                       │  invariants, no framework      │
                       └───────────────┬────────────────┘
                                       │  ports (interfaces)
                       ┌───────────────▼────────────────┐
                       │        :persistence            │
                       │  PostgreSQL · RLS · Flyway     │
                       └────────────────────────────────┘
```

**The one rule this diagram encodes:** REST and MCP are *siblings*. Neither calls the other, neither
is a wrapper around the other, and no business rule lives in either. Both translate a request into a
call on `:application` and translate the result back. That is what makes invariant C2 of the
[domain model](DOMAIN_MODEL.md) — one permission engine — structurally true rather than a promise.

## 2. Backend — Kotlin, hexagonal, six modules

Gradle multi-module build under `backend/`. Dependencies point inward only; the arrow never reverses.

| Module | Contains | May depend on |
|---|---|---|
| `:domain` | Entities, value objects, state machines, invariants. Pure Kotlin — no Ktor, no SQL, no JSON, no logging framework. | nothing |
| `:application` | Use cases, `PermissionService`, `AuditRecorder`, port interfaces. | `:domain` |
| `:persistence` | PostgreSQL adapters, Flyway migrations, RLS session wiring. | `:domain`, `:application` |
| `:api-rest` | Ktor routing, DTOs, content negotiation, error mapping. | `:domain`, `:application` |
| `:api-mcp` | MCP tool and resource definitions, JSON-Schema, transport. | `:domain`, `:application` |
| `:app` | Composition root, configuration, `main()`. | all of the above |

**Why `:domain` is framework-free** and not merely "mostly clean": it is the module every invariant
lives in, and a pure module is testable without a database, a server or a container. It also keeps
the door open for Kotlin Multiplatform — the domain rules are the part worth sharing with a future
native or shared client, and they can only be shared if nothing JVM-specific leaked in.

### 2.1 Chosen libraries, and what each is doing there

| Concern | Choice | Why this one |
|---|---|---|
| HTTP server | **Ktor** | Kotlin-native, coroutine-first, small. A servlet stack would drag in a lifecycle model the domain has no use for. |
| Database access | **Exposed** (DSL, not DAO) + raw SQL where clarity wins | Typed queries without a codegen step in the build. The DAO layer is deliberately unused: implicit lazy loading hides exactly the N+1 and cross-project reads this project cares about. |
| Migrations | **Flyway** | Plain, ordered `.sql` files. Forward-only, expand/contract. |
| Serialisation | **kotlinx.serialization** | Compile-time, reflection-free; the same DTO definitions feed the MCP JSON-Schema generator. |
| DI | **Constructor injection, wired by hand in `:app`** | The graph is small enough to read. A container earns its place when the wiring is too large to hold in the head; it is not. |
| Auth | **JWTs** (access) + opaque refresh tokens; Argon2id for PAT hashes | See § 5. |
| Testing | **Kotest** + **Testcontainers** (real Postgres) | Invariants that live in RLS policies cannot be tested against an in-memory substitute. |
| Logging | **SLF4J** + structured JSON in deployed environments | |

### 2.2 What must never happen in an adapter

- A permission decision. Adapters carry an `ActorContext`; they never decide with it.
- A domain state transition (`ticket.status = …`).
- Writing an audit event. `:application` writes it, in the mutation's transaction (invariant AU3).
- A SQL query. `:api-rest` and `:api-mcp` do not depend on `:persistence`, so this one is enforced by
  the build rather than by review.

## 3. Frontend — React, mobile-first

Vite + React 19 + TypeScript (strict) under `frontend/`.

| Concern | Choice | Notes |
|---|---|---|
| Routing | React Router | File-independent, explicit route table. |
| Server state | TanStack Query | Caching, invalidation and retry belong in one place, not in components. |
| Client state | React state + context | No global store until something actually needs one. |
| Styling | Tailwind CSS | Mobile-first breakpoints by default, no naming layer to maintain. |
| Forms | React Hook Form + Zod | The Zod schemas are generated from the OpenAPI document, so the client cannot drift from the contract silently. |
| Testing | Vitest + Testing Library; Playwright for end-to-end | |

**Mobile-first is a constraint, not a preference.** Every view is built at 375 px first and widened.
The three operations named in the vision's success criteria — read a ticket, comment, change status —
are reachable one-handed, with the primary action inside thumb reach.

**Invariant F1 — components never call `fetch` directly.** All I/O goes through `src/api/`, whose
types are generated from the OpenAPI document. A component that talks to the network directly bypasses
error mapping, auth refresh and the type contract at once.

**Invariant F2 — `actor.kind` is rendered, never inferred.** Wherever an actor appears, the UI shows
whether it is a person or an agent, from the field. No badge derived from a name pattern, no heuristic.

## 4. Database — PostgreSQL 16+

- **Row-level security is the multi-project boundary.** Every project-scoped table has an RLS policy
  keyed on `current_setting('nodera.project_ids')`, set from the authenticated context at the start of
  each transaction. Application code that forgets a `WHERE project_id = …` returns nothing rather than
  another project's rows.
- **The application role cannot escalate.** It holds no `BYPASSRLS`, and on `audit_event` it holds
  `INSERT` and `SELECT` only (invariant AU1). Migrations run as a separate, higher-privileged role.
- **Identifiers are unquoted lowercase `snake_case`,** enforced by a CI check. A quoted mixed-case
  identifier is a permanent, invisible source of "works on my machine".
- **Migrations are forward-only** and follow expand/contract. An applied migration is never edited —
  a mistake is corrected by a new migration.
- Monetary and durational values are not part of this model; timestamps are `timestamptz`, always UTC.

## 5. Identity and access

Two credential shapes, one authorisation path.

**Humans** sign in via OIDC, or local email + one-time code where no provider is configured. The
result is a short-lived access JWT (15 min) plus a rotating opaque refresh token.

**Agents** authenticate with a personal access token (`nod_pat_…`), presented as a bearer token and
stored only as an Argon2id hash (invariant CR1). A PAT belongs to exactly one agent actor, carries its
own scopes, and can expire.

Both produce the same `ActorContext`:

```kotlin
data class ActorContext(
    val actorId: ActorId,
    val kind: ActorKind,             // HUMAN | AGENT
    val surface: Surface,            // WEB | REST | MCP | SYSTEM
    val onBehalfOf: ActorId?,        // delegation chain, audit invariant AU4
    val requestId: RequestId,
)
```

Every use case takes an `ActorContext` as its first parameter. It is not ambient, not a thread-local
and not a global: making it a parameter is what makes "who is acting" impossible to forget, and what
makes the permission check impossible to skip silently.

## 6. The MCP server

`:api-mcp` exposes Nodera's capabilities as MCP tools and resources. Full surface and schemas:
[`MCP.md`](MCP.md).

- **Transports:** `stdio` for a locally spawned server, streamable HTTP for a shared deployment.
- **Authentication:** the same PAT as the REST API. There is no MCP-only credential type and no
  unauthenticated mode, in any environment.
- **Every tool call is a use case call.** The tool layer validates arguments against its JSON-Schema,
  builds an `ActorContext` with `surface = MCP` and `tool_name` set, and calls `:application`.
- **Every tool call is audited,** including denied ones. A denial is information, and an audit trail
  that records only successes cannot answer what an agent tried to do.
- **Pagination is capped server-side** by `NODERA_MCP_MAX_PAGE_SIZE`, independent of the requested
  size. An agent asking for the entire backlog gets a page and a cursor.

## 7. Deployment

One backend process, one Postgres instance, static frontend assets behind any web server.

```
docker compose up   →   postgres:16 · nodera-api (JVM) · nodera-web (static)
```

The image is built once and promoted; configuration comes from the environment, never from a baked-in
file. No environment-specific build exists — a build that differs per environment is a build that was
never tested where it runs.

## 8. Decisions recorded elsewhere

Structural choices carry their reasoning in [`adr/`](adr/README.md), not here. This document describes
the architecture as it is; the ADRs record why each part is that way and what was rejected.
