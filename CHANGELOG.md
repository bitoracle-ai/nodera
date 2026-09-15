# Changelog

All notable changes to Nodera are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow refuses to cut a version with no section here — a release nobody can
evaluate is not a release.

## [Unreleased]

### Added

- **The REST surface an agent authenticates itself on** (API-01). `GET /api/v1/me` answers with the
  actor envelope — `kind` always present, an agent's owner beside it — and
  `POST`/`DELETE /api/v1/me/credentials` mint and revoke the caller's own personal access tokens,
  with the plaintext returned exactly once. `POST /api/v1/auth/refresh` rotates a session, revoking
  the token presented in the transaction that mints its replacement. No route resolves a project, so
  none of them asks the permission engine anything: a capability is held in a project, and
  `docs/API_CONTRACT.md` § 2b says which routes are still specified rather than served, and why.
- **The OpenAPI document is the contract, and drift is a red build.** It is served at
  `GET /openapi.yaml` as the same bytes the repository holds, and a test compares the routing tree
  with the document's paths in both directions — an undocumented route and a documented path with no
  route each fail, with the offending operation named.
- **One error taxonomy for both surfaces.** `ErrorCode` lives in `:application` rather than in an
  adapter, because `:api-mcp` is a sibling of `:api-rest` and cannot see it; the wire codes are the
  contract and the HTTP status each becomes stays in the REST adapter. Problem documents are
  RFC 9457 with `application/problem+json`, and both caller-visible strings pass through
  `SecretRedaction` on the way out — `instance` is the request's own path, and a caller that puts a
  token where an identifier belongs would otherwise have it handed back.
- **A stale `Authorization` header no longer stops the request that renews it.** The credential
  middleware refuses any presented credential it cannot use, before routing — which is right
  everywhere the contract declares security and wrong on the four routes that declare none. A client
  keeps a default header, its access token expires, and it calls refresh with that header still
  attached; refusing there made renewing a session impossible for a client behaving normally, and the
  refusal named the wrong credential. The middleware now has the list of routes the contract marks
  `security: []`, and a test asserts that list is exactly those operations, so the two cannot drift.
- **Every refusal's `detail` reaches the caller unchanged, and a test says so.** The redaction that
  keeps a token out of a problem document also rewrites deliberate text that looks like one:
  "a bearer credential" came back as "a bearer ***". The sentence is hyphenated, and every detail
  this surface can emit is asserted byte-identical after redaction — whole strings, because a
  substring assertion is what let the corruption through.
- **`X-Request-Id` correlates every response, including refusals.** A client value that is not a UUID
  is replaced rather than refused or passed through: refusing would break a caller behind a proxy
  with its own correlation format, and passing it through would put a client-controlled string where
  `audit_event.request_id` is `uuid not null`. The id echoed is the id the audit trail records.

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
- **The audit trail has a writer, and a mechanism that will not let a mutation skip it** (CORE-02).
  `AuditRecorder` appends exactly one `audit_event` row on the transaction the use case already
  opened; the sink refuses to write when there is none, so a mutation and its audit row can never
  commit or roll back independently. Denials are recorded with `outcome = 'denied'`, and the
  delegation chain comes from the authenticated context rather than from a caller's argument.
  Completeness is enforced rather than reviewed: a JDBC listener in the test harness reads the
  statements that actually executed and refuses to commit a transaction whose mutations carry no
  audit event, so bypassing the recorder, hand-writing the SQL and forgetting are all caught the
  same way.
- **A direct `open → closed` edge in the ticket status machine** for `wont_do`, `duplicate` and
  `superseded`, so a ticket recognised as a duplicate the moment it is filed closes in one
  transition instead of being walked through `in_progress` and `in_review`. `done` is refused on
  that edge before the closure gate is consulted — a direct `done` would route around the review
  the gate reads. Both halves carry a test that is red with the guard removed (CORE-06).
- **An agent can authenticate as itself, and so can a person** (SEC-01) — the mechanism, and not
  yet an endpoint to reach it through. Personal access tokens
  (`nod_pat_…`) are issued once and stored only as an Argon2id hash; humans sign in with an e-mail
  address and a one-time code and receive a fifteen-minute access JWT beside a rotating opaque
  refresh token. Both credential shapes reach the same `CredentialAuthenticator` and leave through
  one function, so the `ActorContext` they produce differs only in the surface the request arrived
  on and in which actor was identified — asserted as an equality, not described in a comment. The
  refresh token is refused there and spent only at the rotation path, so a captured one cannot serve
  requests for its whole lifetime without ever rotating past the revocation that would catch it.
- **A token is a selector and a verifier**, `nod_pat_<selector>_<verifier>` in lowercase
  hexadecimal. Argon2id is salted, so a hash cannot be a lookup key; the selector addresses the row
  and carries no authority, and the verifier is checked against the hash in constant time. The
  alphabet also puts the documented example token `nod_pat_EXAMPLE…` outside the grammar, so it can
  neither be minted nor parsed. `V7` adds the column, its unique index and `sign_in_code`.
- **Redaction at the logging boundary.** `%rmsg` and `%rex` in `logback.xml` remove anything
  credential-shaped — a Nodera token, a compact JWS, an authorisation header — from the message and
  from the stack trace, so a secret that reached a log line through code nobody here wrote is
  contained. The types that hold a secret refuse to render it as well; the boundary is the layer
  that catches what those cannot.
- **`serve` refuses to start without a signing key**, an issuer, or with an Argon2id cost below
  OWASP's floor, and refuses a partly-set OIDC configuration rather than falling back to local
  sign-in. A credential passed as a command-line argument is refused before the command is parsed,
  named by position and never echoed.
- **A REST authentication middleware.** An `Authorization: Bearer` header becomes an `ActorContext`
  on the call before routing; a header that is present and unusable is answered `401` with the
  contract's `unauthenticated` problem document rather than continuing as an anonymous request.
  Health stays unauthenticated; API-01 added the routes that use it.
- **A conditional write decides, not the read before it.** Rotating a session, redeeming a sign-in
  code and spending one of its five guesses each end in an `update … where` that can match nothing,
  and each now refuses when it does: concurrent callers holding one refresh token, or one code, no
  longer both receive a session, and the attempt limit is no longer widened by asking at once.
  Every mutating refusal that knows an actor is recorded — requesting a code for a suspended actor,
  and a replayed refresh token, included. Authentication stays a read and writes nothing.

### Changed

- `compose.prod.yml` mounts a third secret, `secrets/jwt_signing_key`, and requires
  `NODERA_PUBLIC_URL` for the `iss` claim. **An existing deployment must create both before its
  next upgrade**, or `serve` refuses to start — which is the intended behaviour rather than a
  regression (`docs/ops/deploy.md`).
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
- Frontend toolchain to Vite 8 with vitest 4, in one change because they cannot move apart:
  vitest 3 depends on `vite "^5 || ^6 || ^7.0.0-0"`, so bumping Vite alone installs a second
  major and runs the tests on one while building the product with the other. `resolutions.vite`
  moved with it rather than being left contradicting the dependency.
- `coverage.include` in `frontend/vite.config.ts`, without which the vitest 4 bump above silently
  disables half the coverage gate. Vitest 3 swept untested files into the report via
  `coverage.all`, which defaulted to true; vitest 4 removed `all` and gates the sweep on
  `include`, which has no default. For one commit an untested file under `src/` was absent from
  the report and `yarn test:coverage` exited 0.
- Node floor raised to what the dependencies actually require: `.nvmrc` 22.23.2 and
  `engines.node >=22.22.0`, with `react`/`react-dom` at `^19.2.7`. Both workflows now take the
  version from `node-version-file: .nvmrc` instead of the major `"22"`, which had been resolving
  above the floor by luck.
- Frontend dependencies: `zod` 4, `react-router` 8, `react-hook-form` 7.86,
  `@testing-library/user-event` 14.6.6.
- One migration implementation instead of two. The Flyway *Gradle plugin* carried its own url,
  locations and placeholders beside the runtime's; `make migrate` and the CI database lane now run
  the same `migrate` entrypoint the image runs, and the migrations are packaged onto the classpath
  so there is one location in a checkout and in the image alike.
- Frontend requests are same-origin: the API serves the web assets, so `NODERA_PUBLIC_API_BASE_URL`
  is relative and no CORS plugin is installed in a normal deployment.
- Invariant F1 is now a lint rule — a component calling `fetch` directly fails `yarn lint` instead
  of waiting for a reviewer to notice.
- `make verify-db` runs in a Postgres of its own (`compose.verify.yml`, its own project name, volume
  and port) and removes it again, on the failing path too. It used to depend on `up`, so it started
  the developer's Postgres if it was stopped, left it running, and left the cluster-level
  `nodera_app` role behind in that cluster.
- `scripts/verify_image.sh` removes its containers with `-v`, so a run no longer leaves the
  anonymous volume the Postgres image declares. It also runs on Windows under Git Bash, which
  rewrote `--tmpfs /tmp` into a host path so the `serve` container never started. Its SIGTERM
  check now requires the container to have been running: a stop of an absent or already-exited one
  returns inside the grace period, so the check passed for a JVM that was never signalled.

### Fixed

- **The audit harness compared equal to the connection it hides.** `AuditCompleteness` proxies a
  JDBC connection and forwarded every call it did not handle to the target, `equals` among them, so
  the watched connection answered `false` for itself and `true` for the raw one underneath — the
  guarantee it exists to give, inverted. The handlers now answer `equals`, `hashCode` and `toString`
  by identity, on the connection proxy and on both statement proxies. The case named "not the one
  underneath it" never caught it: green under kotest 5 because `shouldBe` short-circuited on
  reference identity, and under 6.2.5 red with the same message whether the escape guard is there or
  deleted. It now asserts identity, and a new case states both polarities of the equality (FIX-03).
- **CodeQL's `java-kotlin` analysis had extracted nothing since the Kotlin 2.4.20 bump.** The
  extractor is a compiler plugin and aborts the compile it traces rather than analysing less, so the
  job uploaded a failed-run SARIF and the backend — credentials, tokens, the permission engine —
  went unanalysed. The analysis build, and only it, now compiles with a version bundle 2.27.0
  accepts, through a Gradle property no other build sets; the shipped build keeps 2.4.20, where
  CVE-2026-53914 is fixed. The pin leaves with CI-03 (FIX-03).
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
- A ticket could wear a label belonging to another project. `ticket_label` was the last two-ended
  association without a same-project guard: the policy scopes `ticket_id` only, and referential
  integrity checks bypass row-level security by design, so the `label_id` end was unguarded. What
  this allowed was writing a cross-project association and confirming that a label id exists — not
  reading another project's data: the label's own columns stayed invisible and the join returned
  nothing. `V6` adds the trigger that `ticket_dependency`, which has the same shape, has carried
  since `V2`. Found by DB-01's negative tests rather than by reading the migration (DB-01).
- Invariant F1's paired negative failed on machine speed rather than on code. It ran ESLint inside
  vitest, where loading the flat config and its plugins costs about 1.2 s warm and over 20 s on a
  loaded machine, against a 5 s `testTimeout` — so `CI Gate`, a required check, could go red on a
  change with nothing wrong with it, which is how a real red gets waved through. The proof now runs
  in `yarn lint`, which carries no per-test clock (FIX-02).

### Notes

Nothing is released yet. The application itself is not implemented — see
[`tickets/INDEX.md`](tickets/INDEX.md) for what exists and what is next.
