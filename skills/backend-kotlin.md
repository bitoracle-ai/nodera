---
summary: Kotlin backend conventions — module boundaries and what the build enforces, SOLID as it actually applies here, ActorContext as a parameter, error modelling, transactions, the language baseline, and the patterns that keep adapters thin.
read_when:
  - Before any change under `backend/`.
  - When deciding which module a piece of logic belongs in.
  - During review of a Kotlin diff.
---

# Backend (Kotlin) — conventions

## Module boundaries

Dependencies point inward only. The build fails on a violation, so this is not a guideline.

```
:app  ->  :api-rest  ->  :application  ->  :domain
      ->  :api-mcp   ->  :application  ->  :domain
      ->  :persistence -> :application  ->  :domain
```

`:domain` is **framework-free**: no Ktor, no SQL, no JSON, no logging framework, no coroutine
dispatchers. It holds entities, value objects, state machines and invariants, and it is testable
without a database, a server or a container.

`:api-rest` and `:api-mcp` deliberately do **not** depend on `:persistence`. That is what makes
"no SQL in an adapter" a compile error rather than a review finding.

## SOLID, and which of it the build already holds

Useful here as **vocabulary for a review finding**, not as a poster. Four of the five are already
enforced or nearly so; naming them tells a reviewer where the machine stops helping.

| | What it means in this codebase | Held by |
|---|---|---|
| **S**RP | The transaction boundary is the use case, and a use case does one thing. Detekt's complexity thresholds are the mechanical proxy — a function that trips them is usually a use case doing two. | detekt, approximately |
| **O**CP | Adding a capability must not mean editing the permission engine's core. Role-to-capability defaults are a pure function; `capability` is `text` with a check constraint rather than an enum, so adding one does not lock the table. | design, not a gate |
| **L**SP | A port implementation may not narrow its interface's contract. One that throws where the interface declares a sealed result breaks every caller's exhaustiveness — silently, because the compiler was told the case cannot happen. | nothing — review |
| **I**SP | A port is defined by **the use case that needs it**, not by what the adapter can offer. | nothing — review |
| **D**IP | `:domain` depends on nothing. Ports are interfaces owned by `:application`; `:persistence` implements them. The arrow never reverses. | `checkModuleBoundaries` |

**ISP is the one with no mechanical guard, and the one that fails first.** The failure mode is a
single `TicketRepository` with forty methods that every test must stub and every change touches. When
a use case needs three of them, the port has three. Splitting a fat port later is a refactor across
every implementation; declaring a narrow one costs nothing now.

LSP is the second unguarded one, and its symptom is specific: an adapter that throws instead of
returning `Denied` or `GateFailed`. That converts an expected outcome into an exception the caller's
`when` never sees — the exact inversion of "errors are values in the domain".

## ActorContext is a parameter, never ambient

```kotlin
suspend fun closeTicket(ctx: ActorContext, ticketKey: TicketKey, resolution: Resolution): Ticket
```

First parameter, every use case, no exceptions. Not a thread-local, not a coroutine context element,
not a request-scoped singleton. Making it a parameter is what makes "who is acting" impossible to
forget and the permission check impossible to skip silently.

**Use cases live in `application/src/main/kotlin/ai/nodera/application/<area>/usecase/`.** That path
is the one `scripts/lint_invariants.py` scans for this rule, so it is a location convention with
teeth: a use case placed elsewhere is unchecked rather than exempt. `PermissionService` and the ports
are not use cases and sit beside those directories, not in them.

**Context parameters are a deliberate no**, and this is recorded because someone will reasonably
propose them. Kotlin 2.4 made them stable and they look purpose-built for exactly this. They are
still refused: their whole value is that the dependency stops appearing at the **call site**, and the
call site is where a reviewer reading an audit-sensitive path needs to see who is acting. For a
product whose core promise is a complete audit trail, call-site visibility is worth more than the
ceremony it costs. Reopen this only with an argument about the audit trail, not about verbosity.

## Errors are values in the domain, exceptions at the edge

`:domain` and `:application` return sealed results:

```kotlin
sealed interface ClosureResult {
    data class Closed(val ticket: Ticket) : ClosureResult
    data class GateFailed(val unmet: UnmetClosureRequirements) : ClosureResult
    data class Denied(val capability: Capability) : ClosureResult
}
```

Adapters map them to HTTP status codes or MCP error codes. A domain that throws for expected outcomes
forces every caller to reconstruct the taxonomy from exception types, and the MCP layer needs the
structured detail anyway (see `docs/MCP.md` § 4).

## Transactions

- The transaction boundary is the **use case**, in `:application`. Never in an adapter, never inside a
  repository method.
- The audit event is written inside that same transaction (invariant #3). A repository call that
  writes an audit row outside it is a BLOCKING finding.
- No business logic inside a `transaction { }` block that could run outside it — the block coordinates
  persistence, it does not decide.

## Nullability and value types

- Domain identifiers are value classes (`@JvmInline value class TicketId(val value: UUID)`). A function
  taking three raw `UUID`s is a function whose arguments can be swapped silently.
- Platform types from Java APIs are converted at the boundary. No `!!` in `:domain` or `:application`.

## Concurrency

- Suspending functions all the way down; no blocking call on a coroutine dispatcher without
  `Dispatchers.IO`.
- Ticket key allocation locks its `ticket_sequence` row with `select … for update`. Two concurrent
  creates in one project must not produce the same key.

## Language baseline

- **Explicit API mode** (`-Xexplicit-api=strict`). With six modules and an enforced dependency
  direction, "what is public" is an architectural question; this makes it a compiler question. Every
  public declaration states its visibility and its return type, so a module's surface cannot widen by
  type inference.
- **`kotlin.uuid.Uuid`, not `java.util.UUID`, in `:domain`.** The module is framework-free to keep the
  Kotlin Multiplatform door open — `java.util.UUID` is precisely the JVM detail that closes it, and
  the schema is full of UUID primary keys. Adapters convert at the boundary like any other platform
  type. Requires the toolchain baseline in CORE-01.
- **No `!!` in `:domain` or `:application`**, as below. Platform types from Java APIs are converted at
  the boundary, once, where the conversion can be reviewed.

## Style gates

`./gradlew ktlintCheck detekt test`. Detekt's complexity thresholds are deliberately strict; a function
that trips them is usually a use case doing two things.
