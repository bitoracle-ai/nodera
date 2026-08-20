---
description: Register a new work package properly
argument-hint: "<description of the work>"
---

Register a new work package for: $ARGUMENTS

Follow `docs/PROJECT_MANAGEMENT.md` § 7 exactly:

1. **Determine the priority** from the table in § 5. State which criterion carried it.
2. **Pick the ID** — the next free number in the right prefix range (§ 6).
3. Scaffold it:
   `python scripts/ticket_new.py <ID> "<title>" --priority <P> --effort "<estimate>"`
4. **Fill the body**: motivation, an *honest* current state (what is actually there, not what
   was intended), approach, acceptance criteria that a reader can check without asking me,
   affected files, verification.
5. Set `depends_on` and a one-line `note:` where a blocker matters, then
   `python scripts/tickets_index.py --write`.
6. `python scripts/check_tickets.py --check` must be green.

If this is a **structural decision**, it belongs in `docs/adr/` as an ADR, not in a ticket body.
Say so instead of filing.

Before filing at all, apply § 8: is this fix-now-or-drop? A session creates at most as many
tickets as it closes.
