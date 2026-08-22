# ADR-0009 — The MCP server stays in-process; its protocol layer is not written here

- **Status:** Accepted (2026-08-22)
- **Context documents:** [`../MCP.md`](../MCP.md) § 2 ·
  [`../../skills/mcp-integration.md`](../../skills/mcp-integration.md) ·
  [`0005-mcp-as-sibling-surface.md`](0005-mcp-as-sibling-surface.md) ·
  [`0006-one-image-three-entrypoints.md`](0006-one-image-three-entrypoints.md) ·
  [`0008-kotlin-on-the-jvm-for-the-backend.md`](0008-kotlin-on-the-jvm-for-the-backend.md)
- **Affects:** `backend/api-mcp/`, `backend/gradle/libs.versions.toml`, `docs/MCP.md`,
  `tickets/open/MCP-01.md`, and every later MCP ticket.

## Context

[ADR-0005](0005-mcp-as-sibling-surface.md) settled that MCP is a sibling of REST. It did not settle
**what implements the protocol**, and MCP-01 was about to answer that by default rather than by
decision: its approach reads *"Transport: stdio first, streamable HTTP behind the same handler set"*
and *"JSON-Schema generated from the kotlinx.serialization DTOs"*, budgeted at three days alongside
nine tools, capability-filtered discovery and the parity harness. That is a hand-written MCP protocol
stack — framing, initialise handshake, capability negotiation, session handling, two transports — with
no line of product in it, on an estimate that assumed it away.

Two questions therefore need recorded answers, because both will be asked again by someone reasonable.

**Forces:**

- **An official Kotlin SDK exists.** `io.modelcontextprotocol:kotlin-sdk`, maintained in collaboration
  with JetBrains, covering stdio, Streamable HTTP, SSE and WebSocket, with Ktor integration and a
  `ChannelTransport` intended for tests. It is at `0.15.0` — pre-1.0, with breaking changes between
  minor releases — and it tracks specification proposals as they land.
- **"Why not write the MCP server in TypeScript?" is a fair question.** That is where the reference
  SDK lives, where cold start is fifty milliseconds rather than seconds, and where most MCP examples
  are written. Left unanswered in writing, it gets re-litigated in every MCP ticket.
- **The protocol moves.** MCP is a specification under active development. A hand-written stack is not
  a one-off cost; it is a standing obligation to track a moving target with no help.
- **The specification in this repository contradicts itself.** [ADR-0006](0006-one-image-three-entrypoints.md)
  decision 2 makes `mcp-stdio` an entrypoint *on the image*, running as `nodera_app` against the
  database. [`../MCP.md`](../MCP.md) § 2 shows a client configuration invoking a `nodera-mcp` command
  with `NODERA_API_BASE_URL` and a personal access token — which describes something that reaches a
  *remote* Nodera over the network. Those are two different programs, and only one of them can be "the
  stdio server" without ADR-0005's prohibition on an MCP server that calls its own API being violated.
- **Cold start is felt on exactly this path.** [ADR-0008](0008-kotlin-on-the-jvm-for-the-backend.md)
  accepted JVM start-up as a consequence and named a remedy without choosing one.

## Decision

**1 — The MCP server is Kotlin, in the same process as `:application`.** This is not open without
reopening [ADR-0005](0005-mcp-as-sibling-surface.md) and invariant #2 together: a server in another
runtime cannot call the use case in-process, so it would reach Nodera over HTTP and need a credential
to do it — destroying per-agent identity — or carry a second permission implementation, which
invariant #2 forbids. The language question for the *server* is closed.

**2 — The protocol layer comes from the official Kotlin SDK.** `:api-mcp` depends on
`io.modelcontextprotocol:kotlin-sdk-server` for framing, handshake, capability negotiation, session
handling and transports. Writing any of that by hand requires a recorded rejection of this decision,
not merely a preference for control.

What the SDK does **not** absolve: everything
[`../../skills/mcp-integration.md`](../../skills/mcp-integration.md) governs. Tool bodies stay thin
translations, the declared capability stays the argument to `PermissionService.require(...)`, parity
stays tested, every call stays audited. The SDK carries the protocol; it carries none of the rules.

**3 — Two distinct programs, named distinctly.** The contradiction above is resolved by admitting there
are two:

| Program | What it is | Talks to |
|---|---|---|
| `mcp-stdio` (image entrypoint, ADR-0006) | A real MCP server. In-process `:application`, own database connection. | PostgreSQL |
| `nodera-mcp` (the bridge, optional) | **Not a server.** Forwards MCP frames to a deployment's Streamable HTTP endpoint. | A Nodera deployment, over MCP |

The bridge is permitted because it makes no permission decision and holds no domain logic — it is a
transport shim, and ADR-0005's prohibition is about *deciding*, not about *forwarding*. It carries the
caller's PAT through unchanged; it never holds one of its own. **It must never speak to the REST API.**

**4 — `docs/MCP.md` § 2 is corrected to say which of the two its client configuration shows.** MCP-01
owns that edit, in the same package that makes either one real.

**5 — MCP-01 chooses the cold-start remedy explicitly**, from ADR-0008's ladder: AppCDS, the bridge of
decision 3, or GraalVM native-image. Not deciding is not an option the package may exercise; whatever
it does becomes the answer by default, which is how this ADR became necessary in the first place.

## Consequences

- ✅ **MCP-01's real scope becomes visible.** Nine tools, capability-filtered discovery, the parity
  harness and the audit path — with transports supplied. The three-day estimate becomes plausible
  instead of optimistic, and for the first time it is an estimate of the work that has product in it.
- ✅ **Specification drift is somebody else's maintenance.** New transports and protocol revisions
  arrive as a dependency bump rather than as a reimplementation.
- ✅ **The TypeScript question has a written answer** that points at the structural reason rather than
  at taste, so the next person to raise it gets an argument to check instead of a preference to accept.
- ✅ **`ChannelTransport` gives the parity harness a transport with no process and no socket**, which
  is exactly what a test asserting "same denial through both surfaces" wants.
- ⚠️ **A pre-1.0 dependency in a load-bearing position.** `0.15.0` breaks between minor releases; recent
  ones changed concurrent message handling and rejected duplicate feature registration that was
  previously silent. Version bumps here need reading, not automation. Accepted as smaller than the
  alternative, but it is a subscription.
- ⚠️ **The bridge, if MCP-01 takes it, is a second artifact** — in tension with
  [ADR-0006](0006-one-image-three-entrypoints.md) decision 1, "one image", and with the release path
  OPS-02 has not yet proved once. If it is taken, ADR-0006 is amended in the same package rather than
  quietly contradicted. If it is not, the cold-start consequence of ADR-0008 stands and AppCDS is the
  fallback.
- ⚠️ **An SDK's shape leaks into `:api-mcp`.** Its types will appear in the adapter. That is acceptable
  precisely where it happens — `:api-mcp` is an adapter, and the module boundary already forbids those
  types from reaching `:application` or `:domain`. A reviewer should still watch for an SDK type in a
  use case signature; the build will not catch that one, because the dependency direction is legal.

## Alternatives considered

- **Write the protocol layer, as MCP-01 implied.** Rejected: weeks of work with no product in it,
  against a moving specification, to arrive at what a maintained dependency already provides. The
  usual argument for it — full control over framing and error shape — is not needed here, because the
  things this project is particular about (capability declaration, parity, audit, error *structure*)
  all sit above the protocol layer and are unaffected by who wrote it.
- **A TypeScript MCP server as a separate process.** The reference SDK, the fast start, the large
  example corpus. Rejected structurally, per decision 1 and
  [ADR-0008](0008-kotlin-on-the-jvm-for-the-backend.md)'s polyglot alternative — it cannot reach
  `:application` without a credential that destroys per-agent identity. The permitted residue is the
  bridge, which decides nothing.
- **Wait for the SDK to reach 1.0 and hand-write until then.** Rejected: it inverts the cost. Migrating
  a thin adapter onto a stable SDK later is cheap; maintaining a hand-written protocol stack in the
  meantime is not, and MCP-01 is the first of at least three MCP packages.
- **GraalVM native-image for `mcp-stdio` alone.** Would answer cold start without a second language,
  but it is a second *build* of the same code with its own reflection configuration, and it is the
  heaviest option on the ladder. Left available to MCP-01 under decision 5 rather than chosen here.
- **Drop `stdio` and support Streamable HTTP only.** Would dissolve the cold-start problem entirely.
  Rejected: [`../MCP.md`](../MCP.md) § 2 names stdio the default for a developer machine, and a tracker
  for agents that cannot be run by a locally spawned agent runtime has given up the case it exists to
  serve.
