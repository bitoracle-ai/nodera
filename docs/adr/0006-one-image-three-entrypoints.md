# ADR-0006 — One image, three entrypoints; migrations are a separate step

- **Status:** Accepted (2026-08-20)
- **Context documents:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md) § 7 · [`../VISION.md`](../VISION.md) § 2 ·
  [`0007-deployment-is-the-tenant-boundary.md`](0007-deployment-is-the-tenant-boundary.md)
- **Affects:** `Dockerfile`, `docker-compose.yml`, `.github/workflows/release.yml`, `backend/app/`,
  `.env.example`, `docs/DEPLOYMENT.md`.

## Context

Nodera has to be deployable by two very different readers from **one** artifact: an operator
self-hosting a single instance, and the maintainers running instances on someone else's behalf. Two
artifacts means one of them is the tested path and the other is the one that breaks on a Friday.

Three files in this repository disagreed about the shape. `ARCHITECTURE.md` § 7 described three
services (`postgres`, `nodera-api`, `nodera-web`), the `Dockerfile` built a single image carrying the
backend distribution, the frontend build and the migrations, and `docker-compose.yml` started only
Postgres. The `Dockerfile` had decided in practice; nothing recorded why, so nothing stopped the
other two from drifting further.

**Forces:**

- **Vite bakes `NODERA_PUBLIC_API_BASE_URL` into the bundle at build time.** Any topology that ships
  the frontend as its own deployable needs either a per-environment frontend build — which contradicts
  "built once and promoted unchanged", the rule the `Dockerfile` header states — or a start-up hook
  that rewrites the bundle, which is a build step wearing a runtime costume.
- **The application role cannot run DDL.** `nodera_app` holds `INSERT` and `SELECT` on `audit_event`
  and nothing else (`V4`, invariant AU1). Migrating at application start-up is not merely inelegant:
  it requires granting that role rights with which it could remove its own restrictions, and AU1 stops
  being a database guarantee.
- **A managed fleet multiplies the upgrade surface.** Every additional deployable is another thing to
  version, sign, scan, promote and roll back *in lockstep with the others*. The cost of a second image
  is not the second image; it is the lockstep.
- **MCP has two transports with opposite deployment shapes.** `stdio` is a process spawned by an agent
  on a developer's machine; streamable HTTP is a shared network service. Both must come out of the
  same release.

## Decision

**1 — One image.** Backend distribution, built frontend assets and the migration files ship in a
single OCI image. The API serves the assets from the same origin, so the frontend's API base URL is
relative and CORS leaves the self-hosting path entirely.

**2 — Three entrypoints on that image**, selected by argument:

| Entrypoint | Runs as | Purpose |
|---|---|---|
| `serve` (default) | `nodera_app` | REST on 8080, MCP streamable HTTP on 8081 |
| `migrate` | the schema owner | Flyway, one shot, exits non-zero on failure |
| `mcp-stdio` | `nodera_app` | An agent-spawned server: `docker run -i --rm` |

**3 — Migrations run as their own invocation, never at application start-up.** Same image, so the
migrations can never be a different version from the code that runs against them; separate invocation,
so they can carry separate credentials.

**4 — The upgrade procedure follows from forward-only expand/contract:** apply the expand migration,
start the new image, and ship the contract migration in the *next* release. Rollback is a rollback of
the image, never of the database.

**5 — The release artifact is a digest-addressable image** on `ghcr.io`, built for `linux/amd64` and
`linux/arm64`, carrying provenance, an SBOM and a keyless cosign signature, accompanied by a versioned
production `compose.yml` and a CHANGELOG entry. Nodera pulls nothing and reports nothing home; an
update is an operator's deliberate act.

**6 — Six stateless properties keep 1–5 true**, and are acceptance criteria rather than aspirations:

1. No process writes to the container filesystem. Attachments, when they exist, go through an
   object-storage port — never `File.write`.
2. Every secret is readable from a file path as well as an environment variable, because Docker
   Secrets, Kubernetes Secrets and Vault all mount files.
3. `/health/live` and `/health/ready` are distinct. Readiness fails while the database is unreachable
   or migrations are pending; liveness does not, or the container crash-loops instead of waiting.
4. Two containers of the same instance can run at once: no in-process scheduler, no singleton lock —
   Postgres advisory locks where one is needed — and no permission cache that cannot be invalidated.
5. MCP streamable HTTP sessions are not bound to a process, or the binding is documented as a
   sticky-routing requirement. This is decided in MCP-01, not after it.
6. `SIGTERM` reaches the JVM as PID 1 and shuts the server down gracefully.

## Consequences

- ✅ One artifact to version, sign, scan and promote — for one instance and for a fleet alike. The
  frontend can never be a version out of step with the API it calls.
- ✅ CORS is not part of the self-hosting path. `.env.example` forbids wildcard origins in every
  environment; same-origin assets make that free rather than fiddly.
- ✅ The same image is the API container, the migration job and the agent's stdio sidecar. That is the
  shape an orchestrator expects, so a Kubernetes deployment needs no second build.
- ✅ Migration failure is visible as a failed job before the new version serves traffic.
- ⚠️ The operator must run `migrate` before `serve` on every upgrade. A managed deployment automates
  it; a self-hoster reads it in `docs/DEPLOYMENT.md`, and the readiness probe is what catches a
  forgotten run instead of a confusing 500.
- ⚠️ Serving static assets from the JVM is marginally less efficient than a dedicated web server.
  Accepted: the assets are content-hashed and immutable, so a CDN or reverse-proxy cache in front
  removes the difference without adding a deployable.
- ⚠️ A contract migration is always one release behind its expand. That is the price of image-level
  rollback and it is deliberate.

## Alternatives considered

- **Two images (`nodera-api` + `nodera-web`).** What `ARCHITECTURE.md` § 7 described. Rejected: it
  reintroduces the build-time API base URL and CORS, and it doubles the release lockstep. The strongest
  argument for it — serve one SPA from a CDN for a whole fleet — does not survive: the SPA belongs to a
  *version*, instances upgrade independently, and a shared SPA would have to be version-matched per
  instance. A CDN can still cache in front of the single image.
- **Migrations at application start-up.** Rejected on invariant grounds, not on taste. See Forces.
- **No container as the primary path** (a distribution tarball plus a systemd unit). Rejected as the
  *primary* path — Docker is already required for development and the test suite — but the
  `installDist` tarball remains a documented alternative, because it is what the image contains anyway
  and it costs nothing to keep. Distribution packages (`.deb`, `.rpm`) are refused: per-distribution
  maintenance with no owner.
- **A separate `flyway` CLI image.** Rejected: it decouples the migration version from the application
  version, which is the one thing this decision is trying to make impossible.
