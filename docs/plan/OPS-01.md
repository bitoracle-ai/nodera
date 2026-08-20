# Plan — OPS-01 · Build chain and release package

**Status:** `implemented`
**Ticket:** [`../../tickets/closed/OPS-01.md`](../../tickets/closed/OPS-01.md)
**Decisions this implements:** [ADR-0006](../adr/0006-one-image-three-entrypoints.md) ·
[ADR-0007](../adr/0007-deployment-is-the-tenant-boundary.md)

---

## 1. What phase 1 found, and how it differs from the ticket

The ticket named four defects. Phase 1 found the tree is emptier than that: **there is not one source
file in the repository.** `backend/*/src/**` and `frontend/src` are directory skeletons containing
nothing, `frontend/public` and `frontend/scripts` are empty, and several committed configuration
files point at files that do not exist.

| Referenced by | Points at | Exists |
|---|---|---|
| `frontend/index.html` | `/src/main.tsx` | no |
| `frontend/index.html` | `/favicon.svg` | no |
| `frontend/vite.config.ts` | `./src/test/setup.ts` | no |
| `frontend/package.json` `api:generate` | `scripts/generate-zod.mjs` | no |
| `frontend/package.json` `api:generate` | `backend/api-rest/.../openapi.yaml` | no |
| `frontend/package.json` `lint` | an ESLint 9 flat config | no |
| `frontend/tailwind.config.js` | a PostCSS pipeline | no |
| `backend/app/build.gradle.kts` | `ai.nodera.app.MainKt` | no |

So "commit a wrapper and a lockfile" does not reach a green `make check`; it reaches a different
error one line later. The package is therefore **the build chain end to end, on the smallest surface
that is still real** — not a shell, not a feature, but every link in the chain actually executing.

Effort is revised from ~2 d to **~3 d**, and the ticket's current-state section is corrected in the
same commit. That correction is the point of the section.

## 2. The rule this plan refuses to break

There is a cheap way to a green board: `passWithNoTests`, an ESLint config that lints nothing, a
readiness probe that returns 200 unconditionally, a CI step made conditional until the code it checks
exists. **Every one of those is refused here.** A gate relaxed to fit an unbuilt part reports green
about nothing, and it is never tightened afterwards — the tightening has no author and no ticket.
Where a gate genuinely cannot pass yet, this plan says so in § 7 rather than moving it.

## 3. Files to change, and why each one

### 3.1 Unblock the toolchain

| File | Why |
|---|---|
| `backend/gradlew`, `gradlew.bat`, `gradle/wrapper/**` | Every backend command in the Makefile, `docs/ci.md` and both workflows begins with `./gradlew`. Generated through a `gradle:8-jdk21` container, since this machine has no JDK. The pin is whatever that image's Gradle reports — **8.14.5** — so it names a distribution that demonstrably exists rather than one chosen from memory. |
| `frontend/yarn.lock` | `--frozen-lockfile` and `cache-dependency-path` both name it. Yarn 1.22 classic, matching `--frozen-lockfile` rather than Berry's `--immutable`. |

### 3.2 The three entrypoints (ADR-0006)

| File | Why |
|---|---|
| `backend/app/src/main/kotlin/ai/nodera/app/Main.kt` | Argument dispatch: `serve` (default), `migrate`, `mcp-stdio`. The first Kotlin file in the project, and the one `mainClass` has pointed at since the scaffold. |
| `.../app/Config.kt` | Environment loading, fail-closed. A missing required value refuses start-up (invariant #6); no secret is ever read from a command-line argument. |
| `.../app/Migrate.kt` | Flyway against the owner credentials, non-zero exit on failure. |
| `.../app/Serve.kt` | Ktor with the health routes and the static asset handler. Nothing else — no auth, no domain. |

`mcp-stdio` dispatches and exits non-zero naming MCP-01. Its message goes to **stderr**: stdout is
the MCP framing channel, and a stray byte there reaches an agent's client as a parse error instead of
a sentence. The stub establishes that channel rule and a test guards it, so the rule is already
enforced on the day MCP-01 replaces the stub.

### 3.3 Health, and the contract it owes

| File | Why |
|---|---|
| `backend/api-rest/src/main/kotlin/ai/nodera/api/rest/Health.kt` | `/health/live` and `/health/ready`. Readiness fails while the database is unreachable **or** migrations are pending; liveness does not, or a pending migration becomes a crash loop. |
| `backend/api-rest/src/main/resources/openapi.yaml` | New, containing exactly these two endpoints. Invariant #11 is contract-first: a package that adds a REST endpoint owes it a contract entry. API-01 extends this document rather than creating it. |
| `frontend/scripts/generate-zod.mjs` | `api:generate` names it. Generating types for two health endpoints is a small surface — which is the reason to prove the mechanism here, before API-01 depends on it working. |

**Boundary note.** Readiness needs to know whether migrations are pending, which is a database
question, and `:api-rest` must not depend on `:persistence` (the build enforces it). So `Health.kt`
takes a `ReadinessProbe` functional interface declared in `:api-rest`, and `:app` supplies the
implementation that talks to Flyway. The adapter states the question; the composition root answers it.

### 3.4 The minimum buildable frontend

`src/main.tsx`, `src/App.tsx`, `src/index.css`, `src/test/setup.ts`, `src/App.test.tsx`,
`public/favicon.svg`, `eslint.config.js`, `postcss.config.js`.

`App.tsx` renders one placeholder page naming the instance and its version — it is **not** the shell,
and WEB-01 replaces it. What must be real here is the toolchain: TypeScript strict with
`noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`, ESLint 9 flat config tolerating zero
warnings, Tailwind actually processing, Vitest running under the per-file 80 % gate, and `vite build`
producing a bundle. A placeholder that passes all of those is worth more than a shell that passes
none.

### 3.5 Image and release

| File | Why |
|---|---|
| `Dockerfile` | Fix the dependency-cache layer (six same-named files collapsing into one, with the failure hidden by a trailing `true`), pass arguments through to the entrypoint, point the healthcheck at `/health/ready`, verify the JVM is PID 1. |
| `.github/workflows/release.yml` | `platforms: linux/amd64,linux/arm64`, keyless cosign signature, publish the production compose file with the release. |
| `compose.prod.yml` | New. The topology that `docker-compose.yml` explicitly is not. |
| `.env.example` | A `_FILE` counterpart for every secret. |
| `Makefile` | `make migrate` calls the same code path as the `migrate` entrypoint. |

## 4. Decisions taken in this plan

**4.1 — Both `X` and `X_FILE` set is a start-up refusal, not a precedence rule.** The ticket says the
loader prefers the file. That is wrong for the same reason a defaulted secret is wrong: ambiguous
configuration resolves silently, and the operator learns which value won during an incident. Refusing
and naming the variable costs one line and one test. The ticket's criterion is corrected alongside
this plan.

**4.2 — One migration implementation, not two.** ADR-0006 requires a `migrate` entrypoint using
Flyway *Core*, and the repository already had a Flyway *Gradle plugin*. **Corrected during
implementation:** the plan first called that plugin an unused catalogue entry. It was not — it was
fully configured in `backend/persistence/build.gradle.kts` with its own url, user, password,
`locations` and the `nodera_app_password` placeholder, and it was what `make migrate` and the CI
database lane actually ran. That makes the case stronger rather than weaker: it was a second
implementation of "apply the migrations", configured separately from the one that would run in
production, and the copy that drifts is always the one CI does not exercise. `make migrate` and the
CI database lane now both run `./gradlew :app:run --args=migrate`, and the plugin is gone.

**4.3 — The version is stamped into the image, not read from git.** `-Pversion` already flows through
`release.yml`; the `serve` entrypoint logs it once at start-up and `/health/ready` reports it. An
image that cannot say which version it is makes a fleet unauditable.

## 5. Test plan

Paired-negative wherever a safety claim is made — written, then verified red with the guard disabled,
then re-enabled. A test that has never been seen to fail proves nothing about the guard.

| Claim | Test | Disabling the guard makes it red by |
|---|---|---|
| A missing required secret refuses start-up | `Config` rejects an environment without `NODERA_JWT_SIGNING_KEY` | removing the required-key check |
| `X` and `X_FILE` together refuse start-up | `Config` rejects both-set | removing the conflict check |
| `X_FILE` is honoured | the value comes from the file, not the environment | ignoring the `_FILE` suffix |
| Readiness fails on pending migrations | the probe reports not-ready one migration behind | returning ready unconditionally |
| Liveness ignores database state | live stays 200 with the database stopped | wiring liveness to the probe |
| Stdout carries MCP framing only | `mcp-stdio` leaves stdout byte-empty | writing the diagnostic to stdout |
| An unknown argument fails loudly | non-zero exit plus usage | falling through to `serve` |

Container-level, scripted so a reader can repeat them rather than take a terminal's word for it: two
builds show the dependency layer cached; `migrate` twice is a no-op the second time; `migrate` with
the application credentials exits non-zero; `serve` runs under `--read-only`; `SIGTERM` exits within
the grace period.

## 6. Deliberate non-goals

Authentication, any domain type, any real MCP tool, the frontend shell, `docs/DEPLOYMENT.md`
(DOC-01), the OpenAPI document beyond the two health endpoints (API-01), and any Testcontainers
harness for RLS (DB-01). This package makes the chain run; it does not put a product in it.

## 7. What this package cannot make green, stated rather than worked around

**The frontend CI lane depends on API-01 for its point.** `ci.yml` runs `yarn api:generate` and fails
on a diff. With only the health endpoints in the contract that step passes on its own — but it is
guarding a contract worth guarding only once API-01 lands. This plan does **not** make the step
conditional to hide the gap.

**The backend test lane needs Docker for Testcontainers,** and this machine has no JDK, so backend
work runs inside a container. If mounting the Docker socket into that container does not work here,
the honest outcome is a recorded "not run locally" rather than an assumed pass.

## 8. Open questions

- **Does the toolchain hold at Gradle 8.14.5?** Resolved empirically rather than by recommendation:
  the wrapper takes the version the `gradle:8-jdk21` image ships, and this plan is only correct if
  `./gradlew build` with Kotlin 2.1.20, ktlint 12.2.0 and detekt 1.23.8 passes on it. If it does not,
  the pin moves down and the result is recorded here.

## 9. Added or corrected during implementation

Recorded here rather than silently, because the plan is the record of what was considered.

**9.1 — `V5`, a migration the plan did not foresee.** Readiness has to distinguish "schema current"
from "schema behind", which means reading `flyway_schema_history` — and `V4` grants `nodera_app`
nothing on that table. Without the grant the probe cannot tell, must therefore answer not-ready
(correct polarity), and the instance is permanently undeployable. `V5` grants `select` on that one
table. It does not touch invariant AU1, which is a statement about `audit_event`: what the role
split forbids `nodera_app` is data-definition and rewriting, and reading which migrations ran is
neither.

**9.2 — Migrations are packaged onto the classpath.** `:persistence` copies `db/migrations` into
`db/migration` on its resources, so Flyway finds them at `classpath:db/migration` in a checkout and
in the image alike. One location means "which migration ran" has one answer. The Dockerfile no
longer copies them separately, and the build **fails** when the directory is missing or empty —
Gradle treats a missing `from` as a no-op, which would otherwise ship an image whose `migrate`
applies nothing and reports success.

**9.3 — Redaction of the role password, labelled for what it is.** The first draft claimed a
Flyway failure would otherwise echo `create role nodera_app login password '…'`. Review checked
that and it is not true by default: Flyway Core says "Run Flyway with -X option to see the actual
statement" instead of quoting it. So `redactSecret` is **defence in depth for the debug-logging
case** — which is precisely the configuration someone turns on while a migration is failing — not
a fix for a leak that exists today. It is top-level and `internal` so a unit test can reach it,
because the container check that appeared to prove it could not: the privilege refusal happens
before Flyway runs, so that path never calls it. Flyway types still do not cross the module
boundary.

**9.4 — Four defects in committed configuration that no gate had ever run against.** The Dockerfile
cache layer (already in the ticket); `vite.config.ts` importing `defineConfig` from `vite`, which
has no `test` section; `vitest` pulling a second Vite major whose `Plugin` type is not assignable to
the declared one under `exactOptionalPropertyTypes`, fixed by a `resolutions` pin; and
`@testing-library/dom` being an undeclared peer dependency, so `screen` did not exist at type level.
`ktlintFormat` additionally reformatted `backend/build.gradle.kts`, which had been violating the
style gate since the scaffold.

**9.5 — A new devDependency, with its reason.** `yaml`, used only by `scripts/generate-zod.mjs` to
read the OpenAPI document. `skills/secure-coding.md` requires a new dependency to carry a reason:
the alternative was a hand-rolled YAML parser, which is a worse dependency than a well-known one.

**9.6 — Invariant F1 became a lint rule.** `no-restricted-globals` on `fetch`, with `src/api/**`
exempt. It was previously enforced by review only, and this costs nothing.

**9.7 — `serve` reports a missing static directory instead of failing.** In the image the assets are
always present; on a development machine Vite serves them. The absence is printed, so a broken image
cannot quietly look like a development machine.

**9.8 — `migrate` checks its own privilege before it starts.** The container run found that
`migrate` with the *application* role's credentials exits zero against an already-current schema:
Flyway needs no data-definition rights for a no-op. The acceptance criterion said it must exit
non-zero, and the tempting fix was to soften the criterion to match the behaviour. Instead `Migrator`
now asks `has_schema_privilege(current_user, 'public', 'create')` first and refuses when the answer
is no. The wrong credentials become a first-run refusal rather than a surprise mid-upgrade at the
next release — which is the same argument ADR-0006 makes for keeping migrations out of start-up.

**9.9 — The container checks are committed as `scripts/verify_image.sh`.** They were a throwaway
script until the run above found something real. A proof that only its author can repeat is not a
proof; `docs/ci.md` names the command and states plainly that CI does not run it yet.

**9.10 — The module-boundary guard had never executed.** Phase-4 review found `ci.yml` calling
`:domain:checkModuleBoundaries`, a task registered on the root project — "task not found", so the CI
step failed and the guard had never run once. Correcting the invocation exposed the second half:
its action referenced `logger` and reached across projects through lazy providers, which
`org.gradle.configuration-cache=true` cannot serialise. So the rule that `:api-rest` may not depend
on `:persistence` — the whole reason `ReadinessProbe` exists rather than a direct `Migrator` import —
had never been enforced by anything but attention.

It now runs at configuration time and throws there, which also makes it unavoidable: a violation
fails every Gradle invocation, not only the command a pipeline remembers to call. Demonstrated live
rather than asserted — adding the forbidden edge fails with `:api-rest depends on :persistence`.

The general lesson is worth more than the fix. Two `.kts` task actions in this package turned out
to be silently incompatible with the build's own configuration-cache setting — this one and the
migrations check whose move out of `doFirst` is recorded in § 10 — and neither failed until
something invoked it. A guard that has never
been executed is indistinguishable from one that does not exist.

## 10. Answers to § 8

**Gradle 8.14.5 holds.** The wrapper takes the version the `gradle:8-jdk21` image ships; `./gradlew
ktlintFormat build` passes with Kotlin 2.1.20, ktlint 12.2.0 and detekt 1.23.8. The pin did not have
to move down.

One caveat found the hard way: `org.gradle.configuration-cache=true` means a task action closure in
a `.kts` build script cannot be serialised. The migrations check moved from `doFirst` to
configuration time, which is where it belonged anyway — there is no valid build of `:persistence`
without the migrations.
