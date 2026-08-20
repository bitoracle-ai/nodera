#!/usr/bin/env python3
"""lint_adapters.py — the two-layer adapter architecture stays intact (ADR-0002).

Layer 2 (`docs/`, `skills/`) is the source of truth. Layer 1 is one thin adapter per tool
that carries distillates and pointers only. This gate checks the mechanical half:

1. every file in ROOT_ENTRY_FILES exists and points at `docs/INDEX.md`;
2. root `CLAUDE.md` and `AGENTS.md` never reference each other — each must stand alone,
   because a contributor arrives through exactly one of them;
3. no unfilled `{{PLACEHOLDER}}` survived a copy;
4. no Copilot instruction file claims a repository-wide `applyTo: "**"` — that would make
   every path-specific rule apply everywhere and silently defeat the scoping;
5. scoped guide pairs (`<dir>/CLAUDE.md` and `<dir>/AGENTS.md`) are content-identical.

What it deliberately does NOT catch: semantic drift, where a rule changed in layer 2 and
the distillate was forgotten. No linter can see that. It is what the same-work-package
rule and `docs/prompts/maintain-adapters.prompt.md` are for, and pretending otherwise
would be worse than stating it.

Usage: python scripts/lint_adapters.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import ROOT_ENTRY_FILES, force_utf8, repo_root  # noqa: E402

SCOPED_PAIR_DIRS: tuple[str, ...] = ("backend", "frontend", "db/migrations")
PLACEHOLDER_RE = re.compile(r"\{\{[A-Z_]+\}\}")


def lint(root: Path) -> list[str]:
    problems: list[str] = []

    for name in ROOT_ENTRY_FILES:
        path = root / name
        if not path.is_file():
            problems.append(f"{name}: missing (listed in ROOT_ENTRY_FILES)")
            continue
        text = path.read_text(encoding="utf-8")

        if "docs/INDEX.md" not in text:
            problems.append(f"{name}: does not point at docs/INDEX.md — an adapter must route to the hub")

        for match in PLACEHOLDER_RE.findall(text):
            problems.append(f"{name}: unfilled placeholder {match}")

    claude = root / "CLAUDE.md"
    agents = root / "AGENTS.md"
    if claude.is_file() and "AGENTS.md" in claude.read_text(encoding="utf-8"):
        problems.append("CLAUDE.md: references AGENTS.md — root adapters must not reference each other")
    if agents.is_file() and "CLAUDE.md" in agents.read_text(encoding="utf-8"):
        problems.append("AGENTS.md: references CLAUDE.md — root adapters must not reference each other")

    for path in sorted((root / ".github" / "instructions").glob("*.instructions.md")):
        text = path.read_text(encoding="utf-8")
        if re.search(r'applyTo:\s*["\']?\*\*["\']?\s*$', text, re.MULTILINE):
            problems.append(f"{path.relative_to(root).as_posix()}: repo-wide applyTo \"**\" defeats path scoping")
        if not re.search(r"^applyTo:", text, re.MULTILINE):
            problems.append(f"{path.relative_to(root).as_posix()}: no applyTo frontmatter")

    for directory in SCOPED_PAIR_DIRS:
        a = root / directory / "CLAUDE.md"
        b = root / directory / "AGENTS.md"
        if a.is_file() != b.is_file():
            present, absent = (a, b) if a.is_file() else (b, a)
            problems.append(
                f"{directory}/: scoped guide {present.name} exists but {absent.name} does not — "
                f"pairs are content-identical tracked files, not symlinks"
            )
        elif a.is_file() and a.read_text(encoding="utf-8") != b.read_text(encoding="utf-8"):
            problems.append(f"{directory}/: CLAUDE.md and AGENTS.md have drifted apart")

    return problems


def main() -> int:
    force_utf8()
    problems = lint(repo_root())
    if problems:
        print(f"FAIL - {len(problems)} adapter problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("OK - two-layer adapter architecture is intact.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
