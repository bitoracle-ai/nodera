---
summary: How to build features that treat humans and AI agents as the same kind of participant — the modelling rules, the permission and delegation mechanics, what agent-specific handling is legitimate, and the failure patterns that quietly demote agents back to bot accounts.
read_when:
  - Before touching identity, permissions, assignment, comments or the audit trail.
  - When a feature seems to need "special handling for agents".
  - During review of anything under `backend/application/` or `backend/api-mcp/`.
---

# Agent actors — building for two kinds of participant

This is the skill for the thing Nodera exists to get right. Read it before any change that touches who
may do what.

---

## 1. The rule, and the one question that enforces it

**`actor.kind` is for display and audit. It never decides what is allowed.**

Before writing any conditional that mentions an actor's kind, ask:

> *Am I about to decide a permission, or am I about to describe something to a reader?*

Describing is fine and often required — a UI badge, an audit column, a notification's wording. Deciding
is forbidden without exception.

```kotlin
// Deciding — FORBIDDEN
if (actor.kind == AGENT) throw Forbidden("agents may not close tickets")
if (assignee.kind == HUMAN) sendEmail(...) else postWebhook(...)   // also deciding: delivery capability

// Describing — correct
AuditEvent(actorKind = actor.kind, ...)
ActorBadge(kind = actor.kind)                                       // the UI shows what it is
```

The second forbidden line is the subtle one. Choosing a notification channel *looks* like description,
but it hard-codes an assumption about what agents can receive. Channel preference belongs on the actor
as data (`notification_channel`), which a human may also configure.

## 2. Legitimate agent-specific handling

Not everything about agents is uniform, and pretending otherwise produces its own bugs. These are the
differences the model acknowledges — each is **data on the actor**, never a branch on its kind.

| Legitimate | How it is expressed |
|---|---|
| An agent has an accountable owner | `agent_actor.owner_actor_id` — a column, not a code path |
| An agent's grants expire sooner by policy | `capability_grant.expires_at`, set by whoever grants |
| An agent retries and needs idempotency | `idempotency_key` on mutating operations — available to every caller |
| An agent needs machine-readable errors | Structured error bodies — which humans benefit from too |
| An agent should not receive email | `actor.notification_channel`, configurable for anyone |
| An agent is rate-limited harder | Rate-limit policy attached to the credential, not to the kind |

The test for whether a difference is legitimate: **would it still make sense if a human wanted it?** If
yes, it is data. If the only reason is "because it is an agent", it is invariant #1 being violated with
extra steps.

## 3. The delegation chain

Three actors can be involved in one event, and conflating them destroys the trail.

| Field | Meaning |
|---|---|
| `actor_id` | Who performed the action. The agent. |
| `on_behalf_of_actor_id` | Who caused it. The human who asked. |
| `granted_by_actor_id` (on the grant) | Whose authority makes it permitted. |

These are often three different actors, and each answers a different question after an incident:

- *Who did it?* → the agent.
- *Why did it happen?* → the person who asked.
- *Why was it allowed?* → the person who granted the capability.

**Record `on_behalf_of` whenever an actor acts because another asked it to.** An agent working through
its own backlog has none. An agent responding to a mention or a direct instruction has one, and
omitting it turns "the agent closed the ticket because Anna asked" into "the agent closed the ticket",
which reads like autonomy that was never granted.

## 4. Attenuation, concretely

```
effective(agent) = (role_defaults(agent) + grants(agent) - denials(agent))
                 ∩ effective(grantor)          ← evaluated NOW, not at grant time
```

Implementation rules:

1. `PermissionService.effectiveCapabilities(actorId, projectId)` resolves the grantor chain **on every
   check**. Cache within a request; never across requests.
2. The chain is walked to its human root (bounded at 16 hops — see the `agent_owner_chain_is_valid`
   trigger). A break anywhere collapses the set to empty, never to "assume allowed".
3. An agent can never grant `member.grant`, and can never grant a capability it does not itself hold.
4. A suspended or retired actor has an empty effective set, and so does every agent beneath it.

**The failure this prevents:** an offboarded employee whose agents keep working. It is not
hypothetical — it is the default behaviour of every bot-token integration in existence.

## 5. Assignment

One accountable assignee, human or agent, same column, same rules.

- Assigning to an agent is not a special operation and has no separate endpoint.
- An agent may unassign itself (hand work back). That is `ticket.assign_self` on an empty target, not
  an agent-specific verb.
- **Do not add "auto-assign to an available agent".** That is scheduling, which the scope fence puts
  outside the product. Nodera records who holds work; deciding who should is the operator's.

## 6. Comments

An agent comment is stored, threaded, mentioned and notified exactly like a human comment.

- The reader is told who wrote it from `actor.kind`, **rendered as a field**. Never a name convention
  (`-bot` suffix), never a heuristic, never an inference from the text.
- Agents can be `@mentioned` and can reply. `comment_mention` rows are extracted server-side on write,
  so "was this actor notified?" depends on what happened, not on a parser version at read time.
- Do not add a visual hierarchy that de-emphasises agent comments. If agent output is noisy, that is a
  content problem to solve at the source, not a rendering problem to hide.

## 7. Failure patterns — what this looks like when it goes wrong

Each of these has appeared in a real system. They pass review easily because each is individually
reasonable.

| Pattern | Why it is a violation |
|---|---|
| `if (actor.isAgent) requireExtraConfirmation()` | Kind gating behaviour. If the operation needs confirmation, it needs it from everyone. |
| A separate `agent_comments` table or `is_system` flag | Second-class storage. Every query that forgets the union loses agent history. |
| `assigned_agent_id` beside `assignee_actor_id` | Two columns for one relationship. Every read path must now remember both. |
| Agent actions logged at `DEBUG`, human actions at `INFO` | The audit trail is no longer uniform, and agents become the hard actor to investigate. |
| Filtering agents out of "team members" by default | The team is the team. A filter is a user choice, not a default truth. |
| An "agent mode" flag on the API that relaxes validation | Two contracts. The relaxed one is the one that will be exploited. |
| Naming agents `bot-*` and matching on the prefix | A heuristic standing in for a field that already exists. It breaks the day someone is called `bot-anna`. |

## 8. Reviewing this area

Ask these, in order:

1. Does any new conditional mention `kind`? If yes, is it describing or deciding?
2. Does the change introduce a second storage location, endpoint or code path for one concept?
3. Is `on_behalf_of` recorded where an actor acted on another's instruction?
4. Is the permission check reached on **both** REST and MCP, through the same object?
5. Is the audit event written in the mutation's transaction, and does it record the denial case too?
6. Would this change still be correct if every actor in the system were an agent? If not, why not —
   and is that reason data or prejudice?

Question 6 is the sharpest one. Nodera should work in a project with no human members at all, apart
from the accountability root. Anywhere it would not, something is gating on kind.
