#!/usr/bin/env python3
"""lint_workflow_triggers.py — releasing stays a deliberate act.

Two rules:

1. ``release.yml`` is ``workflow_dispatch``-only. No ``push``, no ``pull_request``, no
   ``schedule``, no ``release`` trigger.
2. No other workflow may target a deployment environment.

This is a gate rather than a comment because a comment is not a control. Re-adding two
YAML lines would otherwise leave every other check green, and the failure mode — an
automatic release from a branch push — is discovered by its consequences.

Deliberately a line scanner rather than a YAML parse: the gate must run with a bare
interpreter in every environment, and the shapes it looks for are unambiguous at the
line level. It over-reports rather than under-reports, which is the correct polarity for
a control.

Usage: python scripts/lint_workflow_triggers.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import force_utf8, repo_root  # noqa: E402

MANUAL_ONLY = "release.yml"
FORBIDDEN_TRIGGERS = ("push", "pull_request", "pull_request_target", "schedule", "release", "repository_dispatch")
DEPLOY_ENVIRONMENTS = ("production", "release")


def _trigger_block(text: str) -> list[str]:
    """Top-level keys under ``on:``, by indentation. Returns [] if there is no ``on:``."""
    lines = text.splitlines()
    start = next((i for i, line in enumerate(lines) if re.match(r"^on:\s*$", line)), None)
    if start is None:
        inline = next((line for line in lines if re.match(r"^on:\s*\S", line)), None)
        return re.findall(r"[a-z_]+", inline.split(":", 1)[1]) if inline else []
    keys: list[str] = []
    for line in lines[start + 1 :]:
        if line.strip() and not line[0].isspace():
            break
        match = re.match(r"^  ([a-z_]+):", line)
        if match:
            keys.append(match.group(1))
    return keys


def lint(root: Path) -> list[str]:
    problems: list[str] = []
    workflows = sorted((root / ".github" / "workflows").glob("*.yml"))
    if not workflows:
        return [".github/workflows/: no workflows found"]

    seen_manual = False
    for path in workflows:
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")
        triggers = _trigger_block(text)

        if path.name == MANUAL_ONLY:
            seen_manual = True
            offending = [t for t in triggers if t in FORBIDDEN_TRIGGERS]
            if offending:
                problems.append(
                    f"{rel}: has trigger(s) {', '.join(offending)} — releasing must stay manual "
                    f"(workflow_dispatch only)"
                )
            if "workflow_dispatch" not in triggers:
                problems.append(f"{rel}: has no workflow_dispatch trigger — it could never be run")
        else:
            for lineno, line in enumerate(text.splitlines(), 1):
                match = re.match(r"\s*environment:\s*([A-Za-z0-9_-]+)", line)
                if match and match.group(1) in DEPLOY_ENVIRONMENTS:
                    problems.append(
                        f"{rel}:{lineno}: targets environment '{match.group(1)}' — only {MANUAL_ONLY} may deploy"
                    )

    if not seen_manual:
        problems.append(f".github/workflows/{MANUAL_ONLY}: missing — the gate has nothing to protect")
    return problems


def main() -> int:
    force_utf8()
    problems = lint(repo_root())
    if problems:
        print(f"FAIL - {len(problems)} workflow trigger problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("OK - releasing stays a deliberate act.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
