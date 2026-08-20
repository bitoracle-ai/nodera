---
id: MCP-01
title: MCP server with the orientation and read tools
priority: P2
status: open
effort: ~3 d
depends_on: [CORE-01, SEC-01, CORE-03]
created: 2026-08-20
updated: 2026-08-20
note: Depends on API-01 only for the shared error taxonomy, not for logic.
---

# MCP-01 · MCP server with the orientation and read tools

**Priority:** P2
**Effort:** ~3 d

# Motivation / context

MCP is the agent's native surface, and the point of the architecture is that it is a sibling of
REST rather than a wrapper. Building the read half first lets the parity harness exist before any
mutating tool can drift.

## Current state (honest)

`:api-mcp` is an empty module. `docs/MCP.md` specifies the full surface; nothing implements it.

## Approach

1. Transport: stdio first, streamable HTTP behind the same handler set. This replaces the
   `mcp-stdio` stub OPS-01 ships — that entrypoint exits non-zero naming this ticket until it
   does — and keeps the rule OPS-01 established: MCP framing on stdout, every diagnostic on
   stderr.
2. Tools: `whoami`, `project_list`, `project_get`, `actor_search`, `ticket_search`, `ticket_get`,
   `ticket_next`, `comment_list`, `review_list`.
3. `tools/list` filtered by the caller's effective capabilities — discovery scoped to what the
   caller can actually do.
4. The parity harness: one test driving a denial through REST and through MCP, asserting the same
   outcome. This is the fixture every later MCP ticket extends.
5. JSON-Schema generated from the kotlinx.serialization DTOs — one definition, not two.

## Acceptance criteria

- [ ] `whoami` returns the effective capability list per project, not a role name.
- [ ] `tools/list` omits every tool the caller lacks the capability for; proved by a test with two
      actors of different grants.
- [ ] `ticket_next` honours priority order and dependency readiness, and returns the reason it
      chose that ticket.
- [ ] Every call is audited with `surface = mcp` and `tool_name` set, including denials.
- [ ] The parity test passes for at least three distinct capabilities.
- [ ] `limit` is clamped server-side regardless of the requested value.
- [ ] The `mcp-stdio` entrypoint from OPS-01 serves a real session, and nothing but MCP framing
      reaches stdout — proved by a test that is red when a diagnostic is written there.
- [ ] The streamable HTTP session is either **not** bound to a process, or the sticky-routing
      requirement is written into `docs/MCP.md` and ADR-0006's decision 6, point 5, is amended to match. Decided
      in this package, because after it the answer is whatever the code happened to do.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/api-mcp/src/main/kotlin/ai/nodera/api/mcp/`.
- `backend/api-mcp/src/test/kotlin/.../SurfaceParityTest.kt`.

## Verification

`./gradlew :api-mcp:test`. Connect a real MCP client over stdio and confirm `tools/list` differs
between two actors with different grants.
