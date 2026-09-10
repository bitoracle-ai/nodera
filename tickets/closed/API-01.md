---
id: API-01
title: REST API skeleton with a contract-first OpenAPI document
priority: P2
status: closed
effort: ~3 d
depends_on: [CORE-01, SEC-01, CORE-03]
created: 2026-08-20
updated: 2026-09-10
closed: 2026-09-10
---

# API-01 · REST API skeleton with a contract-first OpenAPI document

**Priority:** P2
**Effort:** ~3 d
**Skills:** `critical-invariants.md` · `secure-coding.md` · `backend-kotlin.md` · `testing.md`

## Motivation / context

The OpenAPI document is the contract the frontend generates its types from. Written after the fact
it becomes documentation nobody trusts; written first it is the thing both sides are checked
against.

## Current state (honest)

**Corrected on 2026-09-10, at the start of this package.** Two of the three sentences this section
carried were written in August and were no longer true.

- `:api-rest` is **not** an empty module. SEC-01 put `AuthenticationPlugin.kt` in it — the middleware
  that turns an `Authorization` header into an `ActorContext` — and OPS-01 put `Health.kt` and
  `openapi.yaml` there.
- `docs/API_CONTRACT.md` **exists** and is linked from `docs/INDEX.md`. What it does not carry is
  `/auth/*`, which is the gap `tickets/INDEX.md` names.
- What is genuinely absent is every route under `/api/v1`, and the three things the project-scoped
  half of the contract needs before it can have any: an implementation of `PermissionDirectory`,
  an establishment of `nodera.project_ids`, and a way to break the circle between them — the setting
  must come from the acting actor's memberships (invariant #5 forbids the path segment), and `V4`'s
  `project_membership_visible` hides those rows until the setting exists.

Full reading in [`../../docs/plan/API-01.md`](../../docs/plan/API-01.md) § 1.

## ⚠️ Scope amendment, made in this package and visible on purpose

**Two acceptance criteria moved to [API-02](API-02.md), for two different reasons.** They are stated
separately because one framing covering both was wrong about one of them — a maintainer checking the
amendment against the diff is exactly who this section is for.

**`not_found` for a project the caller cannot see** moved because there is no project route here to
prove it on, and there is none because of the third bullet above: breaking the scope circle is a
**structural decision** about row-level security, which
[`../../docs/PROJECT_MANAGEMENT.md`](../../docs/PROJECT_MANAGEMENT.md) § 8 puts with the maintainers
rather than in a contributor's diff, and the work behind it is a migration and two `:persistence`
adapters — a **foreign subtree** for an `API-` package. The rule itself *is* proved here, on the one
resource that has it: a credential that is not the caller's.

**Every mutating route accepts an idempotency key** did **not** move for that reason, and saying so
would be false: this package ships three mutating routes — `POST /me/credentials`,
`DELETE /me/credentials/{id}`, `POST /auth/refresh` — none of which resolves a project, so the
criterion applied here and is **not met**. It moved because nothing reads `idempotency_record` and
the mechanism belongs in `:application`, in the mutation's own transaction: a header parsed and
discarded at the adapter advertises a replay protection that is not there, which is worse than the
header being absent. That is a real reason and a smaller one, and it is the reason API-02 records.

Reversing either is the maintainers' to do; it is written here rather than in a commit message so
that it can be.

## Approach

1. Write the `/auth/*` contract in `docs/API_CONTRACT.md` and the OpenAPI document **before** the
   routes.
2. Routes over the use cases that exist and can be reached: the caller's own actor, the caller's own
   credentials, and session rotation. Translation only.
3. One error taxonomy — `ErrorCode` in `:application`, because `:api-mcp` cannot see `:api-rest`
   (ADR-0005) and MCP-01 depends on this package for exactly it.
4. A drift check that compares the routing tree with the committed document, in both directions.

## Acceptance criteria

- [x] Every route maps to a use case and contains no permission decision, no state transition, no
      SQL and no audit write.
- [x] The committed OpenAPI document matches what the server serves; CI fails on drift.
- [x] `not_found` is returned for something the caller cannot see, never `forbidden` — a test
      confirms an outsider cannot distinguish absent from invisible. Proved on
      `DELETE /me/credentials/{id}`: the same identifier, the same caller, one run with the row
      present and owned by somebody else and one with no such row, compared byte for byte. *(The
      project form of this criterion is API-02's — see the scope amendment.)*
- [x] `/auth/*` is specified, and every route the contract describes but does not serve says why.
- [x] Every security-shaped claim carries a paired negative that was **watched** with its guard
      disabled, and whose comment says so — including the two that did not go red and what was
      changed because of it.
- [x] `make check` green — `check-repo`, `check-db` and `check-frontend` natively; the backend
      lane and the `verify-db` equivalent in throwaway containers, this machine having no JDK.
      Which lanes executed, and the environments created and removed, are in § Verification.
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings — round 4, APPROVED.

## Affected files

- `docs/API_CONTRACT.md` — the `/auth/*` contract, the served/specified split, the redaction rule.
- `backend/api-rest/src/main/resources/openapi.yaml` — extended, written before the routes.
- `backend/api-rest/src/main/kotlin/ai/nodera/api/rest/` — the taxonomy, the correlation plugin, the
  routes, and the one function that registers all of them.
- `backend/application/src/main/kotlin/ai/nodera/application/error/ErrorCode.kt` — shared with MCP-01.
- `backend/application/src/main/kotlin/ai/nodera/application/identity/` — the `ActorProfiles` port and
  the `WhoAmI` use case behind `/me`.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/identity/JdbcActorProfiles.kt`.
- `backend/app/src/main/kotlin/ai/nodera/app/Serve.kt` — the graph and the mount.
- `backend/gradle/libs.versions.toml` and `backend/api-rest/build.gradle.kts` — `snakeyaml-engine`,
  **test scope only**. A check that guards the contract has to parse the contract; a hand-rolled
  subset parser is a gate that stops seeing entries without saying so. The reason is in the
  catalogue beside the version, as `skills/secure-coding.md` § Dependencies asks.
- `frontend/src/api/generated/` — regenerated by `yarn api:generate`, which CI re-runs and diffs.

**Claims this package falsified, corrected in the same change** rather than left for a later round
to find one copy at a time — the distillate lesson DOC-06 wrote down:

- `docs/ARCHITECTURE.md` § 5 said the OIDC callback "is API-01's" and that a scope would be chosen
  by "the surface that issues tokens". That surface exists now and chooses none; the callback is not
  in this package and is not assigned to one.
- `docs/MCP.md` § 9 now says where the shared codes live, because MCP-01 reads that table.
- `CHANGELOG.md` said "no other route exists yet — API-01 builds them", and `tickets/INDEX.md`'s
  OPS-01 paragraph said "the only endpoints are `/health/*`" in the present tense.

## Review result

### Round 1 — 2026-09-10 — CHANGES REQUIRED, 1 BLOCKING, 9 NON-BLOCKING

**The blocking finding was a working defect, not a claim: renewing a session was impossible for a
client behaving normally.** The credential middleware refuses any presented credential it cannot use,
before routing. A client keeps a default `Authorization` header; its access token expires; it calls
`POST /auth/refresh` with a valid refresh token in the body and that stale header still attached —
and got `401`, with a refusal naming the wrong credential. The same header stopped a probe reading
the health endpoints and a client reading the contract it was about to call. All four are routes the
contract marks `security: []`, and the middleware had no way to know.

Fixed by giving the middleware that list, and by asserting in `ContractDriftTest` that the list is
exactly the operations the document declares `security: []` — two statements of one fact, compared
rather than trusted. Both halves are watched (M11, M12).

**Nine non-blocking, all fixed in the same session.** Three were claims rather than code, which is
the class this repository keeps finding: the shared error codes are `docs/MCP.md` § **9** and the
KDoc said § 4; the secrets sweep claimed "every served route" and drove five of seven; and the scope
amendment gave one reason for two criteria, and it was true of only one — the idempotency criterion
applied to this package's three mutating routes and is simply **not met**, for a smaller and
different reason. The rest: `runCatching` swallowed `CancellationException`, so a client that
aborted mid-body was answered `422` into a dead channel; an expiry fallback could have reported an
expiry the credential does not have; the drift walk dropped handlers that are not method-selected —
the shape the static content routing itself takes; two comments overstated what they proved; and the
comment volume was trimmed where it restated `docs/`.

Reasoning and the reviewer's own framing: [`../../docs/plan/API-01.md`](../../docs/plan/API-01.md)
§ 9.

### Round 2 — 2026-09-10 — CHANGES REQUIRED, 1 BLOCKING, 4 NON-BLOCKING

**The blocking finding was a regression round 1's own fix introduced**, which is the third time this
repository has recorded that shape. Round 1's comment trim moved the middleware's hand-rolled problem
document onto the shared `respondProblem` — which redacts `detail` — and `SecretRedaction` replaces
`bearer` or `basic` followed by whitespace and a token. The one detail containing the word is the
unsupported-scheme refusal, so it answered `the Authorization header must carry a bearer ***`,
and falsified a sentence this package had just written into `docs/API_CONTRACT.md` § 4.

**A substring assertion is why nothing caught it** — SEC-01's test asserts `shouldContain "bearer"`,
which the corrupted string satisfies. The replacement compares whole strings: `ProblemDetailTest`
asserts every detail this surface can emit survives redaction byte-identically, and asserts two whole
served bodies. The sentence is hyphenated now; where prose and the redactor disagree, the prose
changes.

Four non-blocking, all fixed: two comments claimed a watched negative the recorded run did not have
(both now run, M13 and M14); the `actorContext()` KDoc still promised "no third state" after the
exemption made that false on four paths; and the byte-for-byte contract test compared the classpath
copy with the wire without ever opening the repository's file — it opens all three now.

**Running M14 rather than asserting it found a third defect of the same shape.** The test that proves
the exemption is not over-broad asserted only the status, and stayed **green** with `/api/v1/me`
added to the exemption: the header is then ignored, the route sees no context, and `401` comes back
either way. It asserts the refusal's detail now — the middleware and the route say different things —
and it is red. Three rounds, three defects, and every one of them a weak assertion rather than wrong
logic.

Reasoning: [`../../docs/plan/API-01.md`](../../docs/plan/API-01.md) § 10.

### Round 3 — 2026-09-10 — CHANGES REQUIRED, 1 BLOCKING, 7 NON-BLOCKING

**The blocking finding was the contract disagreeing with the server, on day one rather than after a
drift.** `/api/v1/me` declared `200` and `401`; the route also answers `404` when the credential
resolves and the actor does not — deliberately, documented, and asserted by this package's own
`IdentityRoutesTest`. The generated `frontend/src/api/generated/schema.ts` therefore typed
`operations["whoAmI"].responses` as `{200, 401}`, so **a frontend written against the generated types
had no member for an answer the server gives** — invariant #11's failure mode, and acceptance
criterion 2 ticked but not met. The code was right and the document was wrong. Fixed in the
document, the client regenerated, and the case written into `docs/API_CONTRACT.md` § 3.

**Nothing in this repository catches that class:** `ContractDriftTest` compares `(method, path)` sets
and never response maps. A check that does is a different check rather than a widening of that walk,
with a real cost, and it is not this package's — recorded as a proposal in
[`../../docs/plan/API-01.md`](../../docs/plan/API-01.md) § 11.

**Seven non-blocking, all fixed.** Four were claims rather than code, which is now the settled
pattern of this package: two live present-tense sentences in `tickets/INDEX.md` that this package
falsified (the third copy of the same class was corrected here in round 1, and
[`../../skills/code-review.md`](../../skills/code-review.md) § 3 is the rule that says a corrected
claim has siblings); the plan restating, for both moved criteria, the single framing round 1 found
false; a test KDoc crediting a change to a review round that did not make it; and
`docs/API_CONTRACT.md`'s `validation_failed` row, unfalsifiable before this package and false after
it — `MALFORMED_BODY` names no field, on purpose. The rest: the unknown-keys guard had no test that
isolated it (both existing cases also omitted a required field, so both stayed green with the
setting flipped), and two comments restated documentation — 16 lines retelling round 1's defect
narrative already carried in four other places, and the project-scope circle already in four.

**The reviewer could not run the build, and one of its questions was worth the run.** `.gitleaks.toml`
is untouched while this package adds five fixture literals, and the implementing session had already
reported a gitleaks run as green. Both are true, and what reconciles them is *what each scan saw*:
the green run scanned **committed history**, which this package is not in, so it could not have seen
the new fixtures at all. A working-tree scan is what settles it. Run here in a throwaway container
over the whole repository — `gitleaks detect --no-git`, this repository's own config — **no leaks**.
Rerun with the allowlist removed, the same tree yields six findings and every one of them is an
existing SEC-01 literal the allowlist already names. The one new file among them, `RestFixtures.kt`,
is flagged only for its compact JWS, which is byte-identical to SEC-01's and already covered. The
five new selectors and verifiers match **no rule at all**: `generic-api-key` wants a keyword beside
the value, and `REFRESH_SELECTOR`/`MINTED_VERIFIER` are not keywords. So the allowlist correctly
gains nothing — and the file's own sentence, "adding a differently-shaped fixture means adding it
here", is what made a reviewer expect otherwise. It is a list of suppressions, not a register of
fixtures, and it now says so.

Two findings the previous session raised against itself are folded in and fixed:
`AuthenticationPluginTest`'s surviving `shouldContain "bearer"` — the exact assertion shape round 2
indicted, left in place as a trap for the next reader — and the unknown-keys claim above.

Reasoning: [`../../docs/plan/API-01.md`](../../docs/plan/API-01.md) § 11.

### Round 4 — 2026-09-10 — APPROVED, 0 BLOCKING, 2 NON-BLOCKING

The confirmation round, run because three of the four blocking findings after SEC-01's round 2 were
defects a previous round's own fix had introduced. This time the fix diff introduced none: the
reviewer re-derived every status each of the seven handlers can produce and compared it with the
document by hand, re-ran both generators and diffed the output against the committed client, and
traced all eighteen guard comments looking for one whose named mutation would leave its test green.
It found none.

**Both non-blocking findings were fixed.** The first was this file's own Working order asserting
API-01 done while linking it as `open/` — the verdict was given before this line was true, which is
the class rounds 1 and 3 corrected elsewhere in the same file. Closure repoints both prose links.

The second is the more useful, and it is the package's signature defect one last time:
`Correlation.kt`'s `correlationId()` said its fallback is "the same answer it would have produced for
a request that carried no usable header". **It is not.** With the plugin installed the id is minted
once, kept in the call's attributes and echoed; without it, every call mints a different one and
none is echoed, so the response header and the audit trail can disagree inside one request — which is
exactly what M9 exists to guard. The fallback is live code, not hypothetical:
`AuthenticationPluginTest` builds an application that installs the middleware and not the plugin. The
comment now says what the fallback actually does. Nothing else changed; round 2's regression came out
of a comment trim, so this one touched no call site.

Reasoning: [`../../docs/plan/API-01.md`](../../docs/plan/API-01.md) § 12.

## Verification

`./gradlew :api-rest:test` for the routes, the drift check and the enumeration test;
`./gradlew :persistence:test` for the profile read against a real Postgres. Say which lanes executed
rather than being served from a cache, and name the environment the run created and removed.

### What was run at closure, 2026-09-10

**The machine has no JDK**, so `make check` could not be invoked whole and the backend half ran in a
container instead — which is the same rule the rest of this repository follows for anything beyond
its own toolchain. Each lane and its real result:

| Lane | Command | Result |
|---|---|---|
| `check-repo` | `make PY=py check-repo` | green — 13 checks including both self-tests and the TODO/FIXME grep |
| `check-db` | `make PY=py check-db` | green — the SQL gate fires on all 5 fixtures, then passes |
| `check-backend` | `ktlintCheck detekt checkModuleBoundaries test build --no-build-cache --rerun-tasks` in an `eclipse-temurin:21-jdk` container | `BUILD SUCCESSFUL`, **74 actionable tasks: 74 executed** — nothing up to date, nothing from the cache. 735 tests, 0 failures, 0 errors, 0 skipped (`domain` 381 · `persistence` 174 · `application` 81 · `app` 51 · `api-rest` 48) |
| `check-frontend` | `make PY=py check-frontend` | green — the generated client regenerates to no diff, lint, types, coverage and build |
| database lane | `compose.verify.yml` under `-p nodera-api01-verify`, migrations applied twice from the same container image | two `BUILD SUCCESSFUL` and `Schema integrity: OK` |
| secret scan (CI-only) | `gitleaks detect --no-git` over the whole tree, in a throwaway container | **no leaks** |

**The negative harness was re-run in full on this tree**, by script: each mutation applied, the named
spec run, the tree restored, and every failure traced to the spec name that failed rather than to a
`BUILD FAILED` line. Seventeen red, M6 green exactly as `docs/plan/API-01.md` § 5 records, M18 red.
The first attempt at M5 disabled the wrong guard and stayed green; § 11 says what that established.

**Environments created and removed.** Throwaway containers from `eclipse-temurin:21-jdk`,
`postgres:16-alpine` (via `compose.verify.yml`) and `ghcr.io/gitleaks/gitleaks`, all `--rm` or
`down -v`; one Gradle cache volume created for these runs and removed at the end; the compose
project's own volume and network removed with it. Testcontainers' own containers were reaped by its
reaper. Nothing was left running and no pre-existing container or volume was touched. The named
limit is the one `docs/ci.md` already states: the base images stay in the local image cache.
