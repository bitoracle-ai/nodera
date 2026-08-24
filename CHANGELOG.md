# Changelog

All notable changes to Nodera are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow refuses to cut a version with no section here — a release nobody can
evaluate is not a release.

## [Unreleased]

### Added

- Repository foundation: vision and scope fence, domain model, architecture, MCP surface
  specification, and the baseline database schema as four forward-only migrations.
- The rule set: twelve critical invariants, ten skills, and the phase-4 review rubric.
- Tool-agnostic adapter layer (ADR-0002) with mechanical consistency checks, so a
  contributor's choice of AI assistant is not a quality variable.
- Markdown ticket system with generated views, plus the tooling that keeps them honest.
- CI with one aggregated required check, CodeQL analysis, and a manual-only release path
  enforced by a gate rather than by a comment.
- **The stack and surface decisions, recorded rather than inherited:** Kotlin on the JVM with the
  alternatives stated fairly (ADR-0008); the MCP server staying in-process with its protocol layer
  taken from the official SDK instead of hand-written (ADR-0009); external references stored as links
  rather than copies, with a forge integration modelled as an ordinary agent actor so attenuation and
  revocation apply to it unchanged (ADR-0010).
- A design system with **exactly two themes**, semantic colour tokens that must have a value in both,
  touch ergonomics for one-handed use, and the rule that agent output is never styled as
  second-class — demoting it in a stylesheet is the one place no invariant lint can see.
- **The deployment shape, decided before implementation:** one image with three entrypoints
  (`serve`, `migrate`, `mcp-stdio`) and migrations as their own step (ADR-0006); the deployment
  as the tenant boundary, with the control plane outside this repository (ADR-0007).
- **A build chain that runs** (OPS-01): the Gradle wrapper, `yarn.lock`, the composition root,
  and the minimum real frontend. The repository previously contained no source file at all.
- `/health/live` and `/health/ready`, deliberately separate. Readiness reports `503` while
  migrations are pending or the database cannot be read; liveness never consults either, so a
  wait does not become a crash loop.
- `V5` grants the application role `select` on the migration history, which is what lets the
  readiness probe tell "current" from "behind" without holding any data-definition right.
- Every secret also reads from a `_FILE` path, for Docker Secrets, Kubernetes Secrets and Vault.
  Setting both forms of one variable refuses start-up rather than resolving by precedence.
- `compose.prod.yml`, published with each release: read-only root filesystem, no exposed database
  port, and `migrate` running to completion as the schema owner before `serve` starts as the
  application role.
- Release images are built for `linux/amd64` and `linux/arm64` and signed with keyless cosign,
  alongside the provenance and SBOM that were already produced.

### Changed

- Every workflow job sets `timeout-minutes`; the previous default was six hours.
- Every `actions/checkout` sets `persist-credentials: false`, except the release step that pushes
  the tag.
- Every JavaScript action pin moved to a node24 major, ahead of Node 20's removal from GitHub
  runners on 2026-09-16. `sigstore/cosign-installer` is composite, so no deadline reached it, but
  it moved to v4 all the same, and v4 installs cosign 3.x — the new protobuf bundle format and
  signatures as OCI Image 1.1 referring artifacts, both on by default.
- All three `gradle/actions/setup-gradle` steps set `cache-provider: basic`. The default,
  `enhanced`, is a commercial caching service under gradle.com's terms; `basic` is the
  open-source one, and this is an MIT repository.
- One migration implementation instead of two. The Flyway *Gradle plugin* carried its own url,
  locations and placeholders beside the runtime's; `make migrate` and the CI database lane now run
  the same `migrate` entrypoint the image runs, and the migrations are packaged onto the classpath
  so there is one location in a checkout and in the image alike.
- Frontend requests are same-origin: the API serves the web assets, so `NODERA_PUBLIC_API_BASE_URL`
  is relative and no CORS plugin is installed in a normal deployment.
- Invariant F1 is now a lint rule — a component calling `fetch` directly fails `yarn lint` instead
  of waiting for a reviewer to notice.

### Fixed

- **CI had never been green — 23 runs, 23 failures.** `backend/gradlew` was recorded in the git
  index as `100644`, so every `./gradlew` step failed with `Permission denied` and exit code 126,
  taking the `backend` and `database` lanes and `CI Gate` with them. The bit is restored on the
  wrapper and on every other tracked file carrying a shebang, and `scripts/lint_executable_bits.py`
  reads the git index to keep it from recurring (CI-01).
- `release.yml` treated an unreachable remote as "this tag is free" and could have re-cut a
  published version; the tag probe now separates exit 0, exit 2 and everything else.
- `release.yml` verified fewer repository gates than a pull request did.
- `scripts/_common.py` claimed to be unit-tested by `tests/test_tooling.py`, which does not exist.
- The `Dockerfile` dependency-cache layer copied six same-named module build files into one
  directory, overwriting each other and then the root build script, with the failure hidden by a
  trailing `|| true`. It cached nothing and reported nothing.
- The healthcheck pointed at `/health`, an endpoint that was specified nowhere, and hardcoded port
  8080 while `NODERA_HTTP_PORT` is a supported variable.
- The module-boundary guard had never run: CI addressed it on the wrong project, and its task
  action was incompatible with the configuration cache. The rule that adapters cannot reach the
  database was enforced by nothing. It now runs at configuration time, so a violation fails every
  Gradle invocation.
- The forward-only migration guard had never been armed. `scripts/lint_sql.py` compares each
  migration against a sha256 ledger at `db/migrations/.checksums`, but that file did not exist and
  an absent ledger returned "no problems" — so `make check-db` and the CI database lane both passed
  on an in-place edit to any migration. Flyway's own `validateOnMigrate` could not cover the gap
  either: the database lane starts from an empty Postgres and so has nothing to compare against,
  which left the mismatch to surface on the next `make migrate` of whoever already had the old
  version applied. The ledger now records `V1` to `V5`, and a missing ledger or an unrecorded
  migration is itself a finding rather than a silent pass.
- Logback's `ConsoleAppender` defaulted to stdout, which on the `mcp-stdio` entrypoint is the MCP
  framing channel. Diagnostics now go to stderr, guarded by a test on the appender's target.
- The readiness body returned the driver's exception class on an unauthenticated endpoint.
- The Gradle wrapper had no `distributionSha256Sum`, so the distribution was fetched unverified.
- `vite.config.ts` imported `defineConfig` from `vite`, which has no `test` section, so the Vitest
  configuration never type-checked. `vitest` also pulled a second Vite major, whose `Plugin` type is
  not assignable to the declared one; both are now pinned to a single version.
- `@testing-library/dom` was an undeclared peer dependency, so `screen` did not exist at type level.
- The coverage gate scanned `dist/`. Vitest replaces its default excludes when `coverage.exclude`
  is given, so `yarn test:coverage` passed on a clean tree and failed as soon as anyone had run
  `yarn build` first — an order-dependent gate.

### Notes

Nothing is released yet. The application itself is not implemented — see
[`tickets/INDEX.md`](tickets/INDEX.md) for what exists and what is next.
