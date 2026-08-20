---
description: Run the independent phase-4 review on the current work package
argument-hint: "<TICKET-ID>"
---

Run the phase-4 independent review for $ARGUMENTS.

Use the `reviewer` subagent (`.claude/agents/reviewer.md`). It runs in its own context, which is
the entire point: the context that produced the code produced its blind spots too.

Give the subagent:

- The ticket ID and path.
- The diff under review (`git diff` or the files the ticket names).

When it returns:

- Report the verdict and every finding verbatim. Do not soften or summarise a BLOCKING finding.
- For BLOCKING findings: fix them, re-run the gates, and review **again**. A fix diff is where
  regressions live.
- For NON-BLOCKING: fix in the same session by default. A follow-up ticket only through the
  ladder in `docs/PROJECT_MANAGEMENT.md` § 8, naming the criterion that carried it.
- Record the result in the ticket under `## Review result`, with the date and the verdict.
  Append later rounds; never edit an earlier one away.
