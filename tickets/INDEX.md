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

**2026-08-24 — the production surface has a runbook and a restore that was walked, the frontend
toolchain is migrated forward off the two majors that reddened the lane, and the application is
still unwritten.**

**The Dependabot backlog was assessed against the green `main`. Two of the four are cleared to
merge; two are not.** All four were rebased onto `09f26f6` and re-ran there, so their green ticks are
current rather than inherited from the red base. None of them was merged — the session that assessed
them could not, so every merge below is still the owner's to make.

- **Cleared:** [#29](https://github.com/bitoracle-ai/nodera/pull/29), the rebased replacement for the
  conflicting #25 (`react-hook-form` 7.85→7.86, `@testing-library/user-event` 14.6.5→14.6.6), and
  [#26](https://github.com/bitoracle-ai/nodera/pull/26) (zod 3→4). Neither touches anything that
  runs: `react-hook-form` and `user-event` are declared but not imported anywhere under
  `frontend/src`, and zod's generated schemas are declared but never parsed. Regenerating
  `src/api/generated/` under zod 4 produces no diff — CI checks that on every run. The generated
  file is sixteen lines and uses three constructs, `z.object`, `z.enum([…])` and `z.string()`; each
  was run against zod 4.4.3 for parse, reject, unknown-key strip and missing-key. Reproducible
  check: build `frontend/` on `main` and on each branch — all three `dist/` trees hash identically.
- **Blocked:** [#28](https://github.com/bitoracle-ai/nodera/pull/28) (react-router 7→8) **must not
  merge on its own.** `react-router@8.3.0` declares `engines.node ">=22.22.0"`; this repository's
  `.nvmrc` says 22.20.0, and Yarn 1 *refuses* an engines mismatch rather than warning, so merging it
  alone breaks `yarn install` — and with it `make check`, `make test` and `make frontend` — for
  anyone following `.nvmrc`. It needs [WEB-04](open/WEB-04.md)'s floor bump first, or in the same
  change.
- **Parked:** [#27](https://github.com/bitoracle-ai/nodera/pull/27) (vite 6→8) is green and must not
  be merged. It does not upgrade Vite, it adds a second one. [WEB-04](open/WEB-04.md) carries the
  evidence.

Review: two independent sub-agent rounds. Round one returned **CHANGES REQUIRED — 3 BLOCKING, 5
NON-BLOCKING**; round two **APPROVED, 0 BLOCKING, 6 NON-BLOCKING**, all of which are fixed here too.
Every BLOCKING finding was a false claim in prose that no gate can see: two sat in the cosign
documentation, one in the #28 clearance above. Both factual ones were reproduced before being
accepted — Yarn 1 aborts on an `engines` mismatch rather than warning, and cosign 2.6.0 already
reads the 3.x bundle format when passed `--new-bundle-format`. Round two's findings were all of the
same kind, including one false claim that a round-one *fix* introduced: worth knowing that
correcting prose in this repository reliably produces more of it.

**[WEB-03](closed/WEB-03.md) is closed.** `main` had been red at `Frontend (React)` since two
frontend majors were merged without the source changes they need: eslint 9→10 with
eslint-plugin-react-hooks 5→7, and tailwindcss 3→4. Both are migrated forward rather than reverted.
The eslint half was one line; the tailwind half moved the `xs` breakpoint into `@theme`, deleted
`tailwind.config.js`, and had to re-declare the `content` globs as `@source` — v4's automatic
detection had started compiling class names out of this repository's own prose. Invariant F1's
paired negative is now a committed test rather than something a reviewer reproduces by hand.

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

1. **[CORE-01](open/CORE-01.md)** — the actor model and the permission engine. Everything else
   references it, so it goes first and it goes in carefully.
2. **[DB-01](open/DB-01.md)** → **[CORE-02](open/CORE-02.md)** — schema applied and the audit
   recorder wired, in that order: the audit invariant is unenforceable without the privilege
   split the migration creates.
3. **[API-01](open/API-01.md)** and **[MCP-01](open/MCP-01.md)** — the two surfaces, built against
   the same use cases. MCP-01 depends on API-01 only for the shared error mapping, not for logic.
4. Everything after that is ordered by the table below.

## Open tickets

<!-- BEGIN GENERATED: open tickets (regenerate: python scripts/tickets_index.py --write) -->

_17 open (P1 4 · P2 9 · P3 4 · P4 0) · 5 closed → [REVIEW_REPORT.md](../REVIEW_REPORT.md)._

### 🔴 P1 — Highest (4)

| ID | Title | Effort | Depends on / note |
|---|---|---|---|
| [CORE-01](open/CORE-01.md) | Actor model and permission engine in the domain core | ~3 d | Everything references this — nothing else starts before it is reviewed. |
| [CORE-02](open/CORE-02.md) | Audit recorder — one event per mutation, in the mutation's transaction | ~2 d | CORE-01, DB-01 |
| [DB-01](open/DB-01.md) | Apply the baseline schema and prove row-level security with negative tests | ~2 d | — |
| [SEC-01](open/SEC-01.md) | Credential issuance and authentication for humans and agents | ~3 d | CORE-01, DB-01 |

### 🟠 P2 — High (9)

| ID | Title | Effort | Depends on / note |
|---|---|---|---|
| [API-01](open/API-01.md) | REST API skeleton with a contract-first OpenAPI document | ~3 d | CORE-01, SEC-01, CORE-03 |
| [CORE-03](open/CORE-03.md) | Ticket lifecycle, key allocation and the closure gate | ~3 d | CORE-01, CORE-02 |
| [CORE-04](open/CORE-04.md) | Comments, mentions and the review record | ~2 d | CORE-02, CORE-03 |
| [MCP-01](open/MCP-01.md) | MCP server with the orientation and read tools | ~3 d | CORE-01, SEC-01, CORE-03 · Depends on API-01 only for the shared error taxonomy, not for logic. |
| [MCP-02](open/MCP-02.md) | MCP mutating tools with idempotency and structured gate errors | ~2 d | MCP-01, CORE-04 |
| [OPS-02](open/OPS-02.md) | Prove the release package by cutting one | ~0.5 d | Carries the one OPS-01 criterion that cannot be proved from inside this repository. |
| [WEB-01](open/WEB-01.md) | Frontend shell — routing, authentication, generated API client | ~2 d | API-01 |
| [WEB-02](open/WEB-02.md) | Ticket list and detail views, mobile-first | ~3 d | WEB-01, CORE-04 |
| [WEB-04](open/WEB-04.md) | Vite 8 needs a vitest migration, not a merge | ~1 d | Parks #27. Also carries the react-router 8 floor bump that #28 needs. |

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
