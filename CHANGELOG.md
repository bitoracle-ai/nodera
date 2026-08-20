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
- The rule set: twelve critical invariants, nine skills, and the phase-4 review rubric.
- Tool-agnostic adapter layer (ADR-0002) with mechanical consistency checks, so a
  contributor's choice of AI assistant is not a quality variable.
- Markdown ticket system with generated views, plus the tooling that keeps them honest.
- CI with one aggregated required check, CodeQL analysis, and a manual-only release path
  enforced by a gate rather than by a comment.
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

- One migration implementation instead of two. The Flyway *Gradle plugin* carried its own url,
  locations and placeholders beside the runtime's; `make migrate` and the CI database lane now run
  the same `migrate` entrypoint the image runs, and the migrations are packaged onto the classpath
  so there is one location in a checkout and in the image alike.
- Frontend requests are same-origin: the API serves the web assets, so `NODERA_PUBLIC_API_BASE_URL`
  is relative and no CORS plugin is installed in a normal deployment.
- Invariant F1 is now a lint rule — a component calling `fetch` directly fails `yarn lint` instead
  of waiting for a reviewer to notice.

### Fixed

- The `Dockerfile` dependency-cache layer copied six same-named module build files into one
  directory, overwriting each other and then the root build script, with the failure hidden by a
  trailing `|| true`. It cached nothing and reported nothing.
- The healthcheck pointed at `/health`, an endpoint that was specified nowhere, and hardcoded port
  8080 while `NODERA_HTTP_PORT` is a supported variable.
- The module-boundary guard had never run: CI addressed it on the wrong project, and its task
  action was incompatible with the configuration cache. The rule that adapters cannot reach the
  database was enforced by nothing. It now runs at configuration time, so a violation fails every
  Gradle invocation.
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
