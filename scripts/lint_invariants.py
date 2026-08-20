#!/usr/bin/env python3
"""lint_invariants.py — executable firewall for the invariants a grep can actually see.

Prose invariants stay with the phase-4 reviewer. These three are mechanical, and each
exists because it is cheap to violate accidentally and expensive to discover later:

1. **Actor kind never gates permission** (invariant #1). Any comparison of an actor's
   kind outside the modules allowed to *describe* it is a finding. This is the rule the
   whole product rests on and the one a well-meaning contributor breaks first.
2. **No string interpolation into SQL.**
3. **No second permission path** — `PermissionService` is constructed in the composition
   root only; an adapter instantiating its own is how "one engine" quietly becomes two.

Allowed to mention actor kind, because their job is display or audit:
``**/audit/**``, ``**/presentation/**``, ``**/dto/**``, and the domain's own actor package
where the type is defined.

Usage: python scripts/lint_invariants.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import force_utf8, ignored_paths, is_ignored, repo_root  # noqa: E402

SCAN_ROOTS = ("backend", "frontend/src")

KIND_ALLOWED = ("/audit/", "/presentation/", "/dto/", "/domain/actor/", "/components/")

# A comparison of actor kind: `kind == ActorKind.AGENT`, `actor.kind === 'agent'`,
# `isAgent`, `isHuman`, `is_bot`. Deliberately broad — a false positive costs one
# comment, a false negative costs the premise.
KIND_RE = re.compile(
    r"(actor\.kind|actorKind|\bkind\b)\s*(==|===|!=|!==)\s*[\"']?(ActorKind\.)?(AGENT|HUMAN|agent|human)"
    r"|\bis(Agent|Human|Bot)\b|\bis_bot\b",
)

SQL_INTERP_RE = re.compile(
    r"""(execute|exec|query|prepareStatement|rawQuery)\s*\(\s*(f?["'].*?\$\{|["'].*?["']\s*\+)""",
    re.IGNORECASE,
)

PERMISSION_CONSTRUCT_RE = re.compile(r"\bPermissionService\s*\(")

SOURCE_SUFFIXES = {".kt", ".kts", ".ts", ".tsx", ".java"}


def lint(root: Path) -> list[str]:
    problems: list[str] = []
    ignored = ignored_paths(root)

    for scan_root in SCAN_ROOTS:
        base = root / scan_root
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            if path.suffix not in SOURCE_SUFFIXES or not path.is_file():
                continue
            rel = path.resolve().relative_to(root).as_posix()
            if is_ignored(rel, ignored) or "/build/" in rel or "/generated/" in rel:
                continue
            posix = "/" + rel

            for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                stripped = line.strip()
                if stripped.startswith(("//", "*", "/*", "#")):
                    continue

                if KIND_RE.search(line) and not any(a in posix for a in KIND_ALLOWED):
                    problems.append(
                        f"{rel}:{lineno}: actor kind compared outside a display/audit module — "
                        f"invariant #1: kind never gates permission. Check a capability instead."
                    )
                if SQL_INTERP_RE.search(line):
                    problems.append(f"{rel}:{lineno}: string interpolation into SQL — use a parameterised statement")
                if PERMISSION_CONSTRUCT_RE.search(line) and "/app/" not in posix and "/test/" not in posix:
                    problems.append(
                        f"{rel}:{lineno}: PermissionService constructed outside the composition root — "
                        f"invariant #2: there is exactly one permission engine"
                    )
    return problems


def main() -> int:
    force_utf8()
    problems = lint(repo_root())
    if problems:
        print(f"FAIL - {len(problems)} invariant violation(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("OK - no mechanical invariant violations.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
