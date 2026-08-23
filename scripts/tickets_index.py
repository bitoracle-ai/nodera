#!/usr/bin/env python3
"""tickets_index.py — generate the ticket views from ticket frontmatter.

The frontmatter of ``tickets/open/<ID>.md`` and ``tickets/closed/<ID>.md`` is the single
source of truth (ADR-0003). Every table a human reads is derived from it:

* ``tickets/INDEX.md``   — open tickets, grouped by priority, between generated markers;
* ``REVIEW_REPORT.md``   — a lean index of closed tickets (ID · title · closed date).

Nothing between the markers is edited by hand. Edit the ticket, run this, commit both.

Usage
-----
    python scripts/tickets_index.py --write     # regenerate both views
    python scripts/tickets_index.py --check     # fail if a view is stale (CI)
    python scripts/tickets_index.py --json      # machine-readable export for tools

stdlib-only, Windows-safe.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import (  # noqa: E402
    PRIORITIES,
    PRIORITY_LABELS,
    fm_list,
    fm_str,
    force_utf8,
    read_frontmatter,
    repo_root,
)

OPEN_BEGIN = "<!-- BEGIN GENERATED: open tickets (regenerate: python scripts/tickets_index.py --write) -->"
OPEN_END = "<!-- END GENERATED: open tickets -->"
CLOSED_BEGIN = "<!-- BEGIN GENERATED: closed tickets (regenerate: python scripts/tickets_index.py --write) -->"
CLOSED_END = "<!-- END GENERATED: closed tickets -->"


@dataclass
class Ticket:
    id: str
    title: str
    priority: str
    status: str
    effort: str
    depends_on: list[str] = field(default_factory=list)
    note: str = ""
    created: str = ""
    updated: str = ""
    closed: str = ""
    path: Path | None = None

    @property
    def rel(self) -> str:
        """Path relative to ``tickets/``, as INDEX.md links it."""
        assert self.path is not None
        return f"{self.path.parent.name}/{self.path.name}"


def load_tickets(root: Path) -> tuple[list[Ticket], list[Ticket]]:
    """Return ``(open, closed)``, each sorted the way its view presents it."""
    open_tickets: list[Ticket] = []
    closed_tickets: list[Ticket] = []

    for directory, bucket in (("open", open_tickets), ("closed", closed_tickets)):
        for path in sorted((root / "tickets" / directory).glob("*.md")):
            if path.name == "TEMPLATE.md":
                continue
            fm = read_frontmatter(path)
            if not fm.get("id"):
                # Reported as an error by check_tickets.py; skipped here so a single
                # malformed file cannot take the generator down with it.
                continue
            bucket.append(
                Ticket(
                    id=fm_str(fm, "id"),
                    title=fm_str(fm, "title"),
                    priority=fm_str(fm, "priority", "P3").upper(),
                    status=fm_str(fm, "status", directory),
                    effort=fm_str(fm, "effort", "—"),
                    depends_on=fm_list(fm, "depends_on"),
                    note=fm_str(fm, "note"),
                    created=fm_str(fm, "created"),
                    updated=fm_str(fm, "updated"),
                    closed=fm_str(fm, "closed"),
                    path=path,
                )
            )

    open_tickets.sort(key=lambda t: (PRIORITIES.index(t.priority) if t.priority in PRIORITIES else 9, _num(t.id)))
    # Closed tickets read newest-first: the question asked of that list is almost always
    # "what happened recently", not "what happened first".
    closed_tickets.sort(key=lambda t: (t.closed, _num(t.id)), reverse=True)
    return open_tickets, closed_tickets


def _num(ticket_id: str) -> tuple[str, int]:
    """Sort key that orders CORE-2 before CORE-10 rather than lexically."""
    prefix, _, tail = ticket_id.partition("-")
    digits = "".join(c for c in tail if c.isdigit())
    return prefix, int(digits) if digits else 0


def _depends_cell(ticket: Ticket) -> str:
    parts = []
    if ticket.depends_on:
        parts.append(", ".join(ticket.depends_on))
    if ticket.note:
        parts.append(ticket.note)
    return " · ".join(parts) if parts else "—"


def render_open_tables(open_tickets: list[Ticket], closed_count: int) -> str:
    counts = {p: sum(1 for t in open_tickets if t.priority == p) for p in PRIORITIES}
    summary = " · ".join(f"{p} {counts[p]}" for p in PRIORITIES)
    lines = [
        "",
        f"_{len(open_tickets)} open ({summary}) · {closed_count} closed → "
        f"[REVIEW_REPORT.md](../REVIEW_REPORT.md)._",
        "",
    ]
    for priority in PRIORITIES:
        rows = [t for t in open_tickets if t.priority == priority]
        lines.append(f"### {PRIORITY_LABELS[priority]} ({len(rows)})")
        lines.append("")
        if not rows:
            lines += ["_none._", ""]
            continue
        lines.append("| ID | Title | Effort | Depends on / note |")
        lines.append("|---|---|---|---|")
        for t in rows:
            lines.append(f"| [{t.id}]({t.rel}) | {t.title} | {t.effort} | {_depends_cell(t)} |")
        lines.append("")
    return "\n".join(lines)


def render_closed_table(closed_tickets: list[Ticket]) -> str:
    lines = ["", f"_{len(closed_tickets)} closed work packages, newest first._", ""]
    if not closed_tickets:
        lines += ["_none yet._", ""]
        return "\n".join(lines)
    lines.append("| ID | Title | Closed |")
    lines.append("|---|---|---|")
    for t in closed_tickets:
        lines.append(f"| [{t.id}](tickets/closed/{t.id}.md) | {t.title} | {t.closed or '—'} |")
    lines.append("")
    return "\n".join(lines)


def _splice(text: str, begin: str, end: str, body: str, where: Path) -> str:
    """Replace the region between the markers, leaving everything else untouched."""
    start = text.find(begin)
    stop = text.find(end)
    if start == -1 or stop == -1 or stop < start:
        raise ValueError(f"{where}: generated markers missing or out of order")
    return text[: start + len(begin)] + "\n" + body + text[stop:]


def write_views(root: Path) -> list[Path]:
    """Regenerate both views. Returns the files whose content actually changed."""
    open_tickets, closed_tickets = load_tickets(root)
    changed: list[Path] = []

    index_path = root / "tickets" / "INDEX.md"
    original = index_path.read_text(encoding="utf-8")
    updated = _splice(
        original, OPEN_BEGIN, OPEN_END,
        render_open_tables(open_tickets, len(closed_tickets)), index_path,
    )
    if updated != original:
        index_path.write_text(updated, encoding="utf-8", newline="\n")
        changed.append(index_path)

    report_path = root / "REVIEW_REPORT.md"
    original = report_path.read_text(encoding="utf-8")
    updated = _splice(original, CLOSED_BEGIN, CLOSED_END, render_closed_table(closed_tickets), report_path)
    if updated != original:
        report_path.write_text(updated, encoding="utf-8", newline="\n")
        changed.append(report_path)

    return changed


def as_json(root: Path) -> str:
    open_tickets, closed_tickets = load_tickets(root)

    def dump(t: Ticket) -> dict[str, object]:
        return {
            "id": t.id, "title": t.title, "priority": t.priority, "status": t.status,
            "effort": t.effort, "depends_on": t.depends_on, "note": t.note,
            "created": t.created, "updated": t.updated, "closed": t.closed, "path": t.rel,
        }

    return json.dumps(
        {"open": [dump(t) for t in open_tickets], "closed": [dump(t) for t in closed_tickets]},
        indent=2, ensure_ascii=False,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate the ticket views from frontmatter.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write", action="store_true", help="regenerate the views in place")
    group.add_argument("--check", action="store_true", help="fail if a view is stale")
    group.add_argument("--json", action="store_true", help="machine-readable export")
    args = parser.parse_args(argv)

    force_utf8()
    root = repo_root()

    if args.json:
        print(as_json(root))
        return 0

    if args.check:
        # Write, then restore: comparing rendered output against the file is the only
        # honest staleness check, and doing it by writing means the generator and the
        # checker can never disagree about formatting.
        snapshots = {
            p: p.read_text(encoding="utf-8")
            for p in (root / "tickets" / "INDEX.md", root / "REVIEW_REPORT.md")
        }
        changed = write_views(root)
        for path, content in snapshots.items():
            path.write_text(content, encoding="utf-8", newline="\n")
        if changed:
            names = ", ".join(p.relative_to(root).as_posix() for p in changed)
            print(f"FAIL - generated views are stale: {names}", file=sys.stderr)
            print("       run: python scripts/tickets_index.py --write", file=sys.stderr)
            return 1
        print("OK - generated ticket views are current.")
        return 0

    changed = write_views(root)
    if not changed:
        print("OK - generated ticket views were already current.")
    for path in changed:
        print(f"regenerated {path.relative_to(root).as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
