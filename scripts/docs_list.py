#!/usr/bin/env python3
"""docs_list.py — every knowledge document carries summary/read_when frontmatter.

Covered: all `skills/*.md` and the explicit `ALLOWLIST_DOCS` under `docs/`. The allowlist
is explicit rather than "everything under docs/" because dated reports and generated
inventories legitimately have neither field, and a rule with silent exceptions is a rule
nobody trusts.

Usage:
    python scripts/docs_list.py            # report + fail on a missing field
    python scripts/docs_list.py --list     # print the catalogue

stdlib-only, Windows-safe.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import ALLOWLIST_DOCS, fm_list, fm_str, force_utf8, read_frontmatter, repo_root  # noqa: E402


def covered_files(root: Path) -> list[Path]:
    files = sorted((root / "skills").glob("*.md"))
    files += [root / "docs" / name for name in ALLOWLIST_DOCS]
    seen: set[str] = set()
    out: list[Path] = []
    for f in files:
        key = f.resolve().as_posix()
        if key not in seen and f.is_file():
            seen.add(key)
            out.append(f)
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check documentation frontmatter.")
    parser.add_argument("--list", action="store_true", help="print the catalogue instead of checking")
    args = parser.parse_args(argv)

    force_utf8()
    root = repo_root()
    problems: list[str] = []

    for name in ALLOWLIST_DOCS:
        if not (root / "docs" / name).is_file():
            problems.append(f"docs/{name}: listed in ALLOWLIST_DOCS but missing from the tree")

    for path in covered_files(root):
        rel = path.relative_to(root).as_posix()
        fm = read_frontmatter(path)
        if not fm_str(fm, "summary"):
            problems.append(f"{rel}: frontmatter has no 'summary'")
        if not fm_list(fm, "read_when"):
            problems.append(f"{rel}: frontmatter has no 'read_when' list")
        if args.list:
            print(f"{rel}\n    {fm_str(fm, 'summary')[:160]}")

    if problems:
        print(f"FAIL - {len(problems)} documentation frontmatter problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    if not args.list:
        print(f"OK - {len(covered_files(root))} documents carry summary + read_when.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
