---
summary: The Nodera domain model — actor (human or agent), project, ticket, assignment, comment, review, capability grant and audit event — with the invariants each entity carries and the reasoning behind the shape.
read_when:
  - Before writing or changing a migration, a domain service or an API response shape.
  - When a feature seems to need a new entity, a new actor kind or a new permission dimension.
  - During review of anything that touches identity, permissions or the audit trail.
---

# Domain model — Nodera

The model has one organising idea: **`actor` is the only participant type.** There is no `user`
table. A human and an agent are two subtypes of the same principal, and every reference to "who did
this" points at `actor`.

Everything below is stated as an invariant where it is one. An invariant is enforced by the database
where the database can enforce it, and by a domain service plus a test where it cannot.

---

## 1. Entity map

```
                                  ┌───────────────┐
                                  │    actor      │  kind: human | agent
                                  └───────┬───────┘
                     ┌────────────────────┼────────────────────┐
                     │                    │                    │
            ┌────────▼───────┐   ┌────────▼────────┐  ┌────────▼─────────┐
            │  human_actor   │   │   agent_actor   │  │   credential     │
            │  email, …      │   │  owner_actor_id │  │  token hash, …   │
            └────────────────┘   └─────────────────┘  └──────────────────┘
                                          │
                     ┌────────────────────┼─────────────────────┐
                     │                    │                     │
        ┌────────────▼──────────┐ ┌───────▼──────────┐ ┌────────▼─────────┐
        │  project_membership   │ │ capability_grant │ │   audit_event    │
        │  role per project     │ │ verb per project │ │   append-only    │
        └────────────┬──────────┘ └──────────────────┘ └──────────────────┘
                     │
              ┌──────▼───────┐
              │   project    │  key space owner
              └──────┬───────┘
                     │
              ┌──────▼───────┐         ┌──────────────────────┐
              │    ticket    │◄────────┤  ticket_dependency   │
              └──────┬───────┘         └──────────────────────┘
                     │
     ┌───────────────┼───────────────┬──────────────────┐
     │               │               │                  │
┌────▼─────────┐ ┌───▼────────┐ ┌────▼─────┐  ┌─────────▼──────────┐
│ acceptance_  │ │  comment   │ │  review  │──┤   review_finding   │
│ criterion    │ │            │ │          │  │ blocking|non_block │
└──────────────┘ └────────────┘ └──────────┘  └────────────────────┘
```

## 2. `actor` — the root of the model

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `kind` | `actor_kind` | `human` \| `agent`. Immutable after insert. |
| `handle` | `citext` UNIQUE | Mention target. Unique across **both** kinds. |
| `display_name` | `text` | |
| `status` | `actor_status` | `active` \| `suspended` \| `retired`. |
| `created_at` / `updated_at` | `timestamptz` | |

**Invariant A1 — `kind` is immutable.** An agent never becomes a human and the reverse is
meaningless. Enforced by a trigger, not by convention: a mutable `kind` would silently rewrite the
meaning of every historical audit row that references the actor.

**Invariant A2 — an actor is never deleted.** Retirement sets `status = 'retired'` and revokes
credentials. Deletion would orphan audit rows, and an audit trail with holes is not an audit trail.
Hard erasure for data-protection requests is an operator runbook that rewrites `display_name` and
`email` to tombstones while preserving row identity.

**Invariant A3 — the handle namespace is shared.** `@deploy-bot` and `@anna` come from one pool, so
a mention is unambiguous and an agent can never be created with a handle that shadows a person's.

### 2.1 `human_actor`

`actor_id` PK/FK, `email citext UNIQUE`, `locale`, `timezone`. Authentication is OIDC or local
email+OTP.

### 2.2 `agent_actor`

| Column | Notes |
|---|---|
| `actor_id` | PK/FK to `actor`. |
| `owner_actor_id` | FK to `actor`. **Accountable owner** — the actor answerable for what this agent does. |
| `runtime_hint` | Free text, e.g. `claude-code`, `codex`, `custom`. Descriptive only. |
| `contact_url` | Where a human reaches whoever runs it. |
| `retired_at` | Set when the agent is decommissioned. |

**Invariant A4 — every agent has an owner, and the ownership chain terminates at a human.** An agent
may own another agent (a supervisor spawning workers is a real shape), but the chain must reach a
`human` actor in a bounded number of steps and must not contain a cycle. Enforced by a recursive
check on insert and update.

**Why an owner at all:** § 4's attenuation rule needs a grantor, and accountability needs a person.
"The agent did it" is not an answer to an incident; "the agent Anna runs did it, with the grants Anna
gave it" is.

**Invariant A5 — `runtime_hint` never drives behaviour.** It is displayed and logged. The moment a
code path branches on it, Nodera has a model-provider integration, which § 3 of the vision forbids.

## 3. `project` — the multi-project boundary

| Column | Notes |
|---|---|
| `id` | `uuid` PK. |
| `key` | `citext` UNIQUE, e.g. `NODERA`. Short, stable, immutable. |
| `name`, `description` | |
| `archived_at` | Archived projects are readable, never writable. |

**Invariant P1 — every project-scoped row carries `project_id`, and every query filters on it
server-side** from the authenticated context, never from a request parameter. Enforced by
row-level security in Postgres, so a forgotten `WHERE` in application code cannot leak across
projects. This is the invariant that makes one deployment safe for unrelated teams.

**Invariant P2 — ticket key spaces are per project.** `CI-13` in one project and `CI-13` in another
are different tickets. The globally unique reference is `PROJECTKEY/TICKETKEY` (`NODERA/CI-13`).

## 4. Permissions — roles, capabilities and attenuation

Two layers. Roles are the ergonomic surface; capabilities are what is actually checked.

**`project_membership`** — `(project_id, actor_id)` unique, plus `role`, `granted_by_actor_id`,
`expires_at`.

Every member holds `project.read` and `actor.read`: they are the floor a role stands on rather than
something that distinguishes one role from another, and a member who can read a ticket but not reach
the project it is in, or resolve a mention target, cannot use the verbs it does hold. The sets are
strictly nested, `observer ⊂ contributor ⊂ maintainer ⊂ owner`.

| Role | Default capabilities, on top of `project.read` and `actor.read` |
|---|---|
| `owner` | everything, including `project.admin` and `member.grant` |
| `maintainer` | contributor **+** `ticket.close`, `ticket.assign`, `comment.moderate`, `review.submit`, `audit.read` |
| `contributor` | observer **+** `ticket.create`, `ticket.update`, `ticket.transition` (not `close`), `ticket.assign_self`, `comment.create` |
| `observer` | `ticket.read`, `comment.read` |

The authoritative list is `ProjectRole.defaultCapabilities()` in `:domain`, whose KDoc records why
`ticket.assign_self` sits at contributor and `audit.read` at maintainer.

**`capability_grant`** — `(project_id, actor_id, capability)` with `granted_by_actor_id` and an
optional `expires_at`. Grants add or remove individual verbs on top of the role.

**Invariant C1 — attenuation.** An actor's effective capability set is always a subset of the
granting actor's effective set *at the moment the grant is used*, not merely when it was created. If
Anna loses `ticket.close`, every agent that holds it via Anna loses it in the same instant. Without
the re-check at use time, revoking a person's access leaves their agents running with the access she
no longer has — the exact failure the model exists to prevent.

**Invariant C2 — one permission engine.** REST and MCP call the same `PermissionService`. There is no
second code path, no MCP-specific shortcut and no "trusted internal" bypass. Proved by a test that
drives the same denial through both surfaces (vision § 6.2).

**Invariant C3 — an agent cannot grant more than it holds, and cannot grant `member.grant` at all.**
Privilege delegation terminates; an agent cannot bootstrap itself a wider grant through a chain of
agents it creates.

## 5. `ticket`

| Column | Notes |
|---|---|
| `id` | `uuid` PK. |
| `project_id` | FK. |
| `key` | e.g. `CI-13`. Unique per project, immutable. |
| `prefix` / `number` | Derived parts of `key`; `number` drives the per-prefix sequence. |
| `title` | |
| `body` | Markdown — motivation, current state, approach, affected files, verification. |
| `priority` | `P1` \| `P2` \| `P3` \| `P4`. |
| `status` | see § 5.1. |
| `effort` | Free text, deliberately coarse (`~1 d`). |
| `reporter_actor_id` | Who filed it. |
| `assignee_actor_id` | Nullable. **One** accountable assignee, human or agent. |
| `created_at` / `updated_at` / `closed_at` | |

**Invariant T1 — one accountable assignee.** Not a list. Shared ownership is the state in which
neither party is responsible, and it is the reason work sits. Additional participants are expressed
by mention, comment or review, all of which are already modelled.

**Invariant T2 — the assignee may be an agent, and nothing in the schema, the API or the UI treats
that as a special case.** No `is_bot` branch, no separate `assigned_agent_id` column. This invariant
is the load-bearing one: the day a code path asks "is the assignee a human?" before deciding what is
allowed, the premise is gone.

**Invariant T3 — `key` never changes and is never reused,** including after deletion. External
references (commits, chat, other tickets) must not silently retarget.

### 5.1 Status and transitions

Fixed state set. Per-project configurable workflows are outside the scope fence.

```
   open ──► in_progress ──► in_review ──► closed
     ▲           │              │            ▲
     └───────────┴──── blocked ─┘            │
                     │                       │
                     └───────────────────────┘  (blocked → closed only as `wont_do`)
```

`closed` carries a `resolution`: `done` | `wont_do` | `duplicate` | `superseded`.

**Invariant T4 — closure is gated, not clicked.** A transition to `closed` with resolution `done` is
refused while any acceptance criterion is unmet, any review finding of severity `blocking` is
unresolved, or no review exists. The gate lives in the domain service and returns the specific
failing item, so the API answer is actionable rather than a flat 409.

### 5.2 `acceptance_criterion`

`(ticket_id, ordinal)` with `text`, `met bool`, `met_at`, `met_by_actor_id`.

Criteria are rows, not Markdown checkboxes, because T4 gates on them and because "who ticked this,
and when" is exactly the kind of claim that turned out to need an audit trail in practice.

### 5.3 `ticket_dependency`

`(ticket_id, depends_on_ticket_id)`, both in the same project.

**Invariant T5 — the dependency graph is acyclic,** checked on insert. A cycle makes the working
order undefined, and the working order is what the whole method runs on.

## 6. `comment`

`id`, `ticket_id`, `author_actor_id`, `body` (Markdown), `in_reply_to_comment_id`, `created_at`,
`edited_at`, `deleted_at`. Mentions are extracted into `comment_mention(comment_id, actor_id)` on
write.

**Invariant CM1 — authorship is never rewritten.** Editing preserves `author_actor_id` and stamps
`edited_at`; deletion is a tombstone that keeps the row and its position in the thread.

**Invariant CM2 — an agent comment is stored, rendered and notified exactly like a human comment.**
The reader is told *who* wrote it (via `actor.kind`, always present in the API response) and is never
left to infer it from the text.

## 7. `review` and `review_finding`

`review`: `ticket_id`, `reviewer_actor_id`, `round` (1, 2, 3…), `verdict`
(`approved` | `changes_required`), `summary`, `created_at`.

`review_finding`: `review_id`, `severity` (`blocking` | `non_blocking`), `title`, `detail`,
`resolved_at`, `resolved_by_actor_id`.

**Invariant R1 — the reviewer is not the author and not the assignee.** Enforced in the domain
service. An agent may review a human's work and a human may review an agent's; what is refused is
reviewing one's own.

**Invariant R2 — review history is append-only and rounds are preserved,** including a later verdict
that contradicts an earlier one. The contradiction is the signal. Collapsing to a single current
verdict destroys precisely the information that makes a review record worth keeping.

## 8. `credential`

`actor_id`, `kind` (`session` | `personal_access_token` | `oidc_link`), `token_hash`, `label`,
`scopes`, `expires_at`, `last_used_at`, `revoked_at`.

**Invariant CR1 — a token is stored only as an Argon2id hash.** The plaintext is returned exactly
once, at creation, and never logged, echoed in an error, or included in any API response afterwards.

**Invariant CR2 — a credential belongs to exactly one actor and grants nothing beyond that actor's
effective capabilities.** There is no shared, ambient or service-wide token. An agent that needs
access gets its own actor and its own credential.

## 9. `audit_event` — append-only

| Column | Notes |
|---|---|
| `id` | `bigint` identity. |
| `occurred_at` | `timestamptz`. |
| `project_id` | Nullable for deployment-level events. |
| `actor_id`, `actor_kind` | `actor_kind` is denormalised deliberately — see below. |
| `on_behalf_of_actor_id` | The delegation chain: the actor whose request caused this one. |
| `surface` | `web` \| `rest` \| `mcp` \| `system`. |
| `tool_name` | MCP tool name, or the REST route. |
| `action` | e.g. `ticket.status_changed`. |
| `entity_type`, `entity_id` | |
| `before`, `after` | `jsonb`, the changed fields only. |
| `request_id` | Correlates every event of one request. |

**Invariant AU1 — append-only at the database level.** The application role holds `INSERT` and
`SELECT` on this table and nothing else. Not "we do not update it" — it cannot.

**Invariant AU2 — `actor_kind` is copied into the row.** Denormalisation on purpose: the question
"was this done by a human or an agent?" must be answerable from the audit table alone, at the time it
happened, without joining a table whose current contents may have changed.

**Invariant AU3 — every mutation writes exactly one event, in the same transaction as the mutation.**
Not best-effort, not asynchronous. A mutation that commits without its audit row is a defect of the
same severity as losing the mutation.

**Invariant AU4 — `on_behalf_of_actor_id` is recorded whenever an actor acts because another asked
it to.** This is what turns "the agent closed the ticket" into "the agent closed the ticket, acting
on Anna's instruction, through the MCP tool `ticket_transition`."

## 10. Markdown interchange

Tickets import from and export to Markdown with YAML frontmatter. This is a supported, tested,
round-tripping format, not a convenience export.

Frontmatter: `id`, `title`, `priority`, `status`, `effort`, `depends_on`, `created`, `updated`,
`closed`. Body sections and the acceptance-criteria checklist map to `ticket.body` and
`acceptance_criterion` rows; the review record maps to `review` and `review_finding`.

**Invariant M1 — round-trip fidelity.** `import(export(t))` is semantically equal to `t`, review
history included. Enforced by a property test over generated tickets, not by a single fixture.

**Why it earns its place in the model:** the reference workflow (vision § 5) lives in this format
today, git history and all. An adoption path that discards the review record would ask teams to throw
away the most expensive thing they have.
