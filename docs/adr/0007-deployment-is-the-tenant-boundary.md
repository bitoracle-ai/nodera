# ADR-0007 — The deployment is the tenant boundary

- **Status:** Accepted (2026-08-20)
- **Context documents:** [`../VISION.md`](../VISION.md) §§ 2, 3 · [`../ARCHITECTURE.md`](../ARCHITECTURE.md) § 4 ·
  [`0006-one-image-three-entrypoints.md`](0006-one-image-three-entrypoints.md)
- **Affects:** the data model as a whole — whether a `tenant` sits above `project` — and therefore
  every migration, every RLS policy and every permission check.

## Context

Nodera is built to be self-hosted, and it may also be run by the maintainers on a customer's behalf,
or offered as a hosted service. That raises a question the repository has so far answered only by
omission: **is one deployment shared by many customers, or does each customer get their own?**

The current model is implicit but consistent. `VISION.md` § 2 says one deployment serves many
independent projects; success criterion 3 requires three projects in one deployment with no shared key
space and no cross-project read. There is no `tenant`, no `organization` and no `customer` anywhere in
the migrations, the domain model or the API contract. The multi-project boundary is enforced by RLS on
`current_setting('nodera.project_ids')`.

This has to be settled before CORE-01, because ADR-0001's own argument applies: a boundary of this
kind is fixed at the bottom or not at all.

**Forces:**

- An organisation that asks to be *operated for* almost always requires dedicated data — residency,
  its own backup and restore, a data processing agreement, sometimes its own key management. Shared
  tenancy makes each of those harder, not easier.
- A tenant column above `project` doubles every permission check and every migration before a single
  use case exists, and creates two nested scoping boundaries that can disagree with each other. Two
  boundaries means the interesting bug is the one where they differ.
- A hosted offering needs provisioning, billing, tenant lifecycle and fleet upgrades. That is a
  control plane. `VISION.md` § 3 already refuses the adjacent temptations — an agent runtime, a CI
  system — on the grounds that Nodera is the thing you talk to, not the thing that runs things.
- Isolation strength and cost per customer trade against each other directly, and only one of the two
  is a correctness property.

## Decision

**The unit of tenancy is the deployment.** One customer means one instance and one database. Nodera
gains no `tenant`, no `organization` and no `customer` entity, and `project` remains the outermost
scoping boundary inside a deployment.

**The control plane lives outside this repository.** Provisioning, billing, fleet upgrade orchestration
and tenant lifecycle are the operator's product, not Nodera's. What Nodera owes such an operator is
exactly the six stateless properties in [ADR-0006](0006-one-image-three-entrypoints.md), decision 6, plus:

- configuration entirely external, with no baked-in per-instance value;
- `migrate` as a job with a machine-readable exit status, so a fleet upgrade can report which instance
  failed rather than which instance is quiet;
- an instance identifier taken **from configuration** and attached to every structured log line and
  every OTLP span — never invented by the application, or two instances will disagree about who they
  are.

"Future-proof" here means Nodera stays a well-behaved unit that something else can multiply. It does
not mean Nodera grows a tenancy layer on speculation.

## Consequences

- ✅ The strongest isolation available — a separate database — is the default rather than an
  enterprise upsell. There is no query that can cross a customer boundary, because there is no shared
  database in which to write one.
- ✅ The self-hosted deployment and the operated deployment are the same deployment. The path a paying
  customer runs on is the path every contributor tests.
- ✅ Data residency, per-customer backup and restore, and per-customer retention are configuration,
  not features.
- ✅ One RLS boundary, already specified and about to be proved by DB-01's negative tests. No second,
  nested mechanism to keep in agreement with it.
- ⚠️ A cost floor per customer: a JVM process and a Postgres database each. For very small customers
  that floor may exceed what they would pay. Accepted — and revisited with real numbers, not now.
- ⚠️ No cross-customer queries. Aggregate usage reporting is a control-plane concern and has to
  collect from instances rather than read one table.
- ⚠️ A fleet upgrade is N upgrades. This is what makes ADR-0006's decision 5 — digest-addressable images, a
  migration job with a real exit status — load-bearing rather than tidy.

## Alternatives considered

- **Shared multi-tenant: a `tenant` table above `project`, RLS on `tenant_id`.** Rejected. It buys a
  lower per-customer cost floor and pays for it with a second nested scoping boundary, per-tenant
  quotas and rate limiting, noisy-neighbour handling, and an audit and credential model that has to be
  tenant-scoped — all before the product has a user. **What reversing this would take**, recorded so
  the door is visibly open: a `tenant` table, a `tenant_id` on every project-scoped table, the existing
  RLS policies extended rather than replaced, and tenant scoping added to credentials and audit.
  Ticket key spaces are already per-project and would be unaffected. It is a migration, not a rewrite,
  precisely because scoping is already server-side and RLS-enforced today — which is the property to
  protect if this decision is ever revisited.
- **Schema-per-tenant in one database.** The tempting middle. Rejected: the RLS policies are written
  against a session setting, so schema isolation would be a *second* mechanism beside them rather than
  a cheaper version of one; migrations still run once per schema; and a shared Postgres is a shared
  failure domain and a shared resource pool, which puts the noisy-neighbour problem in the database —
  the one place where it is hardest to contain.
- **Deciding later.** Rejected on the same grounds ADR-0001 gives: a participant boundary retrofitted
  above a permission model is the change that touches every table, and "later" is when there is data.
