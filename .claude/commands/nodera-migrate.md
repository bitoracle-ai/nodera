---
description: Write and apply a database migration safely
argument-hint: "<what the migration should do>"
---

Write a migration for: $ARGUMENTS

**Read `db/migrations/AGENTS.md` and `skills/database-design.md` first.**

Rules that are not negotiable here:

- **Forward-only.** Never edit an applied migration. Correct a mistake with a new one.
- Next `V<n>__<snake_case_description>.sql`, one logical change.
- Unquoted lowercase `snake_case` identifiers. Only extension names may be quoted.
- Every foreign key gets an index.
- Every project-scoped table gets its RLS policy **in the same migration**.
- Put the invariant where it can actually hold: constraint → trigger → privilege → application
  code. The last one only when the others genuinely cannot express it.

Then:

```
make verify-db && python scripts/lint_sql.py
```

`verify-db` applies the sequence twice on a database it creates and drops. It runs inside the
developer's Postgres, though — starting that container if it was stopped and leaving it up — so
report that rather than "nothing left behind" (`db/migrations/AGENTS.md`). **Never verify by
migrating the development database**: `make migrate` targets it, and a forward-only migration
cannot be taken back out of it. Say what actually happened; reading a migration is not verifying
it.

If it adds an RLS policy or a trigger, write the **negative test** too — run it once with the
guard removed to confirm it goes red, and report both outcomes.
