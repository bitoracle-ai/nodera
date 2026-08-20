---
summary: Database conventions — forward-only migrations, identifier casing, RLS on every project-scoped table, indexing rules, and how an invariant becomes a constraint rather than a comment.
read_when:
  - Before writing any migration.
  - When deciding whether a rule belongs in the database or in application code.
  - During review of anything under `db/migrations/`.
---

# Database design — Nodera

## Migrations are forward-only

- Expand/contract. Add the new shape, migrate, then remove the old shape in a later migration.
- **Never edit an applied migration.** Not to fix a typo, not to add a missing index. A mistake is
  corrected by a new migration, because someone somewhere has already applied the old one.
- One logical change per file. `V<n>__<snake_case_description>.sql`.
- Every migration is verified by **applying it to a scratch database**, not by reading it.

## Identifiers

Unquoted lowercase `snake_case`. Always. A quoted mixed-case identifier works perfectly until
something addresses it unquoted, and then fails in a way that reads like a missing table.

The only quoted identifiers permitted in this repository are extension names in
`create extension if not exists "pgcrypto"`. CI fails on any other.

## Put the invariant in the database when the database can hold it

The ordering, from strongest to weakest:

1. **A constraint or a foreign key** — the database refuses the row.
2. **A trigger** — for rules a constraint cannot express (immutability, graph acyclicity, chain
   validity). Raise with a specific `errcode` so the application can map it.
3. **A privilege** — `grant insert, select` and nothing else is stronger than any amount of care about
   not writing an update path.
4. **Application code plus a test** — only when none of the above can express it.

An invariant enforced at level 4 that could have been enforced at level 1 is a finding.

## Row-level security

Every project-scoped table gets RLS **in the same migration that creates it**. The policy reads
`current_project_ids()`, which is set from the authenticated context at the start of each transaction.

An empty setting matches nothing. That polarity is deliberate: a code path that forgot to establish
context reads zero rows and fails loudly, rather than reading everything and failing silently.

Prove a policy with a **negative test** that is demonstrably red when the policy is dropped. A policy
nobody has seen fail is a policy nobody has tested.

## Indexing

- Every foreign key gets an index. Without one, a delete on the parent scans the child table.
- Index the queries that actually run — the working-order query, the closure gate's unresolved-blocking
  lookup, the audit trail by entity.
- Use partial indexes where the query has a constant predicate (`where revoked_at is null`,
  `where outcome = 'denied'`). They are smaller and they document the access pattern.

## Types

- `timestamptz`, always, always UTC. Never `timestamp`.
- `citext` for anything compared case-insensitively by humans: handles, keys, labels, emails.
- `uuid` primary keys with `gen_random_uuid()`, except `audit_event`, which uses an identity `bigint`
  because it is append-only, high-volume and read in time order.
- Enums for closed sets that change by migration. `text` with a check constraint for sets the
  application must be exhaustive over (capabilities), so adding one does not lock the table.

## What does not belong in the database

Business decisions that need to be readable and testable in isolation: the closure gate, the
prioritisation order, attenuation. These live in `:domain` where they can be unit-tested without a
container and where a reviewer can find them.
