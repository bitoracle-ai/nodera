---
applyTo: "backend/**"
---

# Backend (Kotlin) — path-specific rules

- Dependencies point inward only: `:domain` <- `:application` <- adapters. The `:domain` module is
  framework-free (no Ktor, no SQL, no JSON, no logging framework) and the build enforces it.
- Every use case takes `ActorContext` as its FIRST parameter. Never ambient, never a thread-local.
- Adapters (`:api-rest`, `:api-mcp`) never decide permissions, never transition domain state, never
  write audit events and never issue SQL.
- Never branch on `actor.kind` to decide what is permitted. It is for display and audit only.
- Every mutation writes exactly one audit event inside the mutation's own transaction.
- Gates: `./gradlew ktlintCheck detekt test`. Testcontainers needs a running Docker daemon.
