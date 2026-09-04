---
id: DOC-06
title: Entry files — throwaway test environments, and who sets direction
priority: P2
status: closed
effort: ~0.25 d
depends_on: []
created: 2026-09-04
updated: 2026-09-04
closed: 2026-09-04
---

# DOC-06 · Entry files — throwaway test environments, and who sets direction

**Priority:** P2
**Effort:** ~0.25 d
**Skills:** `critical-invariants.md` (invariant #12 — entry files change only inside a work
package) + `testing.md`

## Motivation / context

Two maintainer decisions of 2026-09-03 are in force and are written down nowhere in this
repository:

1. **A test environment is a throwaway Docker environment, and it is torn down.** Anything a check,
   test or simulation needs beyond this repository's own toolchain — a database, a service, the
   running stack — is created for that run and removed again afterwards. Never the host's own
   installs, never a developer's persistent volume or dev stack.
2. **Direction is set by the maintainers.** This repository is maintained by bitoracle.ai; its
   priorities and standards come out of the maintainers' management process, and contributions run
   through this repository's own process.

Both are rules a contributor is expected to follow, so both belong in the files a contributor and a
tool actually read. A rule in force that the repository does not state is a rule nobody outside the
decision can follow, and the second one answers a question a public repository is asked constantly
and currently does not answer anywhere: who decides what goes in.

## Current state (honest)

**On the first decision, the repository is not silent — it is half-right, and the half that is
missing is the part that has counter-evidence.**

- `skills/testing.md` names Testcontainers for anything the database enforces. It says nothing
  about what a run may create or what it must remove.
- `docs/ci.md` maps every lane to a local command and notes that the backend and database lanes
  need a running Docker daemon. It does not say the resources those lanes create are theirs alone.
- `docs/PROJECT_MANAGEMENT.md` § 9 requires `make check` green and says nothing about the
  environment the run happened in.
- `db/migrations/CLAUDE.md` and `db/migrations/AGENTS.md` tell a contributor to verify a migration
  with `make up && make migrate` — the developer's own Postgres and the volume it keeps. That is the
  counter-evidence: the scoped guide points a verification run at exactly the stack the decision
  puts off limits, while `make verify-db` does the same work on a database it creates and drops.
- **And `make verify-db` itself only goes half way, which this package did not know when it
  started.** Phase-4 review established it: the target is `verify-db: up`, so it runs inside the
  developer's Postgres — starting that container if it was stopped, leaving it running, and leaving
  the cluster-level `nodera_app` role `V4` creates. It isolates the data, not the environment. In CI
  the same lane gets its own `services: postgres` container per job. The rule is right and the
  target has not caught up with it; [CI-02](../open/CI-02.md) is the package that closes that, and
  `skills/testing.md` names the gap in place rather than claiming a guarantee the target does not
  give.
- `docs/ops/backup-restore.md` § the drill already applies the discipline in one place — it runs
  under `docker compose -p nodera-drill` precisely because an unnamed project resolves `pgdata` to
  `nodera_pgdata` and would destroy the development database. The rule generalises that; it does
  not invent it.

**On the second, the repository is silent.** `CLAUDE.md`, `AGENTS.md` and
`.github/copilot-instructions.md` open by saying what Nodera is and that the checkout stands alone
(DOC-03). None of them says who sets the direction. `CONTRIBUTING.md` explains the process for a
change and never says whose project it is. `LICENSE` names the copyright holder and nothing else.

**And one thing a closure record has already got wrong, which this package writes down rather than
leaves to be rediscovered:** on a tree whose inputs Gradle considers unchanged, `make check` serves
the backend lane from the build cache and reports the same green as an executed run. On CORE-06's
first watch of a restored guard `:domain` re-executed and `:persistence` was cache-served; the
second watch ran with the cache off, and it took a second review round to notice the ticket was
resting on the weaker of the two.

## Approach

Layer 2 first, then the distillates — `docs/INDEX.md` § Maintenance, and the same work package.

1. `skills/testing.md` — the throwaway-environment rule in full, naming the mechanics that satisfy
   it (Testcontainers and its reaper, a compose project under its own `-p` name), the one that does
   not yet (`make verify-db`, with what it actually leaves behind), and the developer's stack that
   is not a test environment at all.
2. `docs/PROJECT_MANAGEMENT.md` — § 9 gains what the closure record must say: what the run created
   and removed, and whether the tests executed. A new § 14 states who sets direction. **Appended,
   not inserted:** §§ 8, 9, 12 and 13 are cross-referenced from a dozen files, and renumbering them
   would be a much larger diff than this package.
3. `docs/ci.md` — the local lanes create and remove their own Docker resources, and a green backend
   lane on an unchanged tree is not evidence that a test ran.
4. `docs/AI_COLLABORATION.md` § 1 and § 5, `CONTRIBUTING.md` — where each restates a rule.
5. The three root adapters, the `backend/` and `db/migrations/` scoped pairs, the two
   `.github/instructions/` rule sets that govern a verification run, `README.md`, and the slash
   commands in `.claude/commands/` that prescribe a gate, a verification command or a priority —
   distillates that no gate checks, which is why every one of them needed a reader rather than a
   linter.
6. Close in the same session: views regenerated, `make check`, independent review.

## ⚠️ To decide before starting

- **Priority.** This is none of § 5's impact rows. It is filed **P2** on one ground: both decisions
  are already binding, and every package that closes before they are written closes under rules its
  own record does not state. The backend suite runs against a real Postgres in `:persistence`
  (DB-01), so any package touching it meets the first rule, and every package at all meets the
  second at closure. A reader who ranks it P3 loses the ordering and nothing else — effort is not
  part of the argument (§ 5's ladder is impact-based throughout).
- **§ 5's rule 1 says no low-priority package while a P1 is open, and SEC-01 is open.** This package
  was dispatched by the maintainers rather than pulled off the working order, which is the same
  shape as [CORE-06](CORE-06.md) — P3, closed 2026-09-04 with SEC-01 open. Round 1 of the
  review made the point that § 14 cannot say the maintainers set priorities and leave that
  contradiction standing, and rounds 3 and 5 narrowed what could honestly be said about it. § 14
  now leaves the question itself to the maintainers — whether the ladder governs only what a
  contributor picks up next, or every package — and creates exactly one new rule: a package running
  outside the order says so in its own ticket. § 5 rule 1 keeps its wording and gains a pointer to
  § 14.
- **No `docs/plan/DOC-06.md`.** Under § 10 a plan is persisted for a package ≥ ~1 day or carrying a
  structural decision. This is neither: the decisions were made elsewhere and this package writes
  them down. The reasoning that would go in a plan is the *Approach* above.
- **`.claude/settings.json` is raised, not edited.** Its allowlist pre-approves `make up` and
  `make migrate` — the commands `.claude/commands/nodera-migrate.md` now refuses for verification —
  and does not cover `make verify-db`, the one it prescribes. It is a permissions file: what an
  agent may run without being asked is the maintainers' call, not something a work package should
  quietly widen, and no reviewer's finding is authority to change it. Named here so the next
  session does not have to rediscover it.
- **The `Makefile` is not edited here.** Three of its comments carry sentences this package
  qualified everywhere else: the block comment above `verify-db` ("running it costs nothing and
  destroys nothing"), and the header comment and the `check` help string, which say `make check`
  runs what CI runs when it does not run `verify-db`. A documentation package editing the build to
  make its own sentences true is the scope creep § 8 refuses, and the target itself is changing in
  [CI-02](../open/CI-02.md), so all three move with it. A recorded decision, not an oversight.

## Acceptance criteria

- [x] `skills/testing.md` carries the throwaway-environment rule; names Testcontainers and a
      compose project under its own `-p` name as the ways to satisfy it; states `make verify-db` as
      the one named mechanic that does not meet it yet, with what it leaves running; and says the
      `make up` stack and the volume behind it are the developer's rather than a test environment.
- [x] `docs/PROJECT_MANAGEMENT.md` § 9 requires the closure record to name what the run created and
      removed, and to say whether the tests executed.
- [x] `docs/PROJECT_MANAGEMENT.md` § 14 states who sets direction, and that a contribution needs
      nothing outside this checkout.
- [x] `docs/ci.md` records that a green backend lane on an unchanged tree does not prove a test ran,
      and names the flags that make a run say so.
- [x] `CLAUDE.md`, `AGENTS.md` and `.github/copilot-instructions.md` each distil **both** rules, and
      each still stands alone — no cross-reference between the two root adapters.
- [x] The `backend/` and `db/migrations/` scoped pairs are content-identical and carry the
      distillate; `db/migrations/` names `make verify-db` as the verification command, says what it
      leaves running, and refuses a verification run against the development database itself.
- [x] Every factual claim this package writes into the tree is true of the `Makefile`, the compose
      files and the CI workflow as they stand — checked against them rather than against intent.
- [x] No copy of a corrected claim is left standing — including in this ticket. Six review rounds
      each found exactly one survivor: the scoped pair, `.claude/commands/nodera-migrate.md`,
      `docs/ci.md` § The image is verified separately, `scripts/verify_image.sh`, the six places
      saying `make check` runs what CI runs, and `README.md`. No gate covers `.claude/commands/`,
      `README.md` or a script's comments, which is why each needed a reader.
- [x] Nothing added by this package names a repository other than this one, a path outside it, or an
      organisation-internal identifier. `bitoracle.ai` is the only name used, and it is already in
      `LICENSE` and the repository URL.
- [x] `make check` green.
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings — seven rounds.

## Affected files

- `skills/testing.md` — the rule, where the real-Postgres tests are described.
- `docs/PROJECT_MANAGEMENT.md` — § 9 closure record; new § 14 on direction.
- `docs/ci.md` — what the local lanes create and remove; the build-cache honesty note.
- `docs/AI_COLLABORATION.md` — § 1 minimum contract, § 5 handoff.
- `CONTRIBUTING.md` — who sets direction; test environments beside `make check`.
- `CLAUDE.md` · `AGENTS.md` · `.github/copilot-instructions.md` — the distillates.
- `backend/CLAUDE.md` · `backend/AGENTS.md` · `db/migrations/CLAUDE.md` ·
  `db/migrations/AGENTS.md` — scoped pairs that govern test runs.
- `.github/instructions/tests.instructions.md` · `.github/instructions/migrations.instructions.md`
  — the two path-scoped rule sets that govern a verification run.
- `README.md` — one clause: `make check` does not run `make verify-db`, and the first file a
  contributor reads said it ran every gate.
- `.claude/commands/nodera-migrate.md` · `.claude/commands/nodera-check.md` ·
  `.claude/commands/nodera-ticket.md` — the three slash commands that prescribe a verification
  command, a gate report, or the priority a new ticket is filed at.
- `docs/INDEX.md` · `skills/README.md` — the hub row and the catalogue row, so § 14 and the
  environment rule are reachable from the two files a reader is sent to first.
- `.github/PULL_REQUEST_TEMPLATE.md` · `tickets/TEMPLATE.md` — the two surfaces a report is written
  on: which lanes executed, and what the run created and removed.
- `scripts/verify_image.sh` — **comments only.** It carried the same "`make check` … passes its
  tests" paragraph as `docs/ci.md`, and a "Leaves nothing behind" that its own `cleanup()` does not
  deliver. Both now say what is true; the one-character behavioural fix is [CI-02](../open/CI-02.md)'s,
  which is why this package did not make it.
- `docs/ops/deploy.md` — one clause removed, found by this package's own boundary sweep: it told a
  public reader that sibling services exist beside this repository. The argument around it is
  unchanged; nothing else in that file was touched.
- `tickets/open/CI-02.md` — the follow-up the review made necessary.
- `docs/docs_map.md` · `tickets/INDEX.md` — generated views, regenerated with each change;
  `REVIEW_REPORT.md` gains its row at closure and not before.

## Verification

**`make PY=py check`, 2026-09-04 — exit 0, "All gates green."** Lane by lane, with what each one
actually did:

| Lane | Result |
|---|---|
| Repository checks | Green, all executed: executable bits, LF index, 22 documents with frontmatter, `docs_map` current, ticket tree consistent, adapters intact, docs discoverable, language English, release triggers, the invariant sweep on all 20 fixtures, no mechanical violations, no TODO/FIXME. |
| Database (`check-db`) | Green: `lint_sql.py --self-test` fires on all 5 fixtures, `lint_sql.py` clean. That target needs no database, and `make verify-db` was not run — this diff touches no migration. |
| Backend | Green, and **not executed**: `BUILD SUCCESSFUL in 7s`, `74 actionable tasks: 1 executed, 73 up-to-date`, with `:domain:test`, `:application:test`, `:persistence:test`, `:api-rest:test` and `:app:test` all `UP-TO-DATE`. **No backend test executed on this run**, and no Testcontainers container was started. That is correct for a tree with no source change, and saying it rather than reporting "backend tests pass" is the § 9 obligation this package adds, applied to itself. |
| Frontend | Green and executed: `eslint.selftest.mjs` proved F1 fires, the generated client showed no diff, 15 tests in 3 files passed, coverage 86.66 % of statements, `vite build` succeeded. |

The checks that actually bear on this diff are `py scripts/lint_adapters.py` (root adapters still
stand alone, scoped pairs still identical), `py scripts/lint_docs_index.py`,
`py scripts/generate_docs_map.py --check` and `py scripts/check_tickets.py --check` — all green,
run individually as well as inside `make check`.

**The name sweep**, over every tracked file plus the two tickets this package adds,
case-insensitively, for the identifiers DOC-03 removed from the entry files and for the words
describing a layout above this repository. It is a search for **names and paths**; the softer class
— a line that implies other repositories exist without naming one — is what the paragraph below
covers, and the sweep does not find it. On the pattern it does search, the set of hits is **reduced
by one**, the `docs/ops/deploy.md` clause this package removed, and otherwise unchanged: integrity
hashes in `frontend/yarn.lock`,
the binary `backend/gradle/wrapper/gradle-wrapper.jar`, one substring inside an ordinary technical
term in `docs/adr/0008`, and three lines of DOC-03's own record of what it removed. **Nothing this
package wrote adds one** — the only name it uses is `bitoracle.ai`, which `LICENSE` and the
repository's own URL already carry. The pattern is deliberately not reproduced here: writing the
terms into a file to prove they are absent from the tree is the mistake DOC-05 exists to record.

**Three lines in two closed tickets are knowingly left, and they are not this package's to
decide.** `tickets/closed/CI-01.md` lines 72 and 126, and `tickets/closed/DOC-02.md` line 65, each
imply in passing that the maintainer keeps other repositories; the third also gives a count. No name
and no path in any of them. They are weaker than what DOC-03 removed, they are older than the rule
now in § 13, and none trips that rule's trigger — how another system is hosted, scheduled, backed up
or reached. Redacting a closed ticket is a judgement about this project's own record, which
[DOC-04](DOC-04.md) established belongs to the maintainers. Cited by line rather than
quoted, because § 13's restraint binds a package that reports a disclosure as much as one that
removes it. Raised here rather than done here, and rather than filed as a second ticket against one
closure (§ 8's net rule).

**Docker:** this package created no test environment and needed none — no code change to test, and
the lanes it depends on need no service. Two unrelated containers were already running on the
machine; they were not touched.

## Review result

**Seven independent rounds, each in a fresh sub-agent that had not seen the previous one's
findings.** Every round is recorded, including the two that contradicted an earlier round's fix.

| Round | Verdict | Findings | The one that mattered |
|---|---|---|---|
| 1 | CHANGES REQUIRED | 2 BLOCKING · 7 NON-BLOCKING | `skills/testing.md` named `make verify-db` as a mechanic satisfying the throwaway rule. It is not one: `verify-db: up` runs inside the developer's Postgres, leaves the container up and leaves the cluster-level `nodera_app` role. The false claim had been propagated to nine files. |
| 2 | CHANGES REQUIRED | 1 BLOCKING · 7 NON-BLOCKING | `.claude/commands/nodera-migrate.md` still prescribed `make up && make migrate` — the command the scoped pair had just been changed to refuse. No gate covers `.claude/commands/`. |
| 3 | CHANGES REQUIRED | 1 BLOCKING · 6 NON-BLOCKING | `docs/ci.md` § The image is verified separately still said `make check` "passes its tests", 86 lines below the new section saying it proves no such thing. |
| 4 | CHANGES REQUIRED | 1 BLOCKING · 6 NON-BLOCKING | `scripts/verify_image.sh` carried the same paragraph a fourth time, plus a "Leaves nothing behind" its own `cleanup()` does not deliver. |
| 5 | CHANGES REQUIRED | 1 BLOCKING · 7 NON-BLOCKING | Round 4's own fix created the fifth: correcting "`make check` runs everything CI runs" in `docs/ci.md` left six other copies saying it. |
| 6 | CHANGES REQUIRED | 2 BLOCKING · 5 NON-BLOCKING | `README.md`, the first file anyone reads, said `make check` "runs every gate that does work" — and this ticket described a version of § 14 that three rounds of narrowing had already replaced. |
| 7 | **APPROVED** | 0 BLOCKING · 4 NON-BLOCKING | The five-class sweep found no survivor. All four polish findings fixed in session. |

**What this package is actually a demonstration of.** Six rounds, six survivors, one per round —
and every one of them in a file no linter reads. `scripts/lint_adapters.py` checks the three root
entry files, `.github/instructions/` and the three scoped pairs; it says so itself, and
[`../../docs/ci.md`](../../docs/ci.md) § What CI does not check has always said semantic drift
between layer 2 and its distillates is not machine-checkable. This package is what that costs when
the rule being distilled has a factual claim in it: the claim was wrong, and correcting it in layer
2 left copies in `.claude/commands/`, a shell script's header, `README.md` and this ticket. **The
count is the finding.** A rule stated once in `docs/` reaches nine files by hand, and the hand is
what fails.

**The reviewers verified rather than read.** Rounds 1 to 7 each checked the new sentences against
`Makefile:124-131`, `docker-compose.yml`, `compose.prod.yml`, `db/migrations/V4__audit_and_rls.sql`,
`.github/workflows/ci.yml` and [`CORE-06.md`](CORE-06.md) rather than against the prose — which is
how round 1 found that `verify-db` does not do what this package first said it did, and round 6
found that `-p` alone cannot give it an environment of its own because `docker-compose.yml` pins
`container_name`. Round 7 ran the whole `check-repo` and `check-db` lanes independently, with the
fixture counts, and confirmed the two lanes it could not run are honestly reported here as
unexecuted rather than as green.

**Findings not acted on, with the reason** (`docs/PROJECT_MANAGEMENT.md` § 8 prefers a recorded
decision to a ticket nobody will run):

- Three `Makefile` comment lines and one character of `scripts/verify_image.sh`'s `cleanup()` go to
  [CI-02](../open/CI-02.md) with the target they describe. A documentation package editing the
  build to make its own prose true is scope creep; and the `cleanup()` fix cannot be proved without
  a Docker run, which is CI-02's fourth criterion.
- Three lines in two closed tickets that imply other repositories exist are raised, not redacted.
  None trips § 13's trigger, and [DOC-04](DOC-04.md) put judgements about this project's own record
  with the maintainers.
- `.claude/settings.json` is raised, not edited. What an agent may run unattended is a permissions
  decision; its failure mode is a prompt, which fails safe.

**What the reviewers could not verify:** the backend and frontend lanes of `make check` (Gradle and
yarn, not run by a read-only reviewer), and every Docker runtime behaviour — Testcontainers' reaper,
what `verify-db` leaves running, the anonymous volume `verify_image.sh` keeps. All of those are read
from the recipes and the migration, and each reviewer said so rather than asserting them.
