# Plans

A work package **≥ ~1 day** or carrying a **structural decision** persists its phase-2 plan here as
`<TICKET-ID>.md`. Trivial packages keep the plan in the conversation — no ceremony.

## Why they persist

After implementation the plan is **stamped `implemented`, never deleted.** The code shows what was
built; the plan shows what was considered and set aside, which is the part a later contributor
cannot reconstruct and the part that stops the same rejected idea being re-proposed every six months.

## Status header

Every plan starts with one of:

| Status | Meaning |
|---|---|
| `draft` | Being written. Not yet agreed. |
| `active` | Agreed, implementation in progress. |
| `implemented` | Done. Kept for the reasoning. |
| `superseded` | Replaced — links to what replaced it. |

## Shape

Files to change and why · acceptance criteria · test plan · deliberate non-goals · open questions
with a recommendation for each.

A plan that lists only files is a checklist. The justification is the part worth keeping.

## Current plans

| Plan | Status |
|---|---|
| [`OPS-01.md`](OPS-01.md) — build chain and release package | `implemented` |
| [`CORE-01.md`](CORE-01.md) — actor model and permission engine | `implemented` |
| [`DB-01.md`](DB-01.md) — the schema proved by negative tests | `implemented` |
