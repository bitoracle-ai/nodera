#!/usr/bin/env python3
"""lint_docs_index.py — documentation stays discoverable.

Two rules:

1. **Reachability.** Every knowledge document (`docs/*.md`, `skills/*.md`) is reachable
   from `docs/INDEX.md` by a curated link, directly or through one catalogue hop
   (`skills/README.md`, `docs/adr/README.md`). Knowledge nobody can find is knowledge no
   tool has.
2. **No dead links.** Every relative Markdown link in `docs/`, `skills/` and the root
   entry files resolves to a file that exists.

Generated inventories (`docs/docs_map.md`) are NOT accepted as a link source: a rule that
a generator can satisfy automatically is a rule that goes vacuously green.

Usage: python scripts/lint_docs_index.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import ROOT_ENTRY_FILES, force_utf8, iter_markdown_links, repo_root  # noqa: E402

CATALOGUES: tuple[str, ...] = (
    "docs/INDEX.md", "skills/README.md", "docs/adr/README.md",
    "docs/plan/README.md", "docs/prompts/README.md",
)
GENERATED: frozenset[str] = frozenset({"docs/docs_map.md"})


def _linked_targets(root: Path, source_rel: str) -> set[str]:
    path = root / source_rel
    if not path.is_file():
        return set()
    out: set[str] = set()
    for _, target in iter_markdown_links(path.read_text(encoding="utf-8")):
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        clean = target.split("#", 1)[0]
        if not clean:
            continue
        resolved = (path.parent / clean).resolve()
        try:
            out.add(resolved.relative_to(root).as_posix())
        except ValueError:
            continue
    return out


def lint(root: Path) -> list[str]:
    problems: list[str] = []

    reachable: set[str] = set()
    for catalogue in CATALOGUES:
        reachable |= _linked_targets(root, catalogue)

    corpus = sorted(
        p.relative_to(root).as_posix()
        for p in list((root / "docs").rglob("*.md")) + list((root / "skills").glob("*.md"))
    )
    for rel in corpus:
        if rel in GENERATED or rel in CATALOGUES:
            continue
        if rel.startswith("docs/plan/") or rel.startswith("docs/retro/"):
            continue  # catalogued by their own README, checked as link sources above
        if rel not in reachable:
            problems.append(f"{rel}: not reachable from docs/INDEX.md or a catalogue — add a curated link")

    sources = [*corpus, *CATALOGUES, *ROOT_ENTRY_FILES]
    for rel in sorted(set(sources)):
        path = root / rel
        if not path.is_file() or rel in GENERATED:
            continue
        for label, target in iter_markdown_links(path.read_text(encoding="utf-8")):
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            clean = target.split("#", 1)[0]
            if not clean:
                continue
            if not (path.parent / clean).exists():
                problems.append(f"{rel}: dead link [{label}]({target})")

    return problems


def main() -> int:
    force_utf8()
    problems = lint(repo_root())
    if problems:
        print(f"FAIL - {len(problems)} documentation discoverability problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("OK - documentation is discoverable and every link resolves.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
