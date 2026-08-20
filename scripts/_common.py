"""Shared helpers for Nodera's repository tooling.

stdlib-only and Windows-safe, deliberately: these gates run in CI, on a maintainer's
Windows machine and inside an agent's sandbox, and a dependency that has to be installed
first is a gate people learn to skip.

Consumed by every script in this directory and unit-tested by ``tests/test_tooling.py``.
"""

from __future__ import annotations

import contextlib
import re
import subprocess
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Repository layout
# ---------------------------------------------------------------------------

#: Documents that must carry ``summary``/``read_when`` frontmatter. An explicit
#: allowlist rather than "every file under docs/": dated reports and generated
#: inventories legitimately have neither, and a rule with silent exceptions is a rule
#: nobody trusts.
ALLOWLIST_DOCS: tuple[str, ...] = (
    "INDEX.md",
    "VISION.md",
    "ARCHITECTURE.md",
    "DOMAIN_MODEL.md",
    "MCP.md",
    "API_CONTRACT.md",
    "PROJECT_MANAGEMENT.md",
    "AI_COLLABORATION.md",
    "ci.md",
)

#: Directories whose ``*.md`` headings are inventoried in ``docs/docs_map.md``.
MAP_DIRS: tuple[str, ...] = ("docs", "skills")

#: Layer-1 tool adapters. Adding a tool = one new file plus one entry here.
ROOT_ENTRY_FILES: tuple[str, ...] = (
    "CLAUDE.md",
    "AGENTS.md",
    ".github/copilot-instructions.md",
)

PRIORITIES: tuple[str, ...] = ("P1", "P2", "P3", "P4")

PRIORITY_LABELS: dict[str, str] = {
    "P1": "🔴 P1 — Highest",
    "P2": "🟠 P2 — High",
    "P3": "🟡 P3 — Medium",
    "P4": "⚪ P4 — Nice-to-have",
}

TICKET_ID_RE = re.compile(r"^[A-Z]+-\d+[a-z]?$")


def repo_root() -> Path:
    """Repo root = the parent of the ``scripts`` directory holding this file."""
    return Path(__file__).resolve().parent.parent


def force_utf8() -> None:
    """Emit UTF-8 even on a legacy cp1252 Windows console.

    Ticket titles and the priority labels above contain characters a legacy codec
    cannot encode, and a gate that crashes on its own output is a gate that gets
    disabled rather than fixed.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            with contextlib.suppress(ValueError, OSError):
                reconfigure(encoding="utf-8", errors="replace")


# ---------------------------------------------------------------------------
# Frontmatter
# ---------------------------------------------------------------------------


def parse_frontmatter(text: str) -> dict[str, object]:
    """Minimal YAML-frontmatter parser covering exactly what this repository uses.

    Supports ``key: scalar``, ``key: [a, b]`` inline lists, and ``key:`` followed by
    indented ``- item`` bullets. Anything else in the block is ignored, and a file
    without a ``---`` fence at the very top returns ``{}``.

    Deliberately not PyYAML: these gates must run with a bare interpreter, and the
    subset in use here is small enough that a parser is cheaper than a dependency.
    """
    if not text.startswith("---"):
        return {}
    lines = text.splitlines()
    end = next((i for i in range(1, len(lines)) if lines[i].strip() == "---"), None)
    if end is None:
        return {}

    data: dict[str, object] = {}
    current_key: str | None = None
    for raw in lines[1:end]:
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("- ") and current_key is not None:
            bucket = data.get(current_key)
            if not isinstance(bucket, list):
                bucket = []
                data[current_key] = bucket
            bucket.append(stripped[2:].strip().strip("\"'"))
            continue
        if not raw[:1].isspace() and ":" in raw:
            key, _, value = raw.partition(":")
            current_key = key.strip()
            value = value.strip()
            if value.startswith("[") and value.endswith("]"):
                inner = value[1:-1].strip()
                data[current_key] = (
                    [v.strip().strip("\"'") for v in inner.split(",") if v.strip()] if inner else []
                )
            elif value in ("", ">", ">-", "|", "|-"):
                data.setdefault(current_key, "")
            else:
                data[current_key] = value.strip("\"'")
    return data


def read_frontmatter(path: Path) -> dict[str, object]:
    return parse_frontmatter(path.read_text(encoding="utf-8"))


def fm_str(fm: dict[str, object], key: str, default: str = "") -> str:
    value = fm.get(key, default)
    return str(value).strip() if value is not None else default


def fm_list(fm: dict[str, object], key: str) -> list[str]:
    value = fm.get(key)
    if isinstance(value, list):
        return [str(v) for v in value]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


# ---------------------------------------------------------------------------
# Markdown helpers
# ---------------------------------------------------------------------------


def extract_headings(text: str, max_level: int = 3) -> list[tuple[int, str]]:
    """``(level, title)`` for H1..max_level, skipping fenced code blocks.

    The fence check matters: this repository's documents contain SQL and Kotlin
    samples with ``#`` comments, and counting those as headings would make the
    generated inventory churn on every code edit.
    """
    out: list[tuple[int, str]] = []
    in_fence = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        hashes = len(line) - len(line.lstrip("#"))
        if 1 <= hashes <= max_level and line[hashes : hashes + 1] == " ":
            out.append((hashes, line[hashes + 1 :].strip()))
    return out


def iter_markdown_links(text: str) -> list[tuple[str, str]]:
    """``(label, target)`` for every inline Markdown link, code fences excluded."""
    out: list[tuple[str, str]] = []
    in_fence = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        out.extend(re.findall(r"\[([^\]]*)\]\(([^)]+)\)", line))
    return out


# ---------------------------------------------------------------------------
# git
# ---------------------------------------------------------------------------


def ignored_paths(root: Path) -> set[str]:
    """Repo-relative POSIX paths git ignores; directories carry a trailing ``/``.

    Asking for the *ignored* set rather than the *tracked* set is deliberate. A tracked
    include-list makes a brand-new untracked document invisible to the gates — green
    locally, red in CI. Asking the inverse question keeps unknown files in the corpus,
    which is the polarity that fails loud instead of silent.

    No checkout (an exported tarball, a test fixture) means "exclude nothing":
    over-scanning produces findings a human can see, under-scanning produces silence.
    """
    if not (root / ".git").exists():
        return set()
    try:
        proc = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--others", "--ignored",
             "--exclude-standard", "--directory"],
            capture_output=True, timeout=30, check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise RuntimeError(f"git ls-files failed in {root}: {exc}") from exc
    if proc.returncode != 0:
        raise RuntimeError(f"git ls-files failed in {root}: exit {proc.returncode}")
    return {n for n in proc.stdout.decode("utf-8", "surrogateescape").split("\0") if n}


def is_ignored(rel: str, ignored: set[str]) -> bool:
    """True if ``rel`` is git-ignored, directly or through an ignored directory.

    The trailing slash is load-bearing: git reports a wholly ignored directory as
    ``node_modules/``, and matching the bare name would also swallow a legitimate
    sibling whose name merely starts with it.
    """
    if rel in ignored:
        return True
    return any(rel.startswith(entry) for entry in ignored if entry.endswith("/"))
