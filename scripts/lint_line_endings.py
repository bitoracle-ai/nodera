#!/usr/bin/env python3
"""No tracked text file is stored with CRLF in the git index.

`.gitattributes` normalises text to LF in the repository and converts on checkout, so a
CRLF blob means the file was written past git's clean filter — which is what the GitHub
API does, and therefore what Dependabot does. The result is a file that is *modified* in
every clone the moment it is checked out. Background: ticket FIX-01.

Reads the index via ``git ls-files --eol``, not the working tree, because the working
tree is supposed to differ.

Usage: python scripts/lint_line_endings.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import force_utf8, git_output, repo_root  # noqa: E402

REJECTED_INDEX_EOL = ("crlf", "mixed")


def index_line_endings(root: Path) -> list[tuple[str, str]]:
    """``(index eol, path)`` for every tracked file."""
    raw = git_output(root, ["ls-files", "--eol", "-z"])

    out: list[tuple[str, str]] = []
    for record in raw.decode("utf-8", "surrogateescape").split("\0"):
        if not record:
            continue
        meta, _, path = record.partition("\t")
        index_field = meta.split()[0]
        out.append((index_field.removeprefix("i/"), path))
    return out


def lint(root: Path) -> list[str]:
    if not (root / ".git").exists():
        return ["no git checkout found — this gate reads the index, and there is no index to read"]

    return [
        f"{path}: stored with {eol.upper()} in the git index. .gitattributes normalises text to LF "
        f"in the repository, so this file reads as modified in every fresh clone. "
        f"Fix: git add --renormalize {path}"
        for eol, path in index_line_endings(root)
        if eol in REJECTED_INDEX_EOL
    ]


def main() -> int:
    force_utf8()
    try:
        problems = lint(repo_root())
    except RuntimeError as exc:
        print(f"FAIL - {exc}", file=sys.stderr)
        return 1
    if problems:
        print(f"FAIL - {len(problems)} line-ending problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("OK - every tracked text file is stored LF in the index.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
