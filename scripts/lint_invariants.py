#!/usr/bin/env python3
"""lint_invariants.py — executable firewall for the invariants a grep can actually see.

Prose invariants stay with the phase-4 reviewer. These are mechanical, and each exists
because it is cheap to violate accidentally and expensive to discover later:

1. **Actor kind never gates permission** (invariant #1). Three shapes, because the branch
   can be written three ways and only one of them mentions an operator:
   ``kind == ActorKind.AGENT``, ``when (actor.kind) { … }``, and ``is AgentActor`` — the
   last one being what a sealed ``Actor`` hierarchy invites. This is the rule the whole
   product rests on and the one a well-meaning contributor breaks first.
2. **No string interpolation into SQL.**
3. **No second permission path** — ``PermissionService`` is constructed in the composition
   root only; an adapter instantiating its own is how "one engine" quietly becomes two.
4. **``ActorContext`` is the first parameter of every use case.** Only ``public`` functions
   are checked: explicit API mode makes the modifier mandatory, so a private helper inside
   a use case is excluded without needing a rule of its own.
5. **No unwatched transaction.** ``JdbcUnitOfWork`` opens the transaction that
   ``:persistence``'s audit harness watches, and the harness fails the commit of a mutation
   that wrote no audit event. One constructed outside the composition root and the harness
   file itself opens an *unwatched* transaction, which turns invariant #3 back into something
   a reviewer has to notice — the thing review is worst at.

Allowed to mention actor kind or match a subtype, because their job is display or audit:
``**/audit/**``, ``**/presentation/**``, ``**/dto/**``, and the domain's own actor package
where the types are defined. A module that legitimately needs the subtype for something
that is *not* a permission decision — mapping an actor to its table, say — adds itself
here deliberately, with a reason, rather than the allowance being pre-granted to code that
does not exist yet.

Usage: python scripts/lint_invariants.py [--self-test]

``--self-test`` is not optional ceremony. A linter that has never been seen to fire is an
assertion, not a gate; this runs the sweep against fixtures that must fail and fixtures
that must pass, so the guard is proved on every run rather than once by hand.
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import force_utf8, ignored_paths, is_ignored, repo_root  # noqa: E402

SCAN_ROOTS = ("backend", "frontend/src")

KIND_ALLOWED = ("/audit/", "/presentation/", "/dto/", "/domain/actor/", "/components/")

#: Where the ActorContext-first convention binds. Use cases live here and nowhere else.
USE_CASE_MARKER = "/application/"
USE_CASE_DIR = "/usecase/"

# A comparison of actor kind: `kind == ActorKind.AGENT`, `actor.kind === 'agent'`,
# `isAgent`, `isHuman`, `is_bot`. Deliberately broad — a false positive costs one
# comment, a false negative costs the premise.
KIND_RE = re.compile(
    r"(actor\.kind|actorKind|\bkind\b)\s*(==|===|!=|!==)\s*[\"']?(ActorKind\.)?(AGENT|HUMAN|agent|human)"
    r"|\bis(Agent|Human|Bot)\b|\bis_bot\b",
)

# The same branch with no operator in it: `when (actor.kind) { AGENT -> … }`. The
# identifier has to END in kind/Kind, so `when (kindOfThing)` is not a match.
WHEN_KIND_RE = re.compile(r"\bwhen\s*\(\s*[\w.]*[kK]ind\s*\)")

# The same branch again, through the type rather than the field. `Actor` is sealed, so
# `is AgentActor` is exhaustively equivalent to comparing the kind and mentions neither
# `kind` nor an operator — invisible to both patterns above.
SUBTYPE_RE = re.compile(r"\b!?is\s+(Human|Agent)Actor\b")

SQL_INTERP_RE = re.compile(
    r"""(execute|exec|query|prepareStatement|rawQuery)\s*\(\s*(f?["'].*?\$\{|["'].*?["']\s*\+)""",
    re.IGNORECASE,
)

PERMISSION_CONSTRUCT_RE = re.compile(r"\bPermissionService\s*\(")

# `public class PermissionService(` is a declaration, not a call. Excluded by what it says
# rather than by allowlisting the file, so a genuine second construction in that same file
# is still a finding.
PERMISSION_DECLARATION_RE = re.compile(r"\b(class|interface|object)\s+PermissionService\b")

# The same shape, for the transaction boundary. `:persistence`'s harness watches every
# transaction a `JdbcUnitOfWork` opens and refuses to commit a mutation with no audit event;
# one built anywhere else opens a transaction nothing is watching.
UNIT_OF_WORK_CONSTRUCT_RE = re.compile(r"\bJdbcUnitOfWork\s*\(")
UNIT_OF_WORK_DECLARATION_RE = re.compile(r"\b(class|interface|object)\s+JdbcUnitOfWork\b")
#: The composition root, and the harness file itself — not the whole `audit` package: a new spec
#: dropped beside the harness would otherwise inherit the exemption and opt straight back out.
UNIT_OF_WORK_ALLOWED = (
    "/app/",
    "/persistence/src/test/kotlin/ai/nodera/persistence/audit/AuditCompleteness.kt",
)

# `public fun name(firstParam` / `public suspend fun name(firstParam`, across line breaks,
# capturing up to the first comma or closing paren. Explicit API mode guarantees the
# `public` is written, which is what keeps private helpers out of this check.
#
# A type parameter (`fun <T> close(`) and an extension receiver (`fun Deps.close(`) are matched
# too. Both are shapes a use case will take, and a regex that merely fails to match reports
# nothing at all — the gap would be silent rather than loud, which is the worse kind.
PUBLIC_FUN_RE = re.compile(
    r"\bpublic\s+(?:suspend\s+)?fun\s+(?:<[^>]*>\s*)?(?:\w+\.)?(\w+)\s*\(\s*([^,)]*)",
    re.S,
)

ACTOR_CONTEXT_PARAM_RE = re.compile(r"^ctx\s*:\s*ActorContext\b")

SOURCE_SUFFIXES = {".kt", ".kts", ".ts", ".tsx", ".java"}

COMMENT_PREFIXES = ("//", "*", "/*", "#")


def _use_case_problems(rel: str, text: str) -> list[str]:
    """Invariant: every use case takes ActorContext first (docs/ARCHITECTURE.md § 5)."""
    problems: list[str] = []
    lines = text.splitlines()
    for match in PUBLIC_FUN_RE.finditer(text):
        line_no = text.count("\n", 0, match.start()) + 1
        if lines[line_no - 1].strip().startswith(COMMENT_PREFIXES):
            continue
        name, first_param = match.group(1), match.group(2).strip()
        if not ACTOR_CONTEXT_PARAM_RE.match(first_param):
            problems.append(
                f"{rel}:{line_no}: use case '{name}' does not take ctx: ActorContext as its first "
                f"parameter. It is never ambient — a parameter is what makes the permission check "
                f"impossible to skip silently."
            )
    return problems


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
            text = path.read_text(encoding="utf-8", errors="replace")

            if USE_CASE_MARKER in posix and USE_CASE_DIR in posix:
                problems.extend(_use_case_problems(rel, text))

            for lineno, line in enumerate(text.splitlines(), 1):
                stripped = line.strip()
                if stripped.startswith(COMMENT_PREFIXES):
                    continue

                kind_allowed = any(a in posix for a in KIND_ALLOWED)
                if KIND_RE.search(line) and not kind_allowed:
                    problems.append(
                        f"{rel}:{lineno}: actor kind compared outside a display/audit module — "
                        f"invariant #1: kind never gates permission. Check a capability instead."
                    )
                if WHEN_KIND_RE.search(line) and not kind_allowed:
                    problems.append(
                        f"{rel}:{lineno}: `when` branches on actor kind outside a display/audit "
                        f"module — invariant #1. A branch with no operator in it is still a branch."
                    )
                if SUBTYPE_RE.search(line) and not kind_allowed:
                    problems.append(
                        f"{rel}:{lineno}: actor subtype tested outside a display/audit module — "
                        f"invariant #1. `Actor` is sealed, so `is AgentActor` is a kind comparison "
                        f"written through the type."
                    )
                if SQL_INTERP_RE.search(line):
                    problems.append(f"{rel}:{lineno}: string interpolation into SQL — use a parameterised statement")
                if (
                    PERMISSION_CONSTRUCT_RE.search(line)
                    and not PERMISSION_DECLARATION_RE.search(line)
                    and "/app/" not in posix
                    and "/test/" not in posix
                ):
                    problems.append(
                        f"{rel}:{lineno}: PermissionService constructed outside the composition root — "
                        f"invariant #2: there is exactly one permission engine"
                    )
                if (
                    UNIT_OF_WORK_CONSTRUCT_RE.search(line)
                    and not UNIT_OF_WORK_DECLARATION_RE.search(line)
                    and not any(allowed in posix for allowed in UNIT_OF_WORK_ALLOWED)
                ):
                    problems.append(
                        f"{rel}:{lineno}: JdbcUnitOfWork constructed outside the composition root and "
                        f"AuditCompleteness.kt — this transaction is not watched for audit "
                        f"completeness, so invariant #3 goes back to being a review duty"
                    )
    return problems


# ---------------------------------------------------------------------------
# Self-test — the paired negative for this gate
# ---------------------------------------------------------------------------

#: ``(path under the fixture root, file body, expected number of findings)``.
FIXTURES: tuple[tuple[str, str, int], ...] = (
    (
        "backend/application/src/main/kotlin/ai/nodera/application/Gate.kt",
        "if (actor.kind == ActorKind.AGENT) return denied()\n",
        1,
    ),
    (
        "backend/domain/src/main/kotlin/ai/nodera/domain/audit/Render.kt",
        "if (actor.kind == ActorKind.AGENT) label = \"agent\"\n",
        0,
    ),
    (
        "backend/application/src/main/kotlin/ai/nodera/application/Route.kt",
        "when (actor.kind) { ActorKind.AGENT -> deny(); ActorKind.HUMAN -> allow() }\n",
        1,
    ),
    (
        "backend/application/src/main/kotlin/ai/nodera/application/Sub.kt",
        "if (actor is AgentActor) return denied()\n",
        1,
    ),
    (
        "backend/domain/src/main/kotlin/ai/nodera/domain/actor/Actor.kt",
        "val label = if (actor is AgentActor) \"agent\" else \"human\"\n",
        0,
    ),
    (
        "backend/persistence/src/main/kotlin/ai/nodera/persistence/Tickets.kt",
        "connection.prepareStatement(\"select * from ticket where id = ${id}\")\n",
        1,
    ),
    (
        "backend/api-mcp/src/main/kotlin/ai/nodera/api/mcp/Tools.kt",
        "val permissions = PermissionService(directory, clock)\n",
        1,
    ),
    (
        "backend/app/src/main/kotlin/ai/nodera/app/Wiring.kt",
        "val permissions = PermissionService(directory, clock)\n",
        0,
    ),
    (
        "backend/application/src/main/kotlin/ai/nodera/application/permission/PermissionService.kt",
        "public class PermissionService(\n    private val directory: PermissionDirectory,\n)\n",
        0,
    ),
    (
        "backend/application/src/main/kotlin/ai/nodera/application/usecase/CloseTicket.kt",
        "public suspend fun closeTicket(\n    ticketKey: TicketKey,\n    ctx: ActorContext,\n): Result = error(\"unbuilt\")\n",
        1,
    ),
    (
        "backend/application/src/main/kotlin/ai/nodera/application/usecase/AssignTicket.kt",
        "public suspend fun <T> assignTicket(\n    ticketKey: TicketKey,\n    ctx: ActorContext,\n): T = error(\"unbuilt\")\n",
        1,
    ),
    (
        "backend/application/src/main/kotlin/ai/nodera/application/usecase/CommentOnTicket.kt",
        "public suspend fun Deps.commentOnTicket(\n    ctx: ActorContext,\n    body: String,\n): R = error(\"unbuilt\")\n",
        0,
    ),
    (
        "backend/application/src/main/kotlin/ai/nodera/application/usecase/OpenTicket.kt",
        "public suspend fun openTicket(\n    ctx: ActorContext,\n    title: String,\n): Result = error(\"unbuilt\")\n",
        0,
    ),
    (
        "backend/application/src/main/kotlin/ai/nodera/application/permission/Engine.kt",
        "public suspend fun effectiveCapabilities(actorId: ActorId): Set<Capability> = error(\"unbuilt\")\n",
        0,
    ),
    (
        "backend/domain/src/main/kotlin/ai/nodera/domain/ticket/Ticket.kt",
        "permissions.require(ctx, projectId, Capability.TICKET_CLOSE)\n",
        0,
    ),
    (
        "backend/persistence/src/test/kotlin/ai/nodera/persistence/TicketRepositoryTest.kt",
        "val unitOfWork = JdbcUnitOfWork(dataSource)\n",
        1,
    ),
    (
        "backend/persistence/src/test/kotlin/ai/nodera/persistence/audit/AuditCompleteness.kt",
        "internal fun audited(): UnitOfWork = JdbcUnitOfWork(WatchedDataSource())\n",
        0,
    ),
    (
        # Beside the harness, and still a finding: the exemption is the file, not the package.
        "backend/persistence/src/test/kotlin/ai/nodera/persistence/audit/TicketUseCaseTest.kt",
        "val unitOfWork = JdbcUnitOfWork(dataSource)\n",
        1,
    ),
    (
        "backend/app/src/main/kotlin/ai/nodera/app/Transactions.kt",
        "val unitOfWork = JdbcUnitOfWork(pool)\n",
        0,
    ),
    (
        "backend/persistence/src/main/kotlin/ai/nodera/persistence/JdbcUnitOfWork.kt",
        "public class JdbcUnitOfWork(\n    private val dataSource: DataSource,\n)\n",
        0,
    ),
)


def self_test() -> list[str]:
    """Run the sweep over fixtures that must fail and fixtures that must pass."""
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        # Resolved, because lint() calls Path.resolve().relative_to(root) and the platform temp
        # directory is a symlink on macOS (/var -> /private/var). Without this the self-test dies
        # with a ValueError there instead of passing or failing.
        fixture_root = Path(tmp).resolve()
        for rel, body, _ in FIXTURES:
            target = fixture_root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body, encoding="utf-8")

        found = lint(fixture_root)
        for rel, _, expected in FIXTURES:
            actual = sum(1 for problem in found if problem.startswith(f"{rel}:"))
            if actual != expected:
                verdict = "did not fire" if expected else "fired on clean code"
                failures.append(f"{rel}: expected {expected} finding(s), got {actual} — the sweep {verdict}.")
    return failures


def main() -> int:
    force_utf8()

    if "--self-test" in sys.argv[1:]:
        failures = self_test()
        if failures:
            print(f"FAIL - the invariant sweep is broken ({len(failures)} case(s)):", file=sys.stderr)
            for failure in failures:
                print(f"  - {failure}", file=sys.stderr)
            return 1
        print(f"OK - the invariant sweep fires on all {len(FIXTURES)} fixtures as specified.")
        return 0

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
