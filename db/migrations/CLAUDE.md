# Local guide — `db/migrations/`

Read before writing any migration. Root rules still apply.

Full reference: [`../../skills/database-design.md`](../../skills/database-design.md) ·
[`../../docs/DOMAIN_MODEL.md`](../../docs/DOMAIN_MODEL.md).

## Forward-only. No exceptions.

**Never edit an applied migration** — not to fix a typo, not to add a missing index. Someone
somewhere has already applied it, and `scripts/lint_sql.py` compares checksums against
`.checksums` and fails on a change. Correct a mistake with a NEW migration.

Expand/contract: add the new shape, migrate, remove the old shape in a later migration.

## Conventions

- `V<n>__<snake_case_description>.sql`, one logical change per file.
- Identifiers are **unquoted lowercase `snake_case`**. Only extension names may be quoted. A
  quoted mixed-case identifier works until something addresses it unquoted, and then fails in
  a way that reads like a missing table.
- Every foreign key gets an index — without one, a delete on the parent scans the child.
- `timestamptz` always, UTC always. Never `timestamp`.

## Row-level security in the same migration

Every project-scoped table gets its RLS policy in the migration that creates it. The policy
reads `current_project_ids()`, set from the authenticated context per transaction.

An empty setting matches nothing. That polarity is deliberate: a code path that forgot to
establish context reads zero rows and fails loudly, rather than reading everything.

## Put the invariant where it can actually hold

Strongest to weakest: a constraint or foreign key → a trigger (with a specific `errcode`) → a
privilege grant → application code plus a test. An invariant enforced at the last level that
could have been enforced at the first is a review finding.

## Verify by applying, not by reading

```
make verify-db            # applies the sequence twice on a database it creates and drops again
python scripts/lint_sql.py
```

That is the CI database lane locally, and it never touches the development database. Know what it
does not do: it depends on `up`, so it runs inside the developer's Postgres — starting that
container if it was stopped and leaving it running — and the `nodera_app` role the migrations create
stays in the cluster. Never verify by migrating the development database itself
([`../../skills/testing.md`](../../skills/testing.md)).

An RLS policy is proved by a negative test that is demonstrably red when the policy is dropped.
A policy nobody has seen fail is a policy nobody has tested.
