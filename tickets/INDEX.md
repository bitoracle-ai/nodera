# Ticket index — Nodera

> **Entry point for every session.** Read this first, then the next open ticket by priority.
> Process rules: [`../docs/PROJECT_MANAGEMENT.md`](../docs/PROJECT_MANAGEMENT.md) · scope fence:
> [`../docs/VISION.md`](../docs/VISION.md) · invariants:
> [`../skills/critical-invariants.md`](../skills/critical-invariants.md).
>
> The tables below are **generated from ticket frontmatter**. Edit the ticket file, then run
> `python scripts/tickets_index.py --write`; never edit between the markers.
> Gate: `python scripts/check_tickets.py --check`.

## Status (hand-maintained)

**2026-09-03 — [CORE-04](closed/CORE-04.md) is closed, and the closure gate finally has something
that feeds it.** Comments with server-side mention extraction, edits that preserve authorship,
deletion as a tombstone, review rounds that are appended and never collapsed, and finding resolution
— seven use cases, three adapters, 561 backend tests, 0 failures. An unresolved blocking finding
from round 1 now refuses a closure through the domain rather than through seeded fixtures, and a
round-2 verdict contradicting round 1 leaves both readable.

**The part worth carrying is that nine of the nine blocking findings were in one function.** Five
review rounds, and every blocking one was the Markdown sanitiser calling prose *code*, so a
`<script>` reached the `comment.body` column — each round with a shape the previous round's fix had
not considered. Round 3 named the reason and it generalises: **refusing to treat a marker as an
opener is not conservative**, because the refused marker's CommonMark partner is still there for a
later marker to pair with, so declining to recognise a construct can create a mis-classification.
Round 4 then showed the same mechanism on fenced blocks, from three-line bodies. The answer was to
delete the exemption rather than repair it a fifth time: every `<` is escaped now, with nothing to
classify and so nothing to classify wrongly. The price is `&lt;` in code samples, asserted by its
own test and raised for the maintainers — the sanitising renderer `skills/secure-coding.md` assumes
is where this belongs, and it does not exist yet.

**Two findings were about a guard rather than the code it guards, which is now four packages
running.** The project-scope clause on the key-addressed reads had no paired negative at all —
every fixture project derived its ticket prefix from its own id, so no test could put one key in two
projects and the clause was deletable from four statements with the suite green. And the harness
that watched the twenty guards go red reported success twice while being wrong: once because it
could not find the build script and answered a missing results directory with "no failures", once
because its result parser filed each failure under the preceding, passing case's name.

**One thing this package was expected to carry and does not: `criterion_set`.** It is the third
input the closure gate reads, [`../docs/plan/CORE-03.md`](../docs/plan/CORE-03.md) § 7 had assigned
it here, and it is in neither CORE-04's approach nor its acceptance criteria. Acceptance criteria
are still written only by tests. [`../docs/plan/CORE-04.md`](../docs/plan/CORE-04.md) § 8 proposes a
small follow-up package; CORE-05 needs it.

**2026-09-02 — [CORE-03](closed/CORE-03.md) is closed, and a ticket now has a lifecycle it cannot be
talked out of.** The status machine is a pure transition function over nine specified edges, key
allocation locks its `ticket_sequence` row, and the closure gate returns
`UnmetClosureRequirements` — never a boolean, and never an empty one: the type refuses to be
constructed with nothing unmet, because a refusal that names nothing is the boolean it exists to
replace. Three use cases, the first mutating ones in the repository, so CORE-02's completeness
harness finally has real transactions to watch. 252 backend tests, 0 failures.

**The thing worth carrying is where the defects were: in the guards, not in the code they guard.**
Three review rounds, eighteen findings, none blocking — and the three that mattered were all a guard
weaker than its prose. The race that proves key allocation passed with `for update` deleted, because
the contender was blocking on the sequence table's unique index before it ever reached the lock; the
allocator's "refuse rather than restart the sequence" had never executed, because row-level security
refuses the insert one statement earlier; and the audit entity id added to three refusal paths was
pinned on one of them, so dropping it from the other two left the suite green. Each is now either
fixed, tested, or described as what it actually is.

**One real defect, and it was found by reading rather than by running.** Two concurrent transitions
of one ticket could lose an update: the read took no lock and the write was unconditional, so two
callers that both read `in_review` were both permitted and the later write won — landing a status
the machine never allows from the status the row actually had, with an audit row describing a
`before` that had already gone. The write is a compare-and-set now, and the loser is told to retry.

**And one thing the specification does not carry, raised rather than invented:** `open → closed` has
no path, so a ticket recognised as a duplicate the moment it is filed must be walked through
`in_progress` and `in_review` to be closed. `docs/DOMAIN_MODEL.md` § 5.1 does not draw that edge;
[`docs/plan/CORE-03.md`](../docs/plan/CORE-03.md) § 8 proposes it. It is a product decision and it
is open.

**2026-09-02 — [CORE-02](closed/CORE-02.md) is closed, and the audit trail has a writer that cannot
be forgotten.** `AuditRecorder` appends one row on the transaction the use case already opened, and
`AuditEventRepository` refuses to write when there is none — a sink that opened its own would let a
mutation and its audit row commit independently, which is the defect this package exists to prevent.
150 backend tests, 0 failures.

**The part worth carrying is the enforcement, not the writer.** "Every mutation writes exactly one
event" was going to be a review duty, and review is worst at noticing an absence. It is now a JDBC
listener in the `:persistence` harness: it reads the statements that actually executed and refuses to
commit a transaction whose mutations carry any audit-event count but one. A use case that bypasses
the recorder, hand-writes its SQL or simply forgets is caught identically, because none of that
changes what reaches the database. Opting out is mechanical too — `scripts/lint_invariants.py`
refuses a `JdbcUnitOfWork` built outside the composition root and the harness file, so no future test
can quietly open an unwatched transaction.

**Both blocking findings across five review rounds were defects in that check itself**, which is the
argument for reviewing the guard harder than the code it guards. The first version was anchored at
`^` and could not see a mutation inside a common table expression or behind a comment — the shapes
CORE-03's key allocation actually writes. The second was a `CallableStatement` interception that no
test had ever executed: deleting the branch left the suite green. Seven committed refusals now cover
one door each, and the harness was watched going red twice — once with its check neutered, once
against a throwaway un-audited `insert` of a shape no committed test uses.

**One CORE-01 type changed shape.** `RequestId` carried a non-blank `String` while
`audit_event.request_id` is `uuid not null`, so a string that was not a UUID type-checked and would
have failed on the last statement of the mutation's own transaction. It carries a `Uuid` now.
`docs/API_CONTRACT.md` states the shape that forces; what a surface does with a client-supplied
header that is not one is left to API-01.

**2026-09-01 — [FIX-02](closed/FIX-02.md) is closed, and invariant F1's proof no longer runs against
a clock.** It was a vitest test running ESLint programmatically, and it failed on machine speed
rather than on code: 7037 ms against a 5 s `testTimeout` under artificial load, 22 763 ms on a cold,
busy machine. The cost is ESLint loading the flat configuration and its plugin graph once per
process — about 1.2 s warm — and no clock vitest offers fits it. Sharing one instance saves the
10 ms of construction, and warming in `beforeAll` only moves the cost under `hookTimeout`, where a
heavier load makes vitest report both cases **skipped** rather than failed: a paired negative that
stops running without going red, which is worse than the flake. The proof now runs inside
`yarn lint` as `frontend/eslint.selftest.mjs`, in the shape `lint_invariants.py --self-test`
established, and it is still red in both directions when the rule or its `src/api/**` exemption is
removed.

**Worth carrying, because it is bigger than that ticket: the backend lane was unrunnable on the
development machine for want of a `JAVA_HOME`, not for want of a JDK.** `make check` exited 2 at
`check-backend` until `JAVA_HOME` was pointed at a JDK 21 that was already installed; with it set,
all four lanes pass and the backend suite runs 118 tests, 0 failures. No toolchain resolver is
configured, so Gradle cannot provision a JDK itself — on a machine without one the lane stops rather
than self-heals, and every backend package is unverifiable until it is fixed. `CONTRIBUTING.md` and
`README.md` already name JDK 21 as a prerequisite; nothing in the repository was wrong.

**2026-08-31 — [DB-01](closed/DB-01.md) is closed, and the schema has now been seen to refuse.**
Everything `V1`–`V5` claimed to enforce was, until this package, a claim in a file: the migrations
applied, and nothing they carried had ever been observed failing. There is now a Testcontainers
harness in `:persistence` and 55 negative tests, every one asked as `nodera_app` rather than as the
owner — the owner is a superuser and bypasses row-level security, so a suite written against it
passes with every policy deleted. 59 tests in `:persistence`, 118 across the backend, 0 failures.
Full record in the ticket; the reasoning in [`docs/plan/DB-01.md`](../docs/plan/DB-01.md).

**The ticket said "demonstrably red when the policy is dropped", and that turns out to be the one
removal that cannot show a leak.** A table with row-level security enabled and no policy denies
everything, so a dropped-policy probe reads the same zero the working guard reads — a paired negative
that stays green with the guard gone, which is the exact shape this repository's honesty rule exists
to catch. Each of the fourteen policies is instead removed two ways: row-level security off, which
puts the mechanism on trial, and the predicate replaced with `true`, which puts the policy on trial.
The deny-all behaviour is pinned by its own test rather than used as the negative.

**And proving the boundary found a hole in it.** `ticket_label` was the last two-ended association
without a same-project guard: `V4`'s policy scopes `ticket_id` and says nothing about `label_id`, and
referential integrity checks bypass row-level security by design — they must, or a foreign key could
be evaded by hiding the parent. A caller holding one project's context could attach **another
project's label** to its own ticket. Not a cross-project read: the label's columns stayed invisible
and the join returned nothing. A cross-project write, and a weak existence oracle. `ticket_dependency`
has the identical shape and has carried its trigger since `V2`, which is what makes this an omission
rather than a decision. Corrected by a **new** migration, `V6`, never by editing `V2` — and `V6`
refuses to install over pre-existing straddling rows rather than deleting them.

`scripts/lint_sql.py` gains `--self-test`, the shape CORE-01 established for `lint_invariants.py`: a
gate that has never been seen to fire is an assertion, and the identifier rule it guards is the one
with no runtime symptom — a quoted mixed-case identifier works until something addresses it unquoted.

**2026-08-25 — [CORE-01](closed/CORE-01.md) is closed, and it is the first application code in the
repository.** `:domain` holds the actor model — one participant type, two subtypes, nothing branching
on which — and `:application` holds `PermissionService`, the single engine both surfaces will call.
64 backend tests, 0 failures. Full record in the ticket; the reasoning, including what was rejected,
in [`docs/plan/CORE-01.md`](../docs/plan/CORE-01.md).

**What still does not exist:** `PermissionDirectory` is still a port with no implementation. DB-01
proved the schema; it wrote no production Kotlin and no repository. That is CORE-02's and SEC-01's,
and they are the next packages.

Two findings from that package are worth carrying, because both are the same shape as CI-01's and
OPS-01's. **A permission engine's bounds are part of its semantics:** the first implementation walked
the grantor chain under a shared work budget spent in row order, and phase-4 review proved that
*breaking* a grantor could then free budget to reach another — so removing authority granted a
capability. Rewritten as a least fixed point over the grantor closure; monotonicity is now a theorem
with a committed regression test, and there is no budget to get wrong. **And `backend/detekt.yml` and
the ktlint 1.5.0 pin had never applied to any module** — a configuration block at the top level of
the root build script configures the root project only, so six modules were linted by ktlint 1.0.1 on
detekt's defaults. Committed, documented, and inert. Fixing it reformats seven pre-existing files.

**2026-08-24 — the production surface has a runbook and a restore that was walked, and the frontend
toolchain is migrated forward off the two majors that reddened the lane.**

**[WEB-04](closed/WEB-04.md) is closed, and the Dependabot backlog is empty of content.** All four
open pull requests — [#26](https://github.com/bitoracle-ai/nodera/pull/26) zod 4,
[#27](https://github.com/bitoracle-ai/nodera/pull/27) vite 8,
[#28](https://github.com/bitoracle-ai/nodera/pull/28) react-router 8 and
[#29](https://github.com/bitoracle-ai/nodera/pull/29), the rebased replacement for the conflicting
#25 — are carried forward here in one lockfile rather than merged in four. Three of them were safe
on their own; #27 was not, and merging it would have been the kind of green that means nothing.

**#27 did not upgrade Vite, it added a second one.** A clean install of its lockfile produced five
copies across two majors: `vite` at 8.2.2 while `vitest`, `vite-node`, `@vitest/mocker` and
`@vitejs/plugin-react` each resolved 6.4.3 — because `vitest@3.2.7` carries
`vite: "^5.0.0 || ^6.0.0 || ^7.0.0-0"` as a *dependency*, not a peer. The suite and the coverage gate
would have run on Vite 6 while the production build ran Vite 8, and no lane here can tell those
apart. The `resolutions` pin OPS-01 added was left at `^6.4.3` by that PR, so it stopped covering the
direct dependency while still reading as though Vite were held at 6. WEB-04 takes **vitest 3 → 4**
alongside, moves the pin to `^8.2.2`, and the tree now resolves **exactly one Vite**.

**#28 would have stopped every contributor command.** `react-router@8.3.0` declares
`engines.node ">=22.22.0"`, `.nvmrc` said 22.20.0, and Yarn 1 *refuses* an engines mismatch rather
than warning it — so `make dev`, `make check`, `make test` and `make frontend` all die at install.
CI stayed green only because `NODE_VERSION: "22"` resolved whatever 22.x the runner carried. Both
workflows now read `node-version-file: .nvmrc`, and `.nvmrc`, `engines.node` and the
`react`/`react-dom` ranges are all at or above what react-router 8 requires. **That binds the runner
only.** Nothing forces a *contributor* onto that Node — `.nvmrc` does nothing without `nvm use`, and
there is no engine-strict npmrc, Volta or mise pin — so raising the floor is a change contributors
have to be told about, which is why `CONTRIBUTING.md` and `README.md` now name it and the
`Dockerfile` pins `node:22.23-alpine`.

**#26 and #29 were the easy ones and are recorded as such:** `react-hook-form` and `user-event` are
declared but imported nowhere under `frontend/src`, and zod's generated schemas are declared but
never parsed. `yarn api:generate` produces no diff under zod 4 — CI checks that every run — and the
generated file is sixteen lines using three constructs, `z.object`, `z.enum([…])` and `z.string()`,
each run against 4.4.3 for parse, reject, unknown-key strip and missing-key. (`react-router` is
#28's bump and is imported nowhere either; its problem was the Node floor above, not its API.)

**The pull requests themselves are still open.** The session that did this work could not merge or
close one — the permission was denied. They are superseded **in content**: every version they
propose is in this branch's `package.json` and `yarn.lock`. Pressing the button is the owner's.

Review: **four independent sub-agent rounds, and the split matters.** Rounds 1 and 2 saw only the
branch's first half — the Gradle `cache-provider` change and the cosign corrections — returning
**CHANGES REQUIRED, 3 BLOCKING, 5 NON-BLOCKING** and then **APPROVED, 0 BLOCKING, 6 NON-BLOCKING**.
**Round 3 was the first to see the toolchain migration** and returned **CHANGES REQUIRED, 4
BLOCKING**; **round 4** returned **CHANGES REQUIRED, 1 BLOCKING, 4 NON-BLOCKING**. Every finding of
every round is fixed.

The one worth carrying: bumping vitest 3 → 4 **silently disabled the untested-file half of the
coverage gate.** Vitest 3 swept untested files in via `coverage.all` (default true); vitest 4
removed `all` and gates the sweep on `coverage.include`, which has no default and which this
repository never set. A wholly untested file under `src/` was absent from the report and
`yarn test:coverage` exited 0. This package shipped that regression and round 3 caught it;
`coverage.include` in `frontend/vite.config.ts` is the fix, with a paired negative both ways. Every
other BLOCKING finding across the four rounds was a false claim in prose — three of them introduced
by a previous round's *fix*, which is the argument for reviewing again after fixing rather than
once.

**[WEB-03](closed/WEB-03.md) is closed.** `main` had been red at `Frontend (React)` since two
frontend majors were merged without the source changes they need: eslint 9→10 with
eslint-plugin-react-hooks 5→7, and tailwindcss 3→4. Both are migrated forward rather than reverted.
The eslint half was one line; the tailwind half moved the `xs` breakpoint into `@theme`, deleted
`tailwind.config.js`, and had to re-declare the `content` globs as `@source` — v4's automatic
detection had started compiling class names out of this repository's own prose. Invariant F1's
paired negative is now a committed gate rather than something a reviewer reproduces by hand.

**[OPS-03](closed/OPS-03.md) is closed.** `compose.prod.yml` shipped a production topology with no
procedure for running it and no way to get the data back; the only mentions of backup or restore in
the tree were two lines of ADR-0007 describing them as a consequence of the tenancy model.
[`docs/ops/deploy.md`](../docs/ops/deploy.md) and
[`docs/ops/backup-restore.md`](../docs/ops/backup-restore.md) close that. The restore was executed
against the real compose file and a locally built image, not merely written — and the first attempt
failed, because `nodera_app` is a cluster-level role that no single-database dump carries. Both
documents state what a laptop rehearsal cannot prove.

**[FIX-01](closed/FIX-01.md) is closed.** The Dependabot wrapper bump in #23 put `backend/gradlew.bat`
into the index as CRLF, so the file read as modified in every fresh clone and every lane stayed
green because no lane looked. `scripts/lint_line_endings.py` now checks how a blob is recorded, the
way `lint_executable_bits.py` checks its mode.

The repository scaffold is in place: vision and scope fence, domain model, architecture, MCP
surface, the database schema, the rule set, the tool-agnostic adapter layer, CI, and this ticket
system.

**[CI-01](closed/CI-01.md) is closed.** The line above used to read "the build chain runs", and it
was wrong: `backend/gradlew` was recorded `100644`, so every `./gradlew` step failed with exit 126
and the backend and database lanes had never run a single check in 23 runs of `ci.yml`. Run 24 is
green, all six jobs. The build chain runs now, on a runner, and `scripts/lint_executable_bits.py`
keeps the bit from being lost again.

**[OPS-01](closed/OPS-01.md) is closed.** Before it, the repository contained no source file at all
and every command in the Makefile, in `docs/ci.md` and in both workflows failed at its first line.
It now builds, tests, lints, containerises and releases — one image with three entrypoints, health
probes, and a migration step that refuses the application role's credentials. What exists is a
chain, not a product: the only endpoints are `/health/live` and `/health/ready`, and the frontend is
a placeholder WEB-01 replaces.

The deployment shape was settled before implementation rather than after:
[ADR-0006](../docs/adr/0006-one-image-three-entrypoints.md) (one image, three entrypoints,
migrations as their own step) and
[ADR-0007](../docs/adr/0007-deployment-is-the-tenant-boundary.md) (the deployment is the tenant
boundary). Both constrain CORE-01 and MCP-01.

Worth reading before the next package: OPS-01's review history took three rounds, and each round's
fixes introduced new defects of the same shape as the ones they fixed. The record is in the closed
ticket.

The backlog below is the path to a running system. It is ordered so that the invariants that are
hardest to retrofit — actor identity, permissions, audit — land first, before anything depends on
their shape.

## Working order

[CORE-01](closed/CORE-01.md), [DB-01](closed/DB-01.md) and [CORE-02](closed/CORE-02.md) are done,
so the order now starts three steps in.

1. **[SEC-01](open/SEC-01.md)** — credentials. It was waiting on DB-01 alongside
   [CORE-02](closed/CORE-02.md), which is now closed: the audit invariant was unenforceable without
   the privilege split the migration creates, and that split is proved rather than assumed.
2. **[API-01](open/API-01.md)** and **[MCP-01](open/MCP-01.md)** — the two surfaces, built against
   the same use cases. MCP-01 depends on API-01 only for the shared error mapping, not for logic.
3. Everything after that is ordered by the table below.

## Open tickets

<!-- BEGIN GENERATED: open tickets (regenerate: python scripts/tickets_index.py --write) -->

_11 open (P1 1 · P2 6 · P3 4 · P4 0) · 16 closed → [REVIEW_REPORT.md](../REVIEW_REPORT.md)._

### 🔴 P1 — Highest (1)

| ID | Title | Effort | Depends on / note |
|---|---|---|---|
| [SEC-01](open/SEC-01.md) | Credential issuance and authentication for humans and agents | ~3 d | CORE-01, DB-01 |

### 🟠 P2 — High (6)

| ID | Title | Effort | Depends on / note |
|---|---|---|---|
| [API-01](open/API-01.md) | REST API skeleton with a contract-first OpenAPI document | ~3 d | CORE-01, SEC-01, CORE-03 |
| [MCP-01](open/MCP-01.md) | MCP server with the orientation and read tools | ~3 d | CORE-01, SEC-01, CORE-03 · Depends on API-01 only for the shared error taxonomy, not for logic. |
| [MCP-02](open/MCP-02.md) | MCP mutating tools with idempotency and structured gate errors | ~2 d | MCP-01, CORE-04 |
| [OPS-02](open/OPS-02.md) | Prove the release package by cutting one | ~0.5 d | Carries the one OPS-01 criterion that cannot be proved from inside this repository. |
| [WEB-01](open/WEB-01.md) | Frontend shell — routing, authentication, generated API client | ~2 d | API-01 |
| [WEB-02](open/WEB-02.md) | Ticket list and detail views, mobile-first | ~3 d | WEB-01, CORE-04 |

### 🟡 P3 — Medium (4)

| ID | Title | Effort | Depends on / note |
|---|---|---|---|
| [CORE-05](open/CORE-05.md) | Markdown ticket import and export with round-trip fidelity | ~2 d | CORE-04 |
| [DOC-01](open/DOC-01.md) | Deployment guide and the self-hosting path | ~1 d | API-01, WEB-01 |
| [GH-01](open/GH-01.md) | Link branches, commits and pull requests onto tickets automatically | ~2 d | CORE-01, CORE-03, DB-01 · Shape settled in ADR-0010 — the fence runs through the payload, so it is enforced in the schema. |
| [MCP-03](open/MCP-03.md) | MCP resources and prompts | ~1 d | MCP-02, CORE-05 |

### ⚪ P4 — Nice-to-have (0)

_none._
<!-- END GENERATED: open tickets -->
