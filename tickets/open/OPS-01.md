---
id: OPS-01
title: "Build chain and release package: wrapper, lockfile, image, signed multi-arch release"
priority: P1
status: open
effort: ~2 d
depends_on: []
created: 2026-08-20
updated: 2026-08-20
note: Blocks every backend and frontend package — no ./gradlew and no yarn.lock exist.
---

# OPS-01 · Build chain and release package: wrapper, lockfile, image, signed multi-arch release

**Priority:** P1
**Effort:** ~2 d

## Motivation / context

Nothing in this repository can currently be built. `backend/gradlew` and `frontend/yarn.lock` do not
exist, so every command in the Makefile, in `docs/ci.md`, in the `Dockerfile` and in `release.yml`
fails at its first line — including CORE-01 and DB-01, which are the two packages the working order
puts first.

The same package finishes the release path, because the two halves share one question — *what is the
artifact* — and answering it twice would produce two answers. [ADR-0006](../../docs/adr/0006-one-image-three-entrypoints.md)
and [ADR-0007](../../docs/adr/0007-deployment-is-the-tenant-boundary.md) settle the shape; this
package makes the repository match it.

## Current state (honest)

- **`backend/gradlew`, `backend/gradle/wrapper/` — absent.** Not ignored: `.gitignore:22` re-admits
  `gradle-wrapper.jar` explicitly, so the intent was to commit it. `backend/gradle/` holds only
  `libs.versions.toml`. `ci.yml` runs `validate-wrappers: true`, which cannot pass either.
- **`frontend/yarn.lock` — absent.** `yarn install --frozen-lockfile` fails, and `release.yml` sets
  `cache-dependency-path: frontend/yarn.lock` on a file that is not there.
- **`Dockerfile:22`** — `COPY backend/*/build.gradle.kts ./` copies six files of the same basename
  into one directory; they overwrite each other and then overwrite the root `build.gradle.kts` copied
  on the line above. The following `./gradlew dependencies` is suffixed `|| true`, so the layer fails
  silently and caches nothing. The subsequent full `COPY backend/ ./` restores the tree, which is why
  this has never been visible.
- **`Dockerfile:47`** — the healthcheck calls `http://localhost:8080/health`. That endpoint is
  specified in no document; `API_CONTRACT.md` does not mention health, liveness or readiness.
- **`Dockerfile:36`** — a single `ENTRYPOINT ["./bin/app"]`. The three entrypoints ADR-0006 requires
  do not exist, and `:app` declares one `mainClass`.
- **`release.yml:104`** — `docker/build-push-action` with no `platforms:`, so the image is amd64 only,
  and no signing step. Provenance and SBOM are already on.
- **`docker-compose.yml`** — development topology, correct as it stands. There is no production
  compose file, and the header comment points at `docs/DEPLOYMENT.md`, which does not exist (DOC-01).

## Approach

1. **Unblock.** Commit the Gradle wrapper (8.x, matching `libs.versions.toml`) and a `yarn.lock`
   generated from the current `package.json`. Verify `./gradlew build` and `yarn install
   --frozen-lockfile` on a clean clone.
2. **Entrypoints.** Give `:app` an argument-dispatching `main()` for `serve` (default), `migrate` and
   `mcp-stdio`, and a `Dockerfile` `ENTRYPOINT` that passes arguments through. `migrate` runs Flyway
   against the owner credentials and exits non-zero on failure. `mcp-stdio` ships as **dispatch
   only** until MCP-01 lands: it exits non-zero naming MCP-01, and it writes that message to
   **stderr**. Stdout is the MCP framing channel, and a stray byte there reaches an agent's client
   as a parse error rather than as a sentence — so the channel rule is established with the stub and
   outlives it. A stub that lies is worse than a missing entrypoint; a stub that says so is not.
3. **Health.** `/health/live` and `/health/ready` in `:api-rest`, added to `API_CONTRACT.md` in the
   same commit. Readiness fails while the database is unreachable or the schema version is behind the
   image; liveness does not.
4. **Dockerfile.** Fix the dependency-cache layer to copy each module build file to its own path,
   drop the `|| true`, point the healthcheck at `/health/ready`, confirm the JVM is PID 1 and that
   `SIGTERM` shuts the server down.
5. **Release package.** `platforms: linux/amd64,linux/arm64`, keyless cosign signature, and a
   production `compose.yml` published with the release. Digest recorded in the release notes.
6. **Configuration.** Every secret variable in `.env.example` gains a `_FILE` counterpart, and the
   loader prefers the file when both are set.

## Acceptance criteria

- [ ] A clean clone reaches `make check` with no manually created file. Verified by cloning into a
      fresh directory, not by reasoning about it.
- [ ] `./gradlew` and `yarn install --frozen-lockfile` both succeed; `validate-wrappers` passes in CI.
- [ ] `docker build .` succeeds, and the dependency layer is demonstrably reused on a source-only edit
      (two builds, second one shows the layer cached).
- [ ] `docker run <image> migrate` applies the schema as the owner and exits 0; run twice, the second
      is a no-op. Run with the `nodera_app` credentials, it exits non-zero.
- [ ] `serve` starts with no local write: the container runs with a read-only root filesystem.
- [ ] `mcp-stdio` exits non-zero, names MCP-01, and leaves stdout byte-for-byte empty. An unknown
      argument exits non-zero with usage. Both are covered by tests on the dispatcher, so the channel
      rule is already guarded on the day the stub is replaced.
- [ ] `/health/ready` returns unhealthy while migrations are pending and healthy after; `/health/live`
      stays healthy throughout. Paired-negative: the readiness test is red when the schema check is
      disabled.
- [ ] `SIGTERM` to the container drains and exits within the grace period rather than being killed.
- [ ] A dry-run release produces a two-architecture image with provenance, SBOM and a cosign signature
      that `cosign verify` accepts against the repository's OIDC identity.
- [ ] Every `_FILE` secret variant is honoured, with a test that fails when the file path is ignored.
- [ ] `make check` green.
- [ ] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.

## Affected files

- `backend/gradlew`, `backend/gradlew.bat`, `backend/gradle/wrapper/` — new, committed.
- `frontend/yarn.lock` — new, committed.
- `backend/app/src/main/.../Main.kt` — argument dispatch for the three entrypoints.
- `backend/api-rest/` — `/health/live`, `/health/ready`; `docs/API_CONTRACT.md` in the same commit.
- `Dockerfile` — cache layer, entrypoint, healthcheck.
- `.github/workflows/release.yml` — `platforms:`, cosign, publish the production compose file.
- `compose.prod.yml` — new, released alongside the image.
- `.env.example` — `_FILE` counterparts for every secret.

## Verification

Clone the repository into an empty directory on a machine that has never built it, run `make check`,
then `docker build .` and the `migrate` / `serve` sequence from ADR-0006. The readiness and `SIGTERM`
criteria are proved by tests in `:api-rest` and by a scripted container run recorded in the closure
note — not by observation in a terminal that nobody else can repeat.
