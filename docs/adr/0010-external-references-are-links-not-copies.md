# ADR-0010 — External references are links, not copies; the integration is an agent

- **Status:** Accepted (2026-08-22)
- **Context documents:** [`../VISION.md`](../VISION.md) § 3 · [`../DOMAIN_MODEL.md`](../DOMAIN_MODEL.md) § 2.2, § 5 ·
  [`0001-actor-not-user.md`](0001-actor-not-user.md) ·
  [`0007-deployment-is-the-tenant-boundary.md`](0007-deployment-is-the-tenant-boundary.md)
- **Affects:** `db/migrations/` (a new table), `backend/domain/`, `backend/application/`,
  `docs/DOMAIN_MODEL.md`, `frontend/src/` (ticket detail view).

## Context

A ticket should show the branches, commits and pull requests that belong to it, and it should show
them **without anyone pasting a URL**. That is the entire requirement. It is smaller than "GitHub
integration" usually means, and the smallness is the point.

The domain model anticipated this before any of it existed. Invariant T3 makes a ticket key permanent
and never reused, and states the reason outright: *"External references (commits, chat, other tickets)
must not silently retarget."* The key was made immutable **for** this feature. Nothing else about it
exists — no table, no model section, no port.

**Forces:**

- **The scope fence is narrow and specific.** [`../VISION.md`](../VISION.md) § 3: Nodera *"links to
  commits, branches and pull requests by URL. It does not store, mirror or serve repositories."* The
  interesting part is that a webhook payload contains repository content — commit messages, pull
  request bodies — so the fence does not run between features. It runs **through the payload**, and
  therefore through the schema.
- **Something has to act, and every mutation names an actor.** Invariant AU3 writes exactly one audit
  event per mutation, and an event references an actor. A link that appears by itself still appeared
  because something created it. Naming that something is unavoidable, not optional polish.
- **Nodera is not the source of truth for any of it.** GitHub is. A merged pull request whose webhook
  was dropped leaves Nodera holding a stale answer, and webhook delivery is at-least-once at best.
- **A second forge is plausible.** GitLab and Gitea have the same shape and different detection rules.

## Decision

**1 — A forge integration is an `agent_actor`. No new actor kind, no flag, no system principal.**

`agent_actor` already fits without being bent: `owner_actor_id` is the human who installed it,
`runtime_hint` is `github-app` — free text that invariant A5 forbids any code path from branching on —
and `contact_url` points at the installation. Everything else follows for free:

- Accountability terminates at a human, via the chain invariant A4 already enforces in the database.
- **Attenuation applies unchanged.** The integration can create a link only where its owner may write,
  re-checked at use time. When the installer loses access to a project, the integration stops linking
  there in the same instant — with no integration-specific revocation path to remember.
- The audit trail reads honestly: `actor_kind = agent`, the integration's name, the owner behind it.

A `system` or `integration` kind was the obvious alternative and is refused, because the first code
path that asks *"is this an integration?"* is invariant #1's failure mode wearing a different hat.

**2 — One table, `ticket_external_ref`, holding a reference and not a copy.**

Stored: the ticket, the provider, the reference type (`branch` | `commit` | `pull_request`), the URL,
a **truncated display label**, a coarse state, and `last_seen_at`. Not stored: diffs, file contents,
full commit bodies, pull request descriptions, review comments, check logs.

The line between decision 2's two lists is the fence made mechanical: **enough to render a
recognisable link, never enough to read the work without going to the forge.** A column that does not
exist cannot be filled by a well-meaning later commit, which is why this belongs in the schema rather
than in a guideline.

**3 — Detection is by ticket key**, in branch names, commit messages and pull request titles and
bodies, using the `project.key/ticket.key` form that invariant T3 already guarantees is unique and
permanent. Reading a commit message to find a key is not storing it.

**4 — Inbound is a signed webhook, and the payload is never an identity.** The signature is verified
before anything is parsed. The acting actor is the installation's agent actor — never a GitHub login
found in the body. Mapping forge accounts to Nodera actors is a separate and much larger decision
(what happens when no actor matches?), and linking does not need it.

**5 — The stored state is a cache with a visible age, not a claim.** `last_seen_at` is rendered
beside the reference. Nodera never asserts that a pull request *is* open; it shows what it last saw
and when. A reconciliation pass may refresh references later; until one exists, the timestamp is what
keeps the display honest.

## Consequences

- ✅ **Zero new concepts in the actor model.** No migration to `actor`, no third subtype, no branch on
  what kind of thing is acting. The most invasive-looking part of this feature costs nothing.
- ✅ **Revocation works without being implemented.** Attenuation already does it, which is the payoff
  for having put the rule in `PermissionService` rather than in each caller.
- ✅ **The fence is enforced by absent columns** rather than by review vigilance.
- ✅ Ticket keys were already permanent for exactly this reason, so nothing had to change to make
  references stable.
- ⚠️ **Link state goes stale.** Webhook delivery is lossy, and a dropped `pull_request.merged` leaves
  a reference reading "open" indefinitely. Mitigated by decision 5 — showing the age rather than
  pretending to freshness — not by claiming reliability the transport does not have.
- ⚠️ **Key detection has false positives.** A commit message containing something shaped like a key
  creates a link that does not belong. Accepted: a wrong link is visible and removable, a missing link
  is invisible, so the polarity favours detecting. Link creation and removal are both audited.
- ⚠️ **A second forge shares the table and not the detection.** `provider` is a column, but branch
  naming and webhook shapes differ per forge, so the adapter multiplies even though the schema does
  not.
- ⚠️ **A webhook endpoint is unauthenticated in the ordinary sense** — it is reached by GitHub, not by
  a token-bearing actor — so signature verification is the only thing standing in front of it, and it
  must fail closed on a missing or malformed signature rather than falling back to accepting.

## Alternatives considered

- **Manual paste of URLs onto a ticket.** Rejected: it is exactly what "automatically" excludes, and
  it decays — the link that matters most is the pull request nobody remembered to attach.
- **Polling the forge API instead of webhooks.** Rejected as the primary path: rate limits, and a
  latency that makes the link feel absent when it matters. Retained as the shape any future
  reconciliation pass should take, since it is also the answer to the stale-state consequence.
- **A `system` or `integration` actor kind.** Rejected per decision 1. The bot-account model that
  [ADR-0001](0001-actor-not-user.md) exists to refuse is precisely a special participant kind
  introduced because the general one looked like a poor fit.
- **Mapping forge accounts to Nodera actors, so a commit is attributed to its author.** Not refused —
  deferred. It is genuinely desirable and genuinely larger: it needs an answer for unmatched accounts,
  an identity-claim verification story, and a position on whether an unmatched author blocks the link.
  None of that is needed to show a pull request on a ticket, and folding it in here would make a small
  decision carry a large one.
- **Storing pull request titles and commit messages in full**, so a ticket reads without leaving
  Nodera. Rejected: that is the mirroring the fence names, and it arrives one column at a time. The
  truncated label of decision 2 is the deliberate stopping point.
