## What and why

<!-- What changes, and which problem it solves. Link the ticket: closes NODERA/CORE-01 -->

**Ticket:** <!-- tickets/open/<ID>.md, or "none — trivial" with a reason -->

## Acceptance criteria

<!-- Copy them from the ticket. Tick only what is ACTUALLY true — a reviewer will check
     the code rather than the claim, and a ticket disagreeing with its diff is the single
     most common real review finding. -->

- [ ]
- [ ] `make check` green
- [ ] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings

## Gates

<!-- Which you ran, and which you did not. "Did not run X, because Y" is honest and
     useful; claiming a green gate you did not run is the most damaging thing you can
     write here. -->

| Gate | Result |
|---|---|
| `make check` | |
| Backend tests | |
| Frontend tests | |

## Invariant check

<!-- Delete a line only if the change genuinely cannot touch it. -->

- [ ] No code path branches on `actor.kind` to decide what is permitted
- [ ] Every capability is enforced identically on REST and MCP (parity test where applicable)
- [ ] Every mutation writes exactly one audit event, in its own transaction
- [ ] `project_id` comes from the authenticated context, never from a request parameter
- [ ] No applied migration was edited
- [ ] Every safety claim has a paired-negative test, run and seen to fail with the guard removed
- [ ] Any new document is linked from `docs/INDEX.md`

## Attribution

<!-- If an AI agent produced this change, say so and name the human accountable for it.
     Not a formality: it mirrors the accountability chain this product is built on, and
     the review does not soften either way. -->

- [ ] Produced by a human
- [ ] Produced with substantial AI assistance — accountable human: @

## Not verified

<!-- What you could not check, and why. Leave empty only if there genuinely is nothing. -->
