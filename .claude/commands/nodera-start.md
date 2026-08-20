---
description: Start a work session — read the working order and the next open ticket
---

Start a Nodera work session, following `docs/PROJECT_MANAGEMENT.md` § 2.

1. Read `tickets/INDEX.md` — the hand-written status and working order, then the generated table.
2. Identify the next work package: the working order first, otherwise the first open ticket by
   priority whose `depends_on` are all closed.
3. Read that ticket in full.
4. Read the skills it routes to — always `skills/critical-invariants.md`, plus the domain skill.
5. Report back: which package, why that one, what it requires, and anything in its
   "To decide before starting" section that needs my answer.

**Do not write any code yet.** Phase 2 is a plan, and the plan comes after I have confirmed the
package is the right one.
