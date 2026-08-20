#!/usr/bin/env python3
"""lint_language.py — the repository language is English.

Detects non-English **prose** by looking for function words that are common in other
languages and essentially absent from English technical writing. Function words rather
than a dictionary: they are the part of a sentence a writer cannot avoid, so a paragraph
of German prose lights up while a single borrowed noun does not.

Exceptions carry a MANDATORY reason:

* whole file  -> one line in ``scripts/language_allowlist.txt``: ``<glob>  # reason``
* single line -> a trailing ``lang-ok:`` marker on that line

The gate rejects an allowlist entry without a reason, so an exception can never be added
silently.

**Two honest limits**, stated because they are properties of the detector rather than of
the project:

1. It detects non-English **prose**, not non-English **labels**. A terse foreign word in a
   heading or a table cell slips through. A green run means "no foreign prose"; it is not
   proof of absence, and the diff still wants a reader.
2. A new file can be allowlisted in the same commit that introduces it. That is a visible,
   reviewable edit — nothing enforces it away, and a reviewer should push back on it.

Usage: python scripts/lint_language.py
"""

from __future__ import annotations

import fnmatch
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import force_utf8, ignored_paths, is_ignored, repo_root  # noqa: E402

SCAN_SUFFIXES = {".md", ".kt", ".kts", ".ts", ".tsx", ".sql", ".py", ".yml", ".yaml"}
SCAN_ROOTS = ("docs", "skills", "tickets", "backend", "frontend", "db", "scripts", ".github")
ROOT_FILES = ("README.md", "CLAUDE.md", "AGENTS.md", "CONTRIBUTING.md", "SECURITY.md",
              "CODE_OF_CONDUCT.md", "REVIEW_REPORT.md")

# Function words that are frequent in the target languages and vanishingly rare in
# English technical prose. "die"/"der"/"das" are excluded on purpose: "die" is an English
# verb and "der"/"das" appear inside identifiers.
MARKERS = {
    "de": ("und", "oder", "nicht", "wird", "werden", "eine", "einen", "einem", "dieser",  # lang-ok: the detector's own marker table
           "diese", "dieses", "durch", "damit", "sollen", "sollte", "muss", "kann",  # lang-ok: the detector's own marker table
           "wenn", "dann", "auch", "nur", "noch", "schon", "immer", "keine", "wurde"),  # lang-ok: the detector's own marker table
    "fr": ("les", "des", "une", "avec", "pour", "dans", "être", "cette", "nous", "vous"),  # lang-ok: the detector's own marker table
    "es": ("los", "las", "una", "con", "para", "esta", "este", "por", "como", "pero"),  # lang-ok: the detector's own marker table
}

MIN_HITS = 3  # a single borrowed word is not prose
WORD_RE = re.compile(r"[a-zà-ÿ]+", re.IGNORECASE)


def load_allowlist(root: Path) -> list[tuple[str, str]]:
    path = root / "scripts" / "language_allowlist.txt"
    if not path.is_file():
        return []
    entries: list[tuple[str, str]] = []
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        glob, sep, reason = line.partition("#")
        if not sep or not reason.strip():
            raise ValueError(
                f"scripts/language_allowlist.txt:{lineno}: entry '{glob.strip()}' has no reason — "
                f"the reason is mandatory so an exception can never be added silently"
            )
        entries.append((glob.strip(), reason.strip()))
    return entries


def scan_text(text: str) -> list[tuple[int, str, list[str]]]:
    findings: list[tuple[int, str, list[str]]] = []
    for lineno, line in enumerate(text.splitlines(), 1):
        if "lang-ok:" in line:
            continue
        words = {w.lower() for w in WORD_RE.findall(line)}
        for lang, markers in MARKERS.items():
            hits = sorted(words & set(markers))
            if len(hits) >= MIN_HITS:
                findings.append((lineno, lang, hits))
                break
    return findings


def lint(root: Path) -> list[str]:
    try:
        allowlist = load_allowlist(root)
    except ValueError as exc:
        return [str(exc)]

    ignored = ignored_paths(root)
    problems: list[str] = []
    candidates: list[Path] = [root / n for n in ROOT_FILES]
    for scan_root in SCAN_ROOTS:
        base = root / scan_root
        if base.is_dir():
            candidates += [p for p in base.rglob("*") if p.suffix in SCAN_SUFFIXES]

    for path in sorted(set(candidates)):
        if not path.is_file():
            continue
        rel = path.resolve().relative_to(root).as_posix()
        if is_ignored(rel, ignored) or "/build/" in rel or "/generated/" in rel:
            continue
        if any(fnmatch.fnmatch(rel, glob) for glob, _ in allowlist):
            continue
        for lineno, lang, hits in scan_text(path.read_text(encoding="utf-8", errors="replace")):
            problems.append(
                f"{rel}:{lineno}: looks like {lang} prose ({', '.join(hits)}) — "
                f"the repository language is English; allowlist it with a reason if it is product copy"
            )
    return problems


def main() -> int:
    force_utf8()
    problems = lint(repo_root())
    if problems:
        print(f"FAIL - {len(problems)} language problem(s):", file=sys.stderr)
        for p in problems[:50]:
            print(f"  - {p}", file=sys.stderr)
        if len(problems) > 50:
            print(f"  … and {len(problems) - 50} more", file=sys.stderr)
        return 1
    print("OK - repository language is English.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
