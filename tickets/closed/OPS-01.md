---
id: OPS-01
title: "Build chain and release package: wrapper, lockfile, image, signed multi-arch release"
priority: P1
status: closed
effort: ~3 d
depends_on: []
created: 2026-08-20
updated: 2026-08-20
closed: 2026-08-20
note: The release package is configured and self-verifying; producing the artefacts is OPS-02.
---

# OPS-01 · Build chain and release package: wrapper, lockfile, image, signed multi-arch release

**Priority:** P1
**Effort:** ~3 d

Plan: [`../../docs/plan/OPS-01.md`](../../docs/plan/OPS-01.md). Decisions it implements:
[ADR-0006](../../docs/adr/0006-one-image-three-entrypoints.md) ·
[ADR-0007](../../docs/adr/0007-deployment-is-the-tenant-boundary.md).

## Motivation / context

Nothing in this repository can be built. Every command in the Makefile, in `docs/ci.md`, in the
`Dockerfile` and in both workflows fails at its first line — including CORE-01 and DB-01, which the
working order puts first.

The same package finishes the release path, because both halves answer one question — *what is the
artefact* — and answering it twice would produce two answers.

## Current state (honest)

**There is not one source file in the repository.** `backend/*/src/**` and `frontend/src` are
directory skeletons containing nothing; `frontend/public` and `frontend/scripts` are empty. Several
committed configuration files reference files that do not exist:

| Referenced by | Points at |
|---|---|
| `frontend/index.html` | `/src/main.tsx`, `/favicon.svg` |
| `frontend/vite.config.ts` | `./src/test/setup.ts` |
| `frontend/package.json` | `scripts/generate-zod.mjs`, `backend/api-rest/.../openapi.yaml`, an ESLint 9 flat config |
| `frontend/tailwind.config.js` | a PostCSS pipeline that is not configured |
| `backend/app/build.gradle.kts` | `ai.nodera.app.MainKt` |

On top of that:

- **`backend/gradlew`, `backend/gradle/wrapper/` — absent.** Not ignored: `.gitignore:22` re-admits
  `gradle-wrapper.jar` explicitly, so the intent was to commit it. `ci.yml` runs
  `validate-wrappers: true`, which cannot pass either.
- **`frontend/yarn.lock` — absent.** `yarn install --frozen-lockfile` fails, and `release.yml` sets
  `cache-dependency-path` on a file that is not there.
- **`Dockerfile:22`** — `COPY backend/*/build.gradle.kts ./` copies six files of the same basename
  into one directory; they overwrite each other and then overwrite the root `build.gradle.kts` copied
  on the line above. The following `./gradlew dependencies` is suffixed so that it cannot fail, so
  the layer caches nothing and reports nothing. The later full `COPY backend/ ./` restores the tree,
  which is why this has never been visible.
- **`Dockerfile:47`** — the healthcheck calls `/health`. That endpoint is specified in no document;
  `API_CONTRACT.md` does not mention health, liveness or readiness.
- **`Dockerfile:36`** — a single `ENTRYPOINT`. The three entrypoints ADR-0006 requires do not exist,
  and `:app` declares one `mainClass`.
- **`release.yml:104`** — `docker/build-push-action` with no `platforms:`, so the image is amd64
  only, and no signing step. Provenance and SBOM are already on.
- **`docker-compose.yml`** — development topology, correct as it stands. There is no production
  compose file, and its header points at `docs/DEPLOYMENT.md`, which does not exist (DOC-01).

## Approach

1. **Unblock.** Commit the Gradle wrapper (pinned to the version the `gradle:8-jdk21` image ships,
   because this machine has no JDK) and a `yarn.lock` from the current `package.json`. Verify both on
   a clean clone.
2. **Entrypoints.** Give `:app` an argument-dispatching `main()` for `serve` (default), `migrate` and
   `mcp-stdio`, and a `Dockerfile` `ENTRYPOINT` that passes arguments through. `migrate` runs Flyway
   against the owner credentials and exits non-zero on failure. `mcp-stdio` ships as **dispatch
   only** until MCP-01 lands: it exits non-zero naming MCP-01, and it writes that message to
   **stderr**. Stdout is the MCP framing channel, and a stray byte there reaches an agent's client as
   a parse error rather than as a sentence — so the channel rule is established with the stub and
   outlives it. A stub that lies is worse than a missing entrypoint; a stub that says so is not.
3. **Health, and the contract it owes.** `/health/live` and `/health/ready` in `:api-rest`, plus the
   `openapi.yaml` that invariant #11 requires of any package adding a REST endpoint — containing
   exactly these two. Readiness fails while the database is unreachable or migrations are pending;
   liveness does not. `:api-rest` declares a `ReadinessProbe` interface; `:app` implements it, so the
   adapter never reaches the database.
4. **The minimum buildable frontend.** `main.tsx`, a placeholder `App.tsx` that WEB-01 replaces,
   `index.css`, the Vitest setup file, one real test, `favicon.svg`, the ESLint 9 flat config, the
   PostCSS config, and `generate-zod.mjs`. Minimal in surface, not in rigour: strict TypeScript, zero
   tolerated warnings, the per-file 80 % coverage gate, a real bundle.
5. **Dockerfile.** Fix the dependency-cache layer to copy each module build file to its own path,
   remove the failure suppression, point the healthcheck at `/health/ready`, confirm the JVM is PID 1
   and that `SIGTERM` shuts the server down.
6. **Release package.** `platforms: linux/amd64,linux/arm64`, keyless cosign signature, and a
   production `compose.yml` published with the release. Digest recorded in the release notes.
7. **Configuration.** Every secret variable in `.env.example` gains a `_FILE` counterpart. Setting
   both the variable and its `_FILE` form **refuses start-up** and names the variable — ambiguous
   configuration resolves silently, and the operator would otherwise learn which value won during an
   incident.
8. **One migration implementation.** `make migrate` calls the same code path as the `migrate`
   entrypoint; the unused Flyway Gradle plugin leaves the version catalogue.

## Acceptance criteria

- [x] A clean clone reaches `make check` with no manually created file. Verified by cloning into a
      fresh directory, not by reasoning about it.
- [x] `./gradlew` and `yarn install --frozen-lockfile` both succeed; `validate-wrappers` passes.
- [x] `docker build .` succeeds, and the dependency layer is demonstrably reused on a source-only
      edit (two builds, the second showing the layer cached).
- [x] `docker run <image> migrate` applies the schema as the owner and exits 0; run twice, the second
      is a no-op. Run with the `nodera_app` credentials it exits non-zero **even when the schema is
      already current** — Flyway needs no data-definition rights for a no-op, so without an explicit
      privilege check the wrong credentials pass silently and surface mid-upgrade at the next
      release.
- [x] The container-level checks live in `scripts/verify_image.sh` and are runnable by someone other
      than their author. A proof nobody can repeat is not one.
- [x] `serve` starts with no local write: the container runs with a read-only root filesystem.
- [x] `mcp-stdio` exits non-zero, names MCP-01, and leaves stdout byte-for-byte empty. An unknown
      argument exits non-zero with usage. Both are covered by tests on the dispatcher, so the channel
      rule is already guarded on the day the stub is replaced.
- [x] `/health/ready` returns unhealthy while migrations are pending and healthy after; `/health/live`
      stays healthy throughout. Paired-negative: the readiness test is red when the schema check is
      disabled.
- [x] `SIGTERM` to the container drains and exits within the grace period rather than being killed.
- [x] The release package is configured and self-verifying: two architectures, provenance, SBOM, a
      keyless signature, and a `cosign verify` step that runs immediately after signing with the same
      command an operator would use. **Producing** those artefacts needs a real release run, which
      cannot happen from inside this repository — carried by [OPS-02](../open/OPS-02.md) under the
      external-dependency criterion in `docs/PROJECT_MANAGEMENT.md` § 8. Three review rounds recorded
      it as unproven and it is closed as unproven, not as done.
- [x] A required secret that is absent refuses start-up; a variable set both directly and as `_FILE`
      refuses start-up naming it; a `_FILE` value is actually read. Each with a paired-negative test.
- [x] `make check` green.
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings — round 3, after
      rounds 1 and 2 returned 4 and 1 respectively.


## Review history

Append-only across rounds. A later verdict never replaces an earlier one — the disagreement is the
most informative part of the record.

### Round 1 — 2026-08-20 · CHANGES REQUIRED · 4 BLOCKING, 9 NON-BLOCKING

Run in a sub-agent against commit `30ec267`.

| | Finding | Resolution |
|---|---|---|
| **B1** | `ci.yml` invoked `:domain:checkModuleBoundaries`; the task is registered on the **root** project, so the step failed with "task not found" and the guard had never run. `make check-backend` did not invoke it at all despite its help text. The claim in `Health.kt` that the `:api-rest` → `:persistence` edge is a build failure — the entire justification for introducing `ReadinessProbe` rather than importing `Migrator` — was therefore false. | Fixed, and **the finding went deeper than the review reached**: correcting the invocation showed the task could not run at all. Its action referenced `logger` and reached across projects through lazy providers, so with `org.gradle.configuration-cache=true` it failed with "cannot serialize Gradle script object references". The guard was dead twice over. The check now runs at configuration time in `gradle.projectsEvaluated` and throws there, so a violation fails **every** Gradle invocation rather than the one command someone remembered to wire up. Proved live: adding `implementation(project(":persistence"))` to `:api-rest` fails the build with `:api-rest depends on :persistence`, and passes again when removed. |
| **B2** | The password-redaction claim had no paired-negative test, and the container check presented as its proof could not fail: the privilege refusal happens before Flyway runs, so that path never calls the redactor. The reviewer further established that Flyway Core does not quote the failing statement by default at all. | Fixed: `redactSecret` extracted to `internal` top-level with four unit tests; the container check renamed to what it actually proves; `docs/plan/OPS-01.md` § 9.3 rewritten to call the guard defence in depth for the debug-logging case rather than a fix for a present leak. |
| **B3** | The `SchemaState` → `ReadinessReport` mapping had no test. Turning the `Unreachable` branch into `ready = true` left all 17 backend tests green while four places in the tree promised "unknown is never ready". | Fixed: extracted as `internal fun readinessReport`, four tests including that branch. |
| **B4** | `logback.xml`'s `ConsoleAppender` defaults to `System.out`. The first library log line on the `mcp-stdio` path — which MCP-01 will add — would put plain text into the JSON-RPC framing channel, with every test still green. Introduced by this package's own logging fix. | Fixed: `<target>System.err</target>`, guarded by `LoggingTargetTest` asserting the appender's target. |

Non-blocking N1–N9 all fixed in the same session: `make backend`'s static path resolved one level
short; `compose.prod.yml` configured an inert `NODERA_LOG_FORMAT`; the healthcheck ignored
`NODERA_HTTP_PORT`; the wrapper had no `distributionSha256Sum`; the readiness body leaked the driver's
exception class on an unauthenticated endpoint; `NODERA_HTTP_PORT` was validated as a number rather
than a port; `state()` opens an unpooled connection per probe (documented as an operator-set timeout
in `.env.example` rather than changed); a citation pointed at a section of ADR-0006 that does not
exist; and `make check-frontend` omitted the generated-client freshness step that CI runs.

The reviewer's NOT VERIFIED list is retained as-is: nothing image-level was checked by the reviewer,
and no release run exists, so multi-arch, provenance, SBOM and `cosign verify` remain unproven by
anyone. Also noted: `release.yml` signs but nothing in the repository ever verifies.


### Round 2 — 2026-08-20 · CHANGES REQUIRED · 1 BLOCKING, 9 NON-BLOCKING

Run in a sub-agent against commit `450dd47`, by a reviewer who did not perform round 1. It confirmed
all four round-1 BLOCKING findings genuinely fixed, and reproduced two of them by re-breaking and
re-fixing the guard.

| | Finding | Resolution |
|---|---|---|
| **B1** | **`make check` does not run on a clean clone.** No target installs `frontend/node_modules`, so `check-frontend` dies on its first command. The reviewer did not reason about this — they cloned the commit into a fresh directory and hit it. Worse, round 1's N9 fix had just made `docs/ci.md` claim `make check-frontend` is the local equivalent of the CI Frontend lane, whose *first* step is `yarn install --frozen-lockfile`. CI stayed green precisely because it had the step the Makefile lacked, which is the failure `docs/ci.md` exists to prevent. Falsifies acceptance criteria 1 and 12 verbatim. | Fixed: `check-frontend`, `frontend` and `test` install first. Re-proved by cloning fresh and running the lanes. |

Non-blocking N1–N9, all fixed in the same session:

- **N1** `Migrator`'s class KDoc still asserted the Flyway claim round 1 had disproved, sixty lines
  above the function whose KDoc said the opposite. The round-1 record claimed the correction shipped;
  it had shipped in the plan only. Corrected in the code.
- **N2** `checkModuleBoundaries` printed "OK" unconditionally — the same "reads as present, checks
  nothing" shape as the original B1. The task now asserts the configuration-time check actually ran.
- **N6** The guard was narrower than the rule it backs: project-level edges such as
  `:persistence → :api-rest` and anything → `:app` were unchecked. Replaced with the explicit
  inward-only allow-list from `ARCHITECTURE.md` § 2, including a refusal for any module not listed.
  Proved live: `:persistence → :api-rest` now fails with a message naming what that module may
  depend on.
- **N4** The port-range check added in round 1 had no test; deleting it left all tests green. Added.
- **N3** the shutdown KDoc had become detached from its constants · **N5** the rationale for hiding
  the driver's exception class contradicted the same body returning the build version · **N7** the
  plan claimed "three places" where two are demonstrable · **N8** four more citations to numbered
  sections ADR-0006 does not have · **N9** the backend row in `docs/ci.md` no longer matched
  `make check-backend`.

Round 2's NOT VERIFIED list stands and is not claimed as passing: the release path end to end
(multi-arch, provenance, SBOM, `cosign verify`), `validate-wrappers` as an executed action, CI itself
on this branch, and Testcontainers — this package adds none. The reviewer also confirmed round 1's
observation that `release.yml` signs but nothing in the repository ever verifies.


### Round 3 — 2026-08-20 · APPROVED · 0 BLOCKING, 6 NON-BLOCKING

Run in a sub-agent against commit `3c3d155`, by a third reviewer. It cloned the branch fresh, ran
the gates, and proved three guards live by breaking them and restoring them.

All six non-blocking findings fixed in the same session. Two of them were, once again, the pattern:

- **N1** — round 2's own fix put `make check-backend` in the `docs/ci.md` Backend row while that
  target ran no `build`, which the row still claimed. A contributor breaking the distribution
  assembly would have been green locally and red in CI, then consulted the one document that exists
  to make that impossible. `check-backend` now runs `build`.
- **N3** — the rationale round 2 rewrote in `Readiness.kt` was left standing, verbatim and
  contradicted, forty lines away in `Serve.kt`. Same shape as round 2's N1, which was itself round
  1's B2 leftover.

The other four: **N2** the Database row's local equivalent ran migrate once and never ran
`schema_integrity.sql`; **N4** the boundary marker's comment overstated what a configuration-cache
hit proves; **N5** the widened guard read only `compileClasspath` while its comment promised "no
others" — it now reads the test classpath too; **N6** a cross-reference pointed at a section that
does not carry the claim.

`make verify-db` was added for N2 and then corrected before it shipped: pointed at the development
database it would have failed confusingly on any machine whose dev database already has a schema
without Flyway history — which this one does. It now creates and drops a throwaway database, matching
the empty Postgres the CI lane starts from. Verified: 5 migrations applied, second run 0, and
`db/checks/schema_integrity.sql` passes against the resulting schema — the first time anything in
this project has run that script.

Both earlier rounds noted that `release.yml` signed and nothing ever verified. Fixed here: the
workflow verifies its own signature immediately after making it, with the command an operator would
use.


## Review result

**APPROVED at round 3**, after rounds 1 and 2 returned 4 and 1 BLOCKING findings. Every finding from
all three rounds is fixed; the rounds are recorded above and none was collapsed into the others.

What the three rounds are actually evidence of: **each round's fixes introduced new defects of the
same shape as the ones they fixed.** Round 1 found a guard that had never executed; the fix produced
a task that printed "OK" unconditionally. Round 1 found a missing job-to-local equivalence; the fix
produced an equivalence claim that was false on a clean clone. Round 2 corrected a false comment;
the correction left the same false comment standing forty lines away. Round 3 found that one too.

That is not three unlucky rounds. It is what happens when the person checking a fix is the person
who wrote it, and it is the reason phase 4 is a sub-agent rather than a careful re-read. The single
most valuable finding in the package — that `make check` had never run on a clean clone — came from
a reviewer who cloned the repository instead of reasoning about it.

**Gates, run rather than asserted:** 9 repository gates · backend `ktlintCheck detekt
checkModuleBoundaries test build` with 27 tests · frontend generated-client freshness, lint,
typecheck, 15 tests at per-file coverage above 80 %, build · `scripts/verify_image.sh` 14/14 ·
`make check` on a freshly cloned working copy, backend included with a cold Gradle cache ·
migrations applied twice against an empty database with `db/checks/schema_integrity.sql` passing
afterwards, which nothing in this project had previously run.

**Guards demonstrated red, not merely present:** all six configuration and dispatch paired negatives;
the readiness `Unreachable` branch; the logging target; the port range; and the module boundary, by
adding `:api-rest → :persistence` and then `:persistence → :api-rest` and watching the build refuse
each with a message naming the rule.

**Left unproven, deliberately:** the release artefacts (OPS-02), `validate-wrappers` as an executed
action, CI on this branch, and Testcontainers — this package adds none.

## Affected files

- `backend/gradlew`, `backend/gradlew.bat`, `backend/gradle/wrapper/` — new, committed.
- `frontend/yarn.lock` — new, committed.
- `backend/app/src/main/kotlin/ai/nodera/app/` — `Main.kt`, `Config.kt`, `Migrate.kt`, `Serve.kt`.
- `backend/api-rest/src/main/kotlin/ai/nodera/api/rest/Health.kt` and
  `backend/api-rest/src/main/resources/openapi.yaml` — new.
- `frontend/src/`, `frontend/public/favicon.svg`, `frontend/scripts/generate-zod.mjs`,
  `frontend/eslint.config.js`, `frontend/postcss.config.js` — new.
- `Dockerfile` — cache layer, entrypoint, healthcheck.
- `.github/workflows/release.yml` — `platforms:`, cosign, publish the production compose file.
- `compose.prod.yml` — new, released alongside the image.
- `.env.example` — `_FILE` counterparts. `Makefile` — one migration path.
- `backend/gradle/libs.versions.toml` and `backend/persistence/build.gradle.kts` — the Flyway Gradle
  plugin goes; `:persistence` packages the migrations onto the classpath instead.
- `db/migrations/V5__readiness_probe_reads_migration_history.sql` — new. `nodera_app` needs `select`
  on the migration history or the readiness probe can never tell "current" from "behind".
- `frontend/package.json` — the `yaml` devDependency for the code generator, the undeclared
  `@testing-library/dom` peer, and a `resolutions` pin so `vitest` does not bring a second Vite
  major whose `Plugin` type is not assignable to the declared one.
- `frontend/vite.config.ts` — `defineConfig` from `vitest/config`; the `vite` one has no `test`
  section, so the Vitest configuration never type-checked.
- `docs/API_CONTRACT.md`, `docs/ci.md`, `CHANGELOG.md` — pulled along in the same package.

## Verification

Clone the repository into an empty directory on a machine that has never built it, run `make check`,
then `docker build .` and the `migrate` / `serve` sequence from ADR-0006. The readiness and `SIGTERM`
criteria are proved by tests in `:api-rest` and by a scripted container run recorded in the closure
note — not by observation in a terminal nobody else can repeat.
