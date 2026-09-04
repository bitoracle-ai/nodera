---
applyTo: "db/migrations/**"
---

# Database migrations — path-specific rules

- Forward-only, expand/contract. NEVER edit an applied migration; correct it with a new one.
- Identifiers are unquoted lowercase snake_case. A quoted mixed-case identifier fails CI.
- Every project-scoped table gets an RLS policy in the same migration that creates it.
- Every foreign key gets an index.
- No string interpolation into SQL, anywhere.
- Verify by applying, not by reading — `make verify-db` is that, in a Postgres of its own that it
  removes again. Never verify with `make migrate`, which targets the development database. An RLS
  policy is proved by a negative test that is demonstrably red when the policy is dropped.
