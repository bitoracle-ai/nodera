---
summary: The phase-4 review rubric — what an independent reviewer checks, how findings are classified BLOCKING or NON-BLOCKING, the evidence standard a finding must meet, and the scope governor that keeps a review from becoming a rewrite.
read_when:
  - Before every phase-4 review, as the reviewer.
  - When deciding whether a finding is BLOCKING.
  - When a review is about to produce more tickets than the work package closed.
---

# Code review — Nodera

The review is the load-bearing quality mechanism, and it is **never performed by the author**. A
separate session, a different tool, a different model, or a person. Continuing the implementation
conversation is not a review: the context that produced the code produced its blind spots too.

An agent may review a human's work and a human may review an agent's. What is refused is reviewing
one's own — the same rule Nodera enforces in its own domain model (invariant R1).

---

## 1. What a review returns

A verdict and a list of findings.

| Verdict | Condition |
|---|---|
| `APPROVED` | Zero BLOCKING findings. NON-BLOCKING findings may exist and are listed. |
| `CHANGES REQUIRED` | One or more BLOCKING findings. |

There is no third verdict, no "approved with comments" and no conditional approval. A finding that
must be fixed before merge is BLOCKING; everything else is NON-BLOCKING and gets fixed in the same
session by default.

## 2. Classification

**BLOCKING** — the change must not merge as it stands:

- A [critical invariant](critical-invariants.md) is violated.
- A correctness defect with a concrete failure path: inputs or state that produce a wrong result,
  a crash, or data loss.
- A security defect: a permission not enforced, a secret exposed, an injection path, a scoping bypass.
- A safety claim without its paired-negative test.
- An acceptance criterion marked met that is not met.
- A migration that is not forward-only, or edits an applied one.
- A gate reported green that was not run.

**NON-BLOCKING** — real, worth fixing, does not block:

- Duplication that will drift.
- A name that misleads.
- A missing test for a path that is not safety-relevant.
- A doc that no longer matches the code.
- An efficiency problem with no correctness consequence at current scale.

**Not a finding at all** — do not write these down:

- Style the formatter already decides.
- A preference with no argument behind it ("I would have used a `when` here").
- A restatement of what the diff obviously does.
- A hypothetical with no path to it in this codebase.

## 3. The evidence standard

**A finding names a location and a consequence.** Both, or it is not yet a finding.

> `backend/application/src/.../TicketService.kt:118` — the closure gate reads
> `findings.filter { it.severity == BLOCKING }` from the *latest* review only. A blocking finding
> raised in round 1 and not carried into round 2 is therefore invisible to the gate, and the ticket
> closes with it unresolved. Reproduces with two reviews where round 2 raises no findings.

Compare with what is not a finding:

> The closure gate logic looks fragile.

**Verify before you write it.** Read the surrounding code, follow the call path, check whether a test
already covers it. A confident review full of findings that turn out to be wrong costs more than it
saves — it trains the next author to discount reviews, and the discount will one day apply to a real
one.

**Where a claim cannot be verified from the diff, say so explicitly:** "not verified — I could not run
Testcontainers in this environment" is honest and useful. Asserting it passes is not.

## 4. The review checklist, in order

Run in this order — the early items are cheap and catch the expensive problems.

1. **Invariants.** Walk [`critical-invariants.md`](critical-invariants.md) against the diff. Any
   conditional mentioning `actor.kind`? Any second permission path? Any audit write outside the
   mutation's transaction?
2. **Acceptance criteria.** Each `[x]` in the ticket — is it *actually* true? Check the code, not the
   claim. This is where reviews most often find that the ticket and the diff disagree.
3. **Scope.** Does the diff do what the ticket says, and only that? Unticketed extra work is a finding
   even when it is good work — it was not reviewed against criteria and nobody agreed to it.
4. **Security.** Permission checked on every path including MCP; scoping from context not parameters;
   no secret in a log, error or response; input validated at the boundary.
5. **Correctness.** Edge cases, error paths, concurrency. What happens on the second, simultaneous or
   retried call?
6. **Tests.** Does a new test fail if the behaviour is reverted? A test that passes against both the
   old and the new code tests nothing. For safety claims, check the paired-negative explicitly.
7. **Gates.** Were they run? Is there evidence? Re-run the cheap ones yourself.
8. **Documentation.** Did a rule change in a doc without its distillates being pulled along
   (`python scripts/lint_adapters.py`)? Is a new document linked from `docs/INDEX.md`?

## 5. The scope governor

**A review does not become a rewrite.** Three rules keep it bounded:

1. **Review the diff, not the file.** Pre-existing problems in untouched lines are not this package's
   findings unless the change makes them dangerous.
2. **Propose the smallest correct fix.** If a finding has a three-line fix and a two-day fix, the
   three-line fix is the finding's remedy; the two-day one is a separate proposal with its own
   reasoning.
3. **A session creates at most as many tickets as it closes.** Findings that do not meet the ticket
   test in [`../docs/PROJECT_MANAGEMENT.md`](../docs/PROJECT_MANAGEMENT.md) § 8 are fixed now or
   dropped with a recorded reason. "Noted, not doing this, because …" is a legitimate outcome and is
   preferable to a ticket nobody will run.

**Do not achieve the ticket budget by reviewing less.** Every blocking finding still stops closure.
The constraint applies to what the *closing contributor* does with a finding, never to what the
reviewer is willing to say.

## 6. Re-review after fixes

When BLOCKING findings are fixed, the reviewer returns.

- Verify each fix **against the pixels, not the prose** — read the changed code, do not accept a
  summary of it.
- Check that the fix did not introduce a new problem. Fix diffs are where regressions live, because
  they are written under time pressure and reviewed with less attention than the original.
- If the fix diff is substantially larger than the original change, that is itself worth saying: it
  usually means the finding was a symptom of a design problem the fix is now papering over.

## 7. Recording the result

The review result goes in the ticket file under `## Review result`, with the date and the verdict:

```markdown
## Review result

**2026-08-20 · CHANGES REQUIRED (independent review, round 1).** 2 BLOCKING, 4 NON-BLOCKING.

| # | Finding | Fix |
|---|---|---|
| B1 | `TicketService.kt:118` — closure gate reads only the latest review's findings … | Gate now queries unresolved blocking findings across all rounds. |
| B2 | … | … |

NON-BLOCKING N1–N4 were fixed in the same session: …
```

Later rounds are appended, never edited. **A verdict that contradicts an earlier one stays visible** —
both are part of the record, and the contradiction is usually the most informative line in the file.
