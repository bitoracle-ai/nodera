---
applyTo: "db/migrations/**"
---

# Database migrations — path-specific rules

- Forward-only, expand/contract. NEVER edit an applied migration; correct it with a new one.
- Identifiers are unquoted lowercase snake_case. A quoted mixed-case identifier fails CI.
- Every project-scoped table gets an RLS policy in the same migration that creates it.
- Every foreign key gets an index.
- No string interpolation into SQL, anywhere.
- Verify by applying to a scratch database, not by reading — `make verify-db` is that, and it never
  touches the development database. Never verify with `make migrate`, which does. An RLS policy is
  proved by a negative test that is demonstrably red when the policy is dropped.
