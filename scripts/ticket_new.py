#!/usr/bin/env python3
"""ticket_new.py — scaffold a new ticket from tickets/TEMPLATE.md.

Creates ``tickets/open/<ID>.md`` with the frontmatter filled in, refuses an id that is
already used anywhere in the tree, then regenerates the ticket views so the INDEX row is
derived from the frontmatter rather than pasted by hand.

Usage:
    python scripts/ticket_new.py <ID> "<title>" [--priority P2] [--effort "~1 d"]

stdlib-only, Windows-safe.
"""

from __future__ import annotations

import argparse
import datetime
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import PRIORITIES, TICKET_ID_RE, force_utf8, repo_root  # noqa: E402
from tickets_index import write_views  # noqa: E402


def scaffold(root: Path, ticket_id: str, title: str, priority: str, effort: str, today: str) -> Path:
    """Write tickets/open/<ID>.md. Raises ValueError on a bad id/priority or a collision."""
    if not TICKET_ID_RE.match(ticket_id):
        raise ValueError(f"'{ticket_id}' is not a valid ticket id (e.g. CORE-01, WEB-12a).")
    if priority not in PRIORITIES:
        # Validated before any file is written: the generator groups by priority, so a bad
        # value would otherwise leave a scaffolded file that every rerun then collides with.
        raise ValueError(f"priority {priority!r} is not one of {', '.join(PRIORITIES)}.")
    if not title.strip():
        raise ValueError("title must not be empty.")

    target = root / "tickets" / "open" / f"{ticket_id}.md"
    closed = root / "tickets" / "closed" / f"{ticket_id}.md"
    if target.exists():
        raise ValueError(f"tickets/open/{ticket_id}.md already exists.")
    if closed.exists():
        raise ValueError(f"tickets/closed/{ticket_id}.md already exists (id already used).")

    index_path = root / "tickets" / "INDEX.md"
    if index_path.is_file() and re.search(rf"\b{re.escape(ticket_id)}\b", index_path.read_text(encoding="utf-8")):
        raise ValueError(f"'{ticket_id}' is already mentioned in tickets/INDEX.md (id already used).")

    body = (
        (root / "tickets" / "TEMPLATE.md").read_text(encoding="utf-8")
        .replace("{{ID}}", ticket_id)
        .replace("{{TITLE}}", title)
        .replace("{{PRIORITY}}", priority)
        .replace("{{EFFORT}}", effort)
        .replace("{{DATE}}", today)
    )
    target.write_text(body, encoding="utf-8", newline="\n")
    return target


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Scaffold a new ticket from the template.")
    parser.add_argument("id", help="ticket id, e.g. CORE-01 / MCP-03 / WEB-12")
    parser.add_argument("title", help="ticket title (quote it)")
    parser.add_argument("--priority", default="P3", help="P1|P2|P3|P4 (default P3)")
    parser.add_argument("--effort", default="~1 d", help='effort estimate (default "~1 d")')
    args = parser.parse_args(argv)

    force_utf8()
    root = repo_root()
    try:
        target = scaffold(root, args.id, args.title, args.priority.upper(), args.effort,
                          datetime.date.today().isoformat())
        changed = write_views(root)
    except ValueError as exc:
        print(f"FAIL - {exc}", file=sys.stderr)
        return 1

    print(f"Created {target.relative_to(root).as_posix()}")
    for path in changed:
        print(f"regenerated {path.relative_to(root).as_posix()}")
    print("\nNext: fill in the body, set depends_on/note: in the frontmatter,")
    print("then re-run `python scripts/tickets_index.py --write`.")
    print("Gate: `python scripts/check_tickets.py --check`.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
