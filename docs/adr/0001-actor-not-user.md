# ADR-0001 — Actor, not user: one participant type for humans and agents

- **Status:** Accepted (2026-08-20)
- **Context documents:** [`../VISION.md`](../VISION.md) § 1 · [`../DOMAIN_MODEL.md`](../DOMAIN_MODEL.md) § 2
- **Affects:** every table, every API response, every permission check. This is the decision the
  product exists to make.

## Context

Mixed teams of people and autonomous agents work the same backlog. Every established tracker meets
that with a bot account: the agent borrows a person's identity via a token that person owns.

Four consequences follow immediately, and none of them can be fixed above the data layer:

1. The audit trail attributes agent actions to a human.
2. The agent inherits the person's permissions in full — an agent that should only comment can close.
3. Assignment means a person, so agent work is tracked outside the system.
4. Agent output is unattributed and unaddressable in discussion.

**Forces:**

- A `users` table with an `is_bot` flag is the cheap option, and it is what every competitor did.
- Two parallel tables (`users`, `agents`) means every join, every foreign key and every permission
  check exists twice, and the two copies drift.
- Accountability must terminate at a person; a fully autonomous principal with no owner is not
  auditable in any useful sense.

## Decision

**One `actor` table with a `kind` discriminator (`human` | `agent`) and two subtype tables.** Every
reference to a participant — assignment, comment, grant, review, audit event, mention — points at
`actor`.

Two rules make it real rather than cosmetic:

1. **`actor.kind` never gates permission.** It is display and audit only, enforced by a lint sweep
   and by review. Capability is the only thing ever checked.
2. **Every agent has an owner, and the ownership chain terminates at a human** — so accountability
   exists without special-casing the agent at the permission layer.

## Consequences

- ✅ One code path for participants. No join has to remember two tables.
- ✅ The audit trail distinguishes actor kinds without a heuristic, because `actor_kind` is recorded
  on the event itself.
- ✅ Permission narrowing works identically for both kinds, so "an agent may only comment" is a
  grant rather than a feature.
- ⚠️ The subtype tables must be exhaustive and exclusive; a database cannot express that
  declaratively, so it is a checked invariant rather than a constraint.
- ⚠️ The rule in decision point 1 is easy to violate accidentally and cheap to violate deliberately.
  It needs a mechanical check, and that check is itself a maintenance obligation.

## Alternatives considered

- **`users` with `is_bot`:** rejected. The flag becomes a permission branch within one sprint, and
  then the whole model is decorative.
- **Separate `users` and `agents` tables:** rejected. Every relationship doubles, and the second copy
  is always the one that is forgotten in a new feature.
- **Agents as credentials owned by a user:** rejected — this is precisely the bot-account model and
  the source of all four consequences above.
- **No owner, fully autonomous agents:** rejected. Accountability has to reach a person, and an
  incident review with no human at the end of the chain is not a review.
