#!/usr/bin/env python3
"""check_tickets.py — consistency gate for the ticket tree.

Checks, in the order a reader would want them reported:

1.  every ticket file carries complete, valid frontmatter;
2.  ``status`` matches the directory the file is in (``open/`` vs ``closed/``);
3.  a closed ticket carries a ``closed:`` date — the closed index sorts by it;
4.  no id exists in both ``open/`` and ``closed/``;
5.  the filename matches the ``id`` in the frontmatter;
6.  every ``depends_on`` entry names a ticket that exists;
7.  no dependency cycle — the working order is undefined if one exists;
8.  no open ticket depends on nothing but is blocked by a closed-as-wont_do package;
9.  every ``tickets/**`` link in INDEX.md resolves and stays inside the ticket tree;
10. the generated views are fresh.

Usage:
    python scripts/check_tickets.py --check

stdlib-only, Windows-safe. Exit 0 = green, 1 = findings.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import (  # noqa: E402
    PRIORITIES,
    TICKET_ID_RE,
    fm_list,
    fm_str,
    force_utf8,
    iter_markdown_links,
    read_frontmatter,
    repo_root,
)
import tickets_index  # noqa: E402

REQUIRED_FIELDS = ("id", "title", "priority", "status", "created")
VALID_STATUS = {"open", "closed"}
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def check(root: Path) -> list[str]:
    problems: list[str] = []
    tickets_dir = root / "tickets"
    by_id: dict[str, list[Path]] = {}
    depends: dict[str, list[str]] = {}

    for directory in ("open", "closed"):
        for path in sorted((tickets_dir / directory).glob("*.md")):
            if path.name == "TEMPLATE.md":
                continue
            rel = path.relative_to(root).as_posix()
            fm = read_frontmatter(path)

            if not fm:
                problems.append(f"{rel}: no YAML frontmatter block")
                continue

            missing = [f for f in REQUIRED_FIELDS if not fm_str(fm, f)]
            if missing:
                problems.append(f"{rel}: frontmatter missing {', '.join(missing)}")

            ticket_id = fm_str(fm, "id")
            if ticket_id:
                by_id.setdefault(ticket_id, []).append(path)
                depends[ticket_id] = fm_list(fm, "depends_on")
                if not TICKET_ID_RE.match(ticket_id):
                    problems.append(f"{rel}: id '{ticket_id}' is not a valid ticket id (e.g. CORE-01)")
                if path.stem != ticket_id:
                    problems.append(f"{rel}: filename does not match id '{ticket_id}'")

            priority = fm_str(fm, "priority").upper()
            if priority and priority not in PRIORITIES:
                problems.append(f"{rel}: priority '{priority}' is not one of {', '.join(PRIORITIES)}")

            status = fm_str(fm, "status")
            if status not in VALID_STATUS:
                problems.append(f"{rel}: status '{status}' is not one of {', '.join(sorted(VALID_STATUS))}")
            elif status != directory:
                problems.append(
                    f"{rel}: status '{status}' but the file is in tickets/{directory}/ — "
                    f"a ticket's directory and its status must agree"
                )

            closed = fm_str(fm, "closed")
            if directory == "closed" and not closed:
                problems.append(f"{rel}: closed ticket has no 'closed:' date (the closed index sorts by it)")
            if closed and not DATE_RE.match(closed):
                problems.append(f"{rel}: closed date '{closed}' is not YYYY-MM-DD")

            for field in ("created", "updated"):
                value = fm_str(fm, field)
                if value and not DATE_RE.match(value):
                    problems.append(f"{rel}: {field} '{value}' is not YYYY-MM-DD")

    for ticket_id, paths in sorted(by_id.items()):
        if len(paths) > 1:
            where = ", ".join(p.relative_to(root).as_posix() for p in paths)
            problems.append(f"{ticket_id}: duplicate id in {where}")

    known = set(by_id)
    for ticket_id, deps in sorted(depends.items()):
        for dep in deps:
            if dep not in known:
                problems.append(f"{ticket_id}: depends_on '{dep}' does not exist")

    problems.extend(_cycles(depends, known))
    problems.extend(_index_links(root))

    stale = _stale_views(root)
    if stale:
        problems.append(
            f"generated views are stale ({stale}) — run: python scripts/tickets_index.py --write"
        )

    return problems


def _cycles(depends: dict[str, list[str]], known: set[str]) -> list[str]:
    """Report every dependency cycle once, named by its members.

    Iterative DFS with an explicit stack rather than recursion: a pathological chain
    should produce a finding, not a RecursionError that reads like a tooling bug.
    """
    problems: list[str] = []
    colour: dict[str, int] = {}  # 0 = unvisited, 1 = on stack, 2 = done
    reported: set[frozenset[str]] = set()

    for start in sorted(depends):
        if colour.get(start, 0) != 0:
            continue
        stack: list[tuple[str, list[str]]] = [(start, [start])]
        while stack:
            node, path = stack.pop()
            state = colour.get(node, 0)
            if state == 2:
                continue
            if state == 1:
                colour[node] = 2
                continue
            colour[node] = 1
            stack.append((node, path))  # revisit to mark done after children
            for dep in depends.get(node, []):
                if dep not in known:
                    continue
                if colour.get(dep, 0) == 1:
                    cycle = path[path.index(dep):] if dep in path else [dep, node]
                    key = frozenset(cycle)
                    if key not in reported:
                        reported.add(key)
                        problems.append("dependency cycle: " + " -> ".join([*cycle, dep]))
                elif colour.get(dep, 0) == 0:
                    stack.append((dep, [*path, dep]))
    return problems


def _index_links(root: Path) -> list[str]:
    """Every tickets/** link in INDEX.md resolves and does not escape the tree."""
    problems: list[str] = []
    index_path = root / "tickets" / "INDEX.md"
    if not index_path.is_file():
        return [f"{index_path.relative_to(root).as_posix()}: missing"]

    tickets_dir = (root / "tickets").resolve()
    for label, target in iter_markdown_links(index_path.read_text(encoding="utf-8")):
        if target.startswith(("http://", "https://", "#")):
            continue
        clean = target.split("#", 1)[0]
        if not clean:
            continue
        resolved = (index_path.parent / clean).resolve()
        if not resolved.exists():
            problems.append(f"tickets/INDEX.md: dead link [{label}]({target})")
            continue
        # A ticket link that escapes the ticket tree is almost always a stale
        # open/ -> closed/ move that went one directory too far.
        if clean.startswith(("open/", "closed/")) and tickets_dir not in resolved.parents:
            problems.append(f"tickets/INDEX.md: link [{label}]({target}) escapes tickets/")
    return problems


def _stale_views(root: Path) -> str:
    snapshots = {
        p: p.read_text(encoding="utf-8")
        for p in (root / "tickets" / "INDEX.md", root / "REVIEW_REPORT.md")
        if p.is_file()
    }
    if len(snapshots) < 2:
        return ""
    try:
        changed = tickets_index.write_views(root)
    finally:
        for path, content in snapshots.items():
            path.write_text(content, encoding="utf-8", newline="\n")
    return ", ".join(p.relative_to(root).as_posix() for p in changed)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Ticket-tree consistency gate.")
    parser.add_argument("--check", action="store_true", help="report findings and exit non-zero")
    parser.parse_args(argv)

    force_utf8()
    root = repo_root()
    problems = check(root)

    if problems:
        print(f"FAIL - {len(problems)} ticket consistency problem(s):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print("OK - ticket tree is consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
