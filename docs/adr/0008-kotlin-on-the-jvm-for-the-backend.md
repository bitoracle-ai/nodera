# ADR-0008 — Kotlin on the JVM for the backend

- **Status:** Accepted (2026-08-22)
- **Context documents:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md) § 2 · [`../VISION.md`](../VISION.md) § 3 ·
  [`../../skills/critical-invariants.md`](../../skills/critical-invariants.md) ·
  [`0005-mcp-as-sibling-surface.md`](0005-mcp-as-sibling-surface.md) ·
  [`0006-one-image-three-entrypoints.md`](0006-one-image-three-entrypoints.md)
- **Affects:** `backend/` in its entirety, `Dockerfile`, `.github/workflows/ci.yml`,
  `skills/backend-kotlin.md`, and every backend ticket.

## Context

This is the most expensive technology decision in the repository and it was the only structural one
with nothing recorded. Kotlin, Gradle, Ktor, Exposed and Flyway were all in place before this file
existed; [`../ARCHITECTURE.md`](../ARCHITECTURE.md) § 2.1 justifies the *libraries*, and no document
justified the *language*. A decision that exists only as an accomplished fact is a decision nobody
can argue with, which is the opposite of what an ADR is for.

It was re-examined deliberately, at the last moment where re-examining it was cheap. At the time of
writing the backend holds **1054 lines of Kotlin in 13 files** — configuration, health, migrate,
serve, readiness — and no application code whatsoever. A change of language would have cost about a
day. After CORE-01 and SEC-01 it costs a week; after API-01 it stops being a real option. So the
question was asked while the answer could still go either way.

**Forces:**

- **The product is an invariant-enforcement product.** Twelve critical invariants, a 302-line domain
  model, attenuation re-checked at use time, a closure gate, an append-only audit trail. The relevant
  question is not "which language is pleasant" but **which language moves the largest share of those
  twelve from reviewer attention to compiler output**. Invariant #1 — actor kind never gates
  permission — is the premise of the whole product; anything that lets it be violated quietly is
  disqualifying.
- **Two surfaces, one permission engine, in one process.** [ADR-0005](0005-mcp-as-sibling-surface.md)
  makes REST and MCP siblings that both call `:application` directly, because an MCP server calling
  its own REST API needs a credential and every option for that credential destroys per-agent
  identity. That is not a preference about layering; it forecloses a polyglot backend, because a
  second-language surface could not make that call in-process.
- **The target is an operated fleet, not only self-hosters.** Operability under load — profiling a
  live instance, taking a heap dump, reading flight-recorder data from a customer deployment — is a
  first-order force here, not an afterthought.
- **MCP `stdio` spawns a process per agent session.** [`../MCP.md`](../MCP.md) § 2 names it the
  default for a developer machine. Process start-up is therefore user-visible in a way it is not for a
  long-lived server, and the JVM is the worst common runtime on exactly that axis.
- **GitHub integration is an outbound port.** Within the scope fence ([`../VISION.md`](../VISION.md)
  § 3), Nodera links to commits, branches and pull requests by URL and records outcomes reported to
  it. That is inbound webhooks and outbound REST/GraphQL — ordinary HTTP work that constrains the
  language choice weakly.
- **The contributor pool is mixed and partly non-human.** An open-source tracker whose premise is that
  agents are participants will attract drive-by contribution and agent-generated patches, and more of
  that fluency exists in TypeScript and Python than in Kotlin.

## Decision

**1 — Kotlin on the JVM**, Gradle multi-module, the six modules of
[`../ARCHITECTURE.md`](../ARCHITECTURE.md) § 2, dependencies pointing inward only and enforced by the
build.

**2 — One backend process.** REST and MCP are in-process siblings over one `:application`. There is no
second runtime, no sidecar that holds business logic, and no service split along surface lines.

**3 — `:domain` stays framework-free *and* JVM-detail-free.** The first keeps invariants testable
without a container; the second keeps the Kotlin Multiplatform door open, which is the stated reason
the module is pure at all. A JVM-only type reaching `:domain` defeats the purpose of the rule and is a
review finding.

**4 — The `stdio` transport may be fronted by a non-JVM bridge.** A process that only frames and
forwards MCP messages, and makes no permission decision, does not violate ADR-0005 or invariant #2.
This is explicitly permitted so that the cold-start cost has a remedy that does not require reopening
this decision. What it must never become is a client of the REST API.

**5 — Protocol plumbing is taken from the official SDK, not written.** The MCP protocol layer comes
from `io.modelcontextprotocol:kotlin-sdk` unless a recorded evaluation rejects it. Hand-writing
transports, handshake and capability negotiation is weeks of work with no product in it.

**6 — This ADR does not pin versions.** The toolchain baseline lives in
`backend/gradle/libs.versions.toml` and moves without amending this file, per
[`README.md`](README.md) ("Not for: a library version bump").

## Consequences

- ✅ **Invariants become compiler output.** Value classes make `ActorId` and `ProjectId`
  non-interchangeable, so invariant #5's server-side scoping cannot be violated by argument order.
  Sealed hierarchies with exhaustive `when` make the error taxonomy that [`../MCP.md`](../MCP.md) § 4
  needs total rather than conventional. Null-safety removes an entire class of defect from a codebase
  whose worst failure mode is a permission check that silently did not run.
- ✅ **RLS can be tested negatively.** Testcontainers against a real PostgreSQL is a first-class JVM
  story, and DB-01's whole content is proving policies red when dropped. An in-memory substitute
  cannot test a policy that only exists in PostgreSQL.
- ✅ **Flyway is the reference implementation of forward-only migrations**, which invariant #7 requires
  and `scripts/lint_sql.py` reinforces from outside the JVM.
- ✅ **Fleet operability.** JFR, heap dumps, async-profiler and mature metric exporters are available
  on a running instance without a redeploy. This is the force that most cleanly separates the JVM from
  the alternatives below.
- ✅ **One process makes invariant #2 structurally true** rather than promised, and keeps
  [ADR-0006](0006-one-image-three-entrypoints.md)'s single image honest.
- ⚠️ **JVM cold start is a real cost on `stdio`** — roughly an order of magnitude worse than a Node
  process, on the one path a developer feels directly. Mitigations, in ascending order of commitment:
  AppCDS; the non-JVM bridge of decision 4; GraalVM native-image. **MCP-01 decides which**, and must
  not leave it implicit.
- ⚠️ **The Kotlin MCP SDK is pre-1.0** (`0.15.0` at the time of writing) with breaking changes between
  minor releases. Accepted as cheaper than the alternative — writing the protocol ourselves — but it
  is a maintenance subscription, not a free dependency.
- ⚠️ **A narrower drive-by contributor pool than TypeScript.** Partly offset: the same type system that
  narrows the pool also raises the floor on what an unfamiliar contributor — or an agent — can break
  without the build noticing.
- ⚠️ **Concentration risk.** Kotlin, Ktor, Exposed and kotlinx.* are all JetBrains. The hexagonal
  boundary is the mitigation that matters: `:domain` and `:application` depend on none of them, so a
  replacement is an adapter rewrite rather than a rewrite.
- ⚠️ **Toolchain weight.** The JVM and Gradle are heavier to set up than `node`; today the maintainer
  has no local JDK and the backend gates run only in a container. That is a real velocity tax and it
  is accepted knowingly.

## Alternatives considered

- **TypeScript on Node — the strongest challenger, and the one worth stating fairly.** It would have
  bought the reference MCP SDK, the best GitHub tooling that exists (Octokit), a cold start around
  fifty milliseconds, one language across frontend and backend, and the widest contributor pool.
  Rejected on the product's own premise: structural typing, `any`, and types that vanish at runtime
  make the twelve invariants weaker exactly where they must be strongest — a permission check that is
  skipped is a runtime fact, not a compile error. The operability gap on an operated fleet compounds
  it. This is the alternative to revisit first if the invariant set ever stops being the point.
- **Go.** Fast start, one static binary, excellent operational tooling — it answers the cold-start and
  the fleet force at once. Rejected on expressiveness: no sum types and no exhaustiveness checking, so
  the sealed-result taxonomy becomes a convention enforced by review, which is the thing this project
  most consistently refuses to rely on.
- **Rust.** The strongest type story of any candidate. Rejected as disproportionate: this is a product
  with a demanding *rule set*, not demanding *performance*, and the compile times and contributor
  scarcity buy guarantees the JVM already provides for this workload.
- **Java 21+.** Same platform, same operability, and with records, sealed interfaces and pattern
  matching the gap is far narrower than it was. Rejected on two specifics rather than on taste:
  null-safety in the type system, and value classes for domain identifiers. Both are load-bearing
  here — the first for the whole class of "the check did not run", the second for invariant #5.
- **Polyglot: Kotlin core with a TypeScript MCP server.** Superficially the best of both. Rejected
  structurally, not on preference: a separate process cannot call `:application` in-process, so it
  would reach Nodera over HTTP and need a credential to do so — which ADR-0005 rejects because every
  option for that credential destroys per-agent identity — or it would carry a second permission
  implementation, which invariant #2 forbids outright. The permitted residue of this idea is decision
  4: a bridge that decides nothing.
- **GraalVM native-image for the whole backend.** Would erase the cold-start consequence. Rejected as
  the default because reflection configuration for Ktor, Exposed and Flyway is a standing maintenance
  cost, and a native binary sits awkwardly with the one-image-three-entrypoints shape of ADR-0006. It
  remains available as the heaviest of the decision-4 mitigations.
