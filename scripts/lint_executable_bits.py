#!/usr/bin/env python3
"""Every tracked file with a ``#!`` shebang must be mode 100755 in the git index, and every
100755 file must carry a shebang. Paths in REQUIRED_EXECUTABLE must be 100755 regardless, so
the rule cannot be satisfied by deleting a shebang.

Reads the index, not the filesystem: on a ``core.filemode=false`` checkout the working tree's
permission bits are not what git records. Background: ticket CI-01.

Usage: python scripts/lint_executable_bits.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import force_utf8, git_output, repo_root  # noqa: E402

EXEC_MODE = "100755"
NON_EXEC_MODE = "100644"

SHEBANG = b"#!"

#: Invoked as ``./name``, so the bit is a precondition rather than a convention.
REQUIRED_EXECUTABLE: tuple[str, ...] = ("backend/gradlew",)


def index_entries(root: Path) -> list[tuple[str, str, str]]:
    """``(mode, object id, path)`` for every entry in the git index."""
    out = git_output(root, ["ls-files", "-s", "-z"])
    entries: list[tuple[str, str, str]] = []
    for record in out.decode("utf-8", "surrogateescape").split("\0"):
        if not record:
            continue
        meta, _, path = record.partition("\t")
        mode, oid, _stage = meta.split()
        entries.append((mode, oid, path))
    return entries


def blob_heads(root: Path, oids: list[str], length: int = len(SHEBANG)) -> dict[str, bytes]:
    """First ``length`` bytes of each blob, read from the index rather than the working tree."""
    unique = sorted(set(oids))
    if not unique:
        return {}
    stream = git_output(root, ["cat-file", "--batch"], stdin=("\n".join(unique) + "\n").encode("ascii"))

    heads: dict[str, bytes] = {}
    pos = 0
    for oid in unique:
        # Record: "<oid> <type> <size>" line, then <size> bytes, then LF; a short header
        # means git could not read the object.
        header_end = stream.index(b"\n", pos)
        header = stream[pos:header_end].split()
        if len(header) < 3:
            state = header[1].decode("ascii", "replace") if len(header) > 1 else "unparseable"
            raise RuntimeError(f"git cat-file could not read object {oid} ({state})")
        size = int(header[2])
        body = header_end + 1
        heads[oid] = stream[body : body + length]
        pos = body + size + 1
    return heads


def lint(root: Path) -> list[str]:
    if not (root / ".git").exists():
        return ["no git checkout found — this gate reads the index, and there is no index to read"]

    entries = index_entries(root)
    regular = [(mode, oid, path) for mode, oid, path in entries if mode in (EXEC_MODE, NON_EXEC_MODE)]
    heads = blob_heads(root, [oid for _mode, oid, _path in regular])

    problems: list[str] = []
    flagged: set[str] = set()
    modes = {path: mode for mode, _oid, path in entries}

    # Symlinks (120000) and submodules (160000) carry no permission bit.
    for mode, oid, path in regular:
        if heads.get(oid, b"").startswith(SHEBANG) and mode != EXEC_MODE:
            problems.append(
                f"{path}: recorded {mode} but starts with a shebang — it is checked out non-executable "
                f"and running it directly fails with exit 126. Fix: git update-index --chmod=+x {path}"
            )
            flagged.add(path)
        elif not heads.get(oid, b"").startswith(SHEBANG) and mode == EXEC_MODE:
            problems.append(
                f"{path}: recorded {mode} but has no shebang — an executable bit on a file nothing runs "
                f"directly makes the bit meaningless as a signal. Fix: git update-index --chmod=-x {path}"
            )
            flagged.add(path)

    for path in REQUIRED_EXECUTABLE:
        mode = modes.get(path)
        if mode is None:
            problems.append(
                f"{path}: named in REQUIRED_EXECUTABLE but absent from the git index — either the file "
                f"moved and this list did not follow, or the list is stale"
            )
        elif mode != EXEC_MODE and path not in flagged:
            problems.append(
                f"{path}: recorded {mode} and the repository invokes it as ./{Path(path).name} — "
                f"that command cannot start. Fix: git update-index --chmod=+x {path}"
            )

    return problems


def main() -> int:
    force_utf8()
    try:
        problems = lint(repo_root())
    except RuntimeError as exc:
        print(f"FAIL - {exc}", file=sys.stderr)
        return 1
    if problems:
        print(f"FAIL - {len(problems)} executable-bit problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("OK - every shebang carries an executable bit, and every executable bit a shebang.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
