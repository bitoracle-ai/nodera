#!/usr/bin/env python3
"""lint_sql.py — the migration conventions that a reader cannot reliably check.

1. **Identifiers are unquoted lowercase snake_case.** A quoted mixed-case identifier
   works perfectly until something addresses it unquoted, and then fails in a way that
   reads like a missing table. Only extension names may be quoted.
2. **Every foreign key is indexed.** Without one, a delete on the parent scans the child.
3. **An applied migration is never edited.** Every migration is recorded in
   `db/migrations/.checksums`, and a recorded file is frozen. A changed file, an unrecorded
   one, and a missing ledger are all findings — `_checksum_drift` explains why all three.

   This is not a duplicate of Flyway's own checksum validation. CI applies the migrations to
   an empty database on every run, so Flyway has nothing to compare against and an in-place
   edit passes; it surfaces later, on the machine of whoever already applied the old version.
   This ledger is the only thing that sees the drift where the edit was made.

SQL line comments are stripped before the identifier check, so a quoted word inside a
`--` comment is not reported.

Usage:
    python scripts/lint_sql.py              # check
    python scripts/lint_sql.py --accept     # record checksums when ADDING a migration
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import force_utf8, repo_root  # noqa: E402

QUOTED_RE = re.compile(r'"([^"]+)"')
ALLOWED_QUOTED = {"pgcrypto", "citext", "uuid-ossp"}
FK_RE = re.compile(r"^\s*(\w+)\s+[\w\[\]() ]*?references\s+(\w+)\s*\(", re.IGNORECASE | re.MULTILINE)
CREATE_TABLE_RE = re.compile(r"create\s+table\s+(\w+)\s*\(", re.IGNORECASE)
INDEX_RE = re.compile(r"create\s+(?:unique\s+)?index\s+\w+\s+on\s+(\w+)\s*(?:using\s+\w+\s*)?\(([^)]*)\)", re.IGNORECASE)
PK_RE = re.compile(r"^\s*(\w+)\s+\w+.*primary\s+key", re.IGNORECASE | re.MULTILINE)


def _strip_comments(sql: str) -> str:
    return re.sub(r"--[^\n]*", "", sql)


def lint(root: Path) -> list[str]:
    problems: list[str] = []
    migrations = sorted((root / "db" / "migrations").glob("*.sql"))
    if not migrations:
        return ["db/migrations/: no migration files found"]

    for path in migrations:
        rel = path.relative_to(root).as_posix()
        raw = path.read_text(encoding="utf-8")
        sql = _strip_comments(raw)

        for match in QUOTED_RE.finditer(sql):
            name = match.group(1)
            if name not in ALLOWED_QUOTED:
                line = sql[: match.start()].count("\n") + 1
                problems.append(
                    f'{rel}:{line}: quoted identifier "{name}" — DB identifiers must be '
                    f"unquoted lowercase snake_case (only extension names may be quoted)"
                )

        for name in re.findall(r"create\s+table\s+(\w+)", sql, re.IGNORECASE):
            if name != name.lower():
                problems.append(f"{rel}: table '{name}' is not lowercase")

        # Foreign keys: indexed either explicitly, or implicitly as the leading column of
        # a primary key or a unique constraint. Checking only explicit CREATE INDEX would
        # report a false positive on every composite-PK junction table.
        indexed: set[tuple[str, str]] = set()
        for table, columns in INDEX_RE.findall(sql):
            first = columns.split(",")[0].strip().split()[0].strip()
            indexed.add((table.lower(), first.lower()))
        for table in CREATE_TABLE_RE.findall(sql):
            body = _table_body(sql, table)
            for pk in re.findall(r"primary\s+key\s*\(([^)]*)\)", body, re.IGNORECASE):
                indexed.add((table.lower(), pk.split(",")[0].strip().lower()))
            for col in PK_RE.findall(body):
                indexed.add((table.lower(), col.lower()))
            for uq in re.findall(r"unique\s*\(([^)]*)\)", body, re.IGNORECASE):
                indexed.add((table.lower(), uq.split(",")[0].strip().lower()))
            for column, _target in FK_RE.findall(body):
                if (table.lower(), column.lower()) not in indexed:
                    problems.append(
                        f"{rel}: foreign key {table}.{column} has no index — "
                        f"a delete on the parent will scan this table"
                    )

    problems.extend(_checksum_drift(root, migrations))
    return problems


def _table_body(sql: str, table: str) -> str:
    """Text from `create table <table> (` to the matching close paren."""
    match = re.search(rf"create\s+table\s+{re.escape(table)}\s*\(", sql, re.IGNORECASE)
    if not match:
        return ""
    depth, start = 0, match.end() - 1
    for i in range(start, len(sql)):
        if sql[i] == "(":
            depth += 1
        elif sql[i] == ")":
            depth -= 1
            if depth == 0:
                return sql[start + 1 : i]
    return sql[start:]


def _recorded(ledger: Path) -> dict[str, str]:
    """Ledger as `{filename: sha256}`.

    Comment lines are skipped: the header carries a double space of its own and would
    otherwise parse as an entry for a file named "filename — every migration is …".
    """
    if not ledger.is_file():
        return {}
    return dict(
        line.split("  ", 1)[::-1]
        for line in ledger.read_text(encoding="utf-8").splitlines()
        if "  " in line and not line.lstrip().startswith("#")
    )


def _checksum_drift(root: Path, migrations: list[Path]) -> list[str]:
    """Three ways the forward-only guarantee breaks. All three are findings.

    A migration whose content changed is the obvious one. The other two are how this check
    goes quiet without anyone deciding that it should: a migration nobody recorded is never
    compared against anything, and a deleted ledger disables every comparison at once.
    Treating either as "nothing to check here" is what left this inert for its first five
    migrations, while the surrounding documentation described it as enforced.
    """
    ledger = root / "db" / "migrations" / ".checksums"
    if not ledger.is_file():
        return [
            "db/migrations/.checksums: missing — the forward-only check has nothing to "
            "compare against and passes on any edit. Restore it from git; --accept creates "
            "one only for a repository that has never applied a migration anywhere."
        ]
    recorded = _recorded(ledger)
    problems: list[str] = []
    for path in migrations:
        name = path.name
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if name not in recorded:
            problems.append(
                f"db/migrations/{name}: not recorded in .checksums — a migration this check "
                f"cannot protect. Run `python scripts/lint_sql.py --accept` and commit the "
                f"ledger in the same change as the migration."
            )
        elif recorded[name] != digest:
            problems.append(
                f"db/migrations/{name}: content changed after being recorded as applied — "
                f"migrations are forward-only; correct it with a NEW migration"
            )
    return problems


HEADER = (
    "# sha256  filename — every migration is listed here, and a listed file is frozen.\n"
    "# Adding a migration means running `python scripts/lint_sql.py --accept` in that commit.\n"
    "# Re-recording an existing line is how a forward-only violation gets hidden. Don't.\n"
)


def accept(root: Path) -> int:
    """Record the current checksums.

    Re-recording a line that already exists is the one genuinely dangerous use of this flag:
    afterwards the ledger is indistinguishable from one where the file was never edited. So it
    is reported rather than performed quietly, because the operator sees the terminal even
    when nobody reads the diff.
    """
    ledger = root / "db" / "migrations" / ".checksums"
    previous = _recorded(ledger)
    lines: list[str] = []
    added: list[str] = []
    rerecorded: list[str] = []
    for path in sorted((root / "db" / "migrations").glob("*.sql")):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {path.name}")
        if path.name not in previous:
            added.append(path.name)
        elif previous[path.name] != digest:
            rerecorded.append(path.name)

    ledger.write_text(HEADER + "\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"recorded {len(lines)} migration checksum(s) — {len(added)} newly listed")
    for name in rerecorded:
        print(
            f"WARNING: {name} was already recorded and its content has changed. If it has been "
            f"applied anywhere, this ledger edit hides a forward-only violation, and the "
            f"correction belongs in a NEW migration instead.",
            file=sys.stderr,
        )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Migration convention gate.")
    parser.add_argument("--accept", action="store_true", help="record checksums when adding a migration")
    args = parser.parse_args(argv)

    force_utf8()
    root = repo_root()
    if args.accept:
        return accept(root)

    problems = lint(root)
    if problems:
        print(f"FAIL - {len(problems)} SQL convention problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("OK - migrations follow the conventions.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
