# ADR-0005 — MCP is a sibling surface, not a wrapper over REST

- **Status:** Accepted (2026-08-20)
- **Context documents:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md) § 1 · [`../MCP.md`](../MCP.md)
- **Affects:** `backend/api-mcp/`, `backend/api-rest/`, `backend/application/`, the parity test harness.

## Context

Nodera needs an MCP server so agents can work natively. The cheap implementation is an MCP layer that
issues HTTP calls to its own REST API — it reuses everything and ships in a fraction of the time.

That shape has a specific failure mode, and it is not performance. An MCP server that speaks to REST
must authenticate to REST, which means either a shared service credential (destroying per-agent
identity and the whole audit model) or forwarding the caller's token (which works until the day
someone adds a convenience path that does not).

The deeper problem is that a wrapper makes MCP structurally *derivative*. Anything REST cannot
express, MCP cannot either; anything REST expresses awkwardly for an agent stays awkward. The
capability an agent most needs — the itemised closure-gate refusal — is precisely the kind of thing a
REST API tends to flatten into a 409.

## Decision

**`:api-rest` and `:api-mcp` are siblings over `:application`.** Neither depends on the other, neither
calls the other over the network, and no business rule lives in either. Both translate a request into
a use case call and translate the result back.

Enforced structurally: `:api-mcp` does not depend on `:api-rest` in the Gradle build, so a shortcut is
a compile error rather than a review finding.

The one thing they share is the **error taxonomy** — a set of codes defined in `:application` and
mapped differently by each adapter. Shared meaning, not shared transport.

## Consequences

- ✅ Every agent authenticates as itself. There is no service credential and no token forwarding.
- ✅ Permission checks happen once, in the use case, so "one permission engine" is true by
  construction.
- ✅ The MCP surface can express what agents need — structured refusals, capability-filtered
  discovery, idempotency — without those shapes having to make sense as HTTP first.
- ⚠️ Two adapters to maintain. Every new capability is wired twice, and forgetting one is a real risk.
  Mitigated by the **parity test**, which is mandatory: the same denial driven through both surfaces,
  asserting the same outcome. Without it this decision degrades into two APIs that agree by accident.
- ⚠️ More initial work than a wrapper. Accepted knowingly.

## Alternatives considered

- **MCP as an HTTP client of the REST API:** rejected, for the credential and derivation reasons above.
- **MCP only, with REST generated from it:** rejected. Browsers are not MCP clients, and the web
  application is a first-class surface too.
- **A shared "gateway" layer both call:** rejected as a rename. That layer is `:application`, and
  adding another between it and the adapters buys nothing but a place for logic to hide.
