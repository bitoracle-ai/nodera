---
summary: Kotlin backend conventions — module boundaries and what the build enforces, ActorContext as a parameter, error modelling, transactions, and the patterns that keep adapters thin.
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

## ActorContext is a parameter, never ambient

```kotlin
suspend fun closeTicket(ctx: ActorContext, ticketKey: TicketKey, resolution: Resolution): Ticket
```

First parameter, every use case, no exceptions. Not a thread-local, not a coroutine context element,
not a request-scoped singleton. Making it a parameter is what makes "who is acting" impossible to
forget and the permission check impossible to skip silently.

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

## Style gates

`./gradlew ktlintCheck detekt test`. Detekt's complexity thresholds are deliberately strict; a function
that trips them is usually a use case doing two things.
