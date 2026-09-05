---
id: SEC-01
title: Credential issuance and authentication for humans and agents
priority: P1
status: closed
effort: ~3 d
depends_on: [CORE-01, DB-01]
created: 2026-08-20
updated: 2026-09-05
closed: 2026-09-05
---

# SEC-01 · Credential issuance and authentication for humans and agents

**Priority:** P1
**Effort:** ~3 d
**Skills:** `critical-invariants.md` · `secure-coding.md` · `agent-actors.md` · `backend-kotlin.md` ·
`database-design.md` · `testing.md`
**Plan:** [`../../docs/plan/SEC-01.md`](../../docs/plan/SEC-01.md)

## Motivation / context

An agent authenticating as itself is the first of the five first-class capabilities, and nothing
else can be demonstrated without it. Both surfaces need the same `ActorContext`, produced from two
different credential shapes.

## Current state (honest)

Rounds 2–6 were run by two earlier sessions, each of which was interrupted before it finished;
round 6 reached zero BLOCKING and its six fixes were never re-reviewed. Rounds 7 and 7b are a fresh
pair over the whole tree, and **round 7c is the confirming re-review this package had never
reached** — run last, on a tree nothing was editing. Every fix below is in the tree.

Gates, on the tree as committed:

- `make PY=py check-repo` and `make PY=py check-db` — green, every script executed.
- `make PY=py check-frontend` — green; this package touches no frontend file.
- Backend lane, `--no-build-cache --rerun-tasks`, so nothing was cache-served: **74 of 74 actionable
  tasks executed**, **695 tests, 0 failures** (`:domain` 381, `:persistence` 171, `:application` 81,
  `:app` 51, `:api-rest` 11). This is the run that covers the final tree.
- `make PY=py check` as one invocation — "All gates green", and the backend lane reported **1 of 74
  tasks executed, 73 up to date**: every `test` task was `UP-TO-DATE`. That run is the gate, not the
  evidence; the line above it is.
- `make PY=py verify-db` — green. Seven migrations applied to an empty database including `V7`, the
  second pass applied none, `db/checks/schema_integrity.sql` passed. Its Postgres was created and
  removed with the project, volume and network it brought.
- gitleaks 8.30.1 over the tracked and new files with this `.gitleaks.toml`: no leaks. Watched
  failing with a high-entropy fixture added to an identity test file, which is what proves the
  allowlist names values rather than blinding the test tree.
- `scripts/verify_image.sh` was **not** run: `docker build` fails at `FROM node:26.8-alpine` with
  `yarn: not found`, before anything this package touches. `Dockerfile` is unchanged here and the
  image is built only by the manual release path, so nothing in this package regressed — but the
  acceptance script's own SEC-01 check, the one round 3 added, is therefore unexecuted. Said here
  rather than left to be discovered at a release.

Built and covered by tests:

- A token is `nod_pat_<selector>_<verifier>` in lowercase hexadecimal, with the selector stored in
  the clear as the lookup key and only an Argon2id hash of the verifier stored. The plaintext is
  returned once, from `IssuePersonalAccessToken`, and there is no path that returns it again.
- `CredentialAuthenticator` accepts either shape — a personal access token or a signed access JWT —
  and both leave through one function, so the `ActorContext` differs only in the surface and the
  actor. A **refresh** token is refused there: it is spent at the rotation path and nowhere else, so
  a captured one cannot serve requests for thirty days without ever rotating.
- Human sign-in by e-mail plus a one-time code, single-use, superseded on reissue, with an attempt
  limit; a fifteen-minute access JWT and a rotating opaque refresh token. Rotation revokes the token
  it was handed **and refuses when that revocation matched no row**, so the second of two concurrent
  rotations is refused exactly as a sequential replay is; redemption of the code does the same with
  its own conditional write.
- `serve` refuses to start without a signing key or an issuer, with an Argon2id cost below OWASP's
  floor, or with a partly-set OIDC configuration. A credential passed as a command-line argument is
  refused before the command is parsed.
- Redaction at the logging boundary (`%rmsg` / `%rex`), proved against the encoder the shipped
  `logback.xml` configures rather than a hand-built one. Every configuration object that holds a
  secret overrides `toString`, because `ServeConfig` composes them and one interpolation is enough.
- A REST middleware that turns `Authorization: Bearer` into an `ActorContext` before routing and
  answers `401` when it cannot, wired into `serve` over a Hikari pool.
- Every **mutating** refusal that knows an actor is on the trail: a sign-in requested for an actor
  that may not sign in, a redemption refused for any cause, a rotation refused for any of its three
  causes — a wrong verifier against a live selector, a revoked row, a lost race — and a revocation
  of somebody else's credential. Authentication itself
  writes nothing, refusals included — it is the read path, and a write there owes the trail an
  event per request (§ 7 of the plan, and the rate-limiting package that follows it).

Not built, deliberately — each is named in [`../../docs/plan/SEC-01.md`](../../docs/plan/SEC-01.md)
§ 7 with its reason:

- **No REST or MCP endpoints.** `POST /me/credentials`, `/auth/*` and the MCP tools are API-01's and
  MCP-01's; the use cases they call exist.
- **No OIDC handshake.** The configuration is validated all-or-nothing — a partial one refuses
  start-up — and nothing reads the result: no code branches on `IdentityConfig.oidc`. The
  authorization-code exchange needs a callback route to exist. Departure from § Approach step 3,
  recorded here.
- **No delivery for the one-time code.** `SignInCodeDelivery` is a port with no implementation and
  nothing wired to it. The obvious placeholder — log the code "for now" — writes a live credential
  into the log file, which is the failure the other half of this package exists to prevent. The seam
  is finished though: `RequestSignInCode` calls it after its transaction has committed.
- **No expiry policy and no scopes on a personal access token.** The mechanism honours both — an
  expired credential is refused, and the column exists — but the default, the ceiling and the scope
  itself are the issuing route's arguments. A token with no expiry is mintable today; API-01
  supplies the terms.
- **`last_used_at` stays unwritten**, and `nodera.project_ids` is still unestablished: it is set
  from the project a request addresses, which is the surface's business rather than a credential's.
  `JdbcUnitOfWork`'s comment named SEC-01 for it and has been corrected.

## Approach

1. PAT issuance: generate, prefix `nod_pat_`, hash with Argon2id, return the plaintext **once**.
2. Verification middleware producing `ActorContext` with the correct `surface`.
3. Human sign-in: local email plus one-time code, with the OIDC path behind configuration.
4. Access JWT (15 minutes) plus a rotating opaque refresh token.
5. Redaction at the logging boundary, so a mistake upstream is still contained.

## Acceptance criteria

- [x] A PAT plaintext appears exactly once, in the creation response, and never again in any
      response, log line or error — proved by a test that inspects captured log output.
- [x] A revoked or expired credential is rejected; a test covers both.
- [x] The process refuses to start when a required secret is absent, rather than defaulting.
- [x] A token supplied as a command-line argument is rejected with a message naming why.
- [x] Both credential shapes produce an equivalent `ActorContext`, differing only in `surface` and
      the actor identified.
- [x] `make check` green.
- [x] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/domain/src/main/kotlin/ai/nodera/domain/identity/` — the token grammar, credential state,
  the sign-in code and the redaction rule.
- `backend/application/src/main/kotlin/ai/nodera/application/identity/` — ports, the verifier, the
  authenticator, sign-in, rotation, and the two use cases under `usecase/`.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/identity/` — the three JDBC adapters;
  `ConnectionPool.kt` beside them, because the serving process had no pool.
- `backend/api-rest/src/main/kotlin/.../AuthenticationPlugin.kt` — the REST middleware.
- `backend/app/src/main/kotlin/ai/nodera/app/` — `Config.kt` (fail-closed start-up), `Main.kt` (the
  command-line refusal), `Serve.kt` (the wiring), `Identity.kt` (Argon2id, `SecureRandom`, JWT),
  `Redaction.kt` plus `logback.xml`.
- `db/migrations/V7__credential_selector_and_sign_in_code.sql`.
- `scripts/verify_image.sh` — `serve` now requires the identity block, so the image-acceptance
  script has to supply it; without that every serve container it starts exits at start-up and one
  of its checks passes for the wrong reason. It also gained the positive check for the refusal.
- `compose.prod.yml`, `docs/ops/deploy.md`, `docs/ops/backup-restore.md`, `.env.example`,
  `Makefile`, `.gitleaks.toml`, `CHANGELOG.md`, `docs/plan/README.md`, `docs/docs_map.md`.
- `docs/DOMAIN_MODEL.md` (`selector` in § 8, invariants CR3–CR5, § 8.1 for `sign_in_code`) and
  `docs/ARCHITECTURE.md` (a personal access token belongs to one *actor*).
- `backend/app/build.gradle.kts` and `backend/gradle/libs.versions.toml` (`java-jwt` declared,
  Logback moved to the compile classpath), `backend/persistence/.../Binding.kt` (an `instant()`
  binder), `JdbcUnitOfWork.kt` (the RLS seam comment corrected), `db/migrations/.checksums`.
- `backend/app/src/test/kotlin/ai/nodera/app/ConfigTest.kt` and `DispatchTest.kt` — existing test
  files, extended rather than added.

## Verification

`./gradlew test`. The redaction test captures appender output and asserts the token plaintext is
absent from it.

## Notes on the record

**Two things the ticket's approach did not anticipate, both structural.**

The first is why a selector exists at all. `V1` puts a unique index on `credential.token_hash`,
which reads as the lookup key and is not one: Argon2id salts, so the same plaintext hashes
differently every time and `where token_hash = hash(presented)` matches nothing. The alternative to
a lookup key is one Argon2id evaluation per credential per request, which is a denial-of-service
surface held open from inside. `V7` adds `selector`, and its alphabet — lowercase hexadecimal — also
makes `.gitleaks.toml`'s existing promise about `nod_pat_EXAMPLE…` true by construction rather than
by anyone remembering it.

The second is that `serve` had no connection pool. Nothing needed one before, because nothing but
the readiness probe touched the database, and the probe opens a connection per call. The middleware
does, so `ConnectionPool` exists; it is deliberately lazy (`initializationFailTimeout = -1`) so a
database that is briefly unreachable leaves `/health/ready` reporting `503` rather than turning a
wait into a crash loop.

**One defect was found by a harness rather than by a test that was written for it.** The refused
path of `RevokeCredential` executed `update credential … where actor_id = ?`, matched no row, and
wrote no audit event; `:persistence`'s completeness harness watches statements rather than row
counts and refused the commit. The right fix was not to quiet the harness but to record the
refusal — invariant #3 covers attempts, and somebody addressing a credential that is not theirs is
exactly what an incident review reads first.

## Review result

Six independent rounds, each in a sub-agent that did not write the code. The package was
implemented in a session that ended before its round-1 findings were fixed; the findings themselves
were lost with it, so round 2 below is a **fresh** review of the tree rather than a re-check of a
list. What round 1 found is therefore not on this record, and cannot be — the honest statement is
that it is gone, not a reconstruction of it.

**2026-09-05 · CHANGES REQUIRED, 4 BLOCKING, 8 NON-BLOCKING (independent review, round 2 — fresh).**

| # | Finding | Fix |
|---|---|---|
| B1 | `RefreshSession` discarded the result of `credentials.revoke`, which is conditional on the row still being live. Under READ COMMITTED a `select` takes no row lock, so two callers holding one refresh token both pass verification and the loser mints a session anyway — one captured token, two live sessions. | The `null` is a refusal, recorded as a denial. Watched red with the result discarded. |
| B2 | `SignInCodeStore.consume` returned `Unit` and the adapter discarded its row count, so the same race made one code redeemable twice. Unfixable at the call site, because the signature hid it. | `consume` returns `Boolean`; a redemption that matched no row is refused. Watched red. |
| B3 | `RequestSignInCode` refused a **known** but inactive actor silently. Invariant #3 covers attempts, and the argument for writing nothing — `audit_event.actor_id` is `not null` — only ever covered the *unknown* address. | The refusal is recorded with `outcome = denied`. Watched red. |
| B4 | A comment claimed `IdentityConfig` avoids `data class` because a generated `toString` would print the signing key. False — `ByteArray.toString()` prints an identity hash. Meanwhile `ServeConfig` really did print the database password through `DatabaseConfig`, and the comment made a reader less likely to check. | Every configuration object holding a secret overrides `toString`; the claim is now true and tested. Watched red. |
| N1–N8 | Over-commenting in the upper layers; a timing oracle on code redemption; a dead doc reference; `NODERA_PUBLIC_URL` and `secrets/jwt_signing_key` missing from the ops procedures; the gitleaks allowlist not covering the new fixtures; a vacuous assertion; delivery inside the transaction; a drifted file list in the plan. | All eight fixed. The gitleaks one was **verified by running the scanner**, not reasoned about: it found four real leaks, which is a red CI lane, and is clean after the allowlist entries. |

**2026-09-05 · CHANGES REQUIRED, 3 BLOCKING, 6 NON-BLOCKING (independent review, round 3).** Round 3
re-derived rather than re-checked, and two of its three findings are in round 2's own fixes.

| # | Finding | Fix |
|---|---|---|
| B1 | `scripts/verify_image.sh` starts `serve` with the database block only, so every serve container exits at start-up once this package makes the identity block required — and "readiness refuses while migrations are pending" then passed *for the wrong reason*, satisfied by a dead container. The compose file and both ops documents were updated; the acceptance script was not. | Both containers carry the identity variables, and a positive check was added: the image must refuse to start naming `NODERA_JWT_SIGNING_KEY`. |
| B2 | `absorb` — round 2's own fix for the redemption timing oracle — levelled one branch out of four and **made the oracle easier to exploit**: a live code exists only for minutes, so the common state of a registered address is a branch that hashed nothing, and the fix put the expensive path on the unregistered side. | Every path through a redemption now costs exactly one verification, pinned by a test that walks all six. Watched red with each `absorb` removed. |
| B3 | `CredentialVerification.Refused.owner` was never populated while its KDoc stated the opposite, and the consequence was concrete: the **ordinary** sequential replay of a refresh token wrote no audit event, while the far rarer race did. The completeness harness cannot see it — that path executes no mutation. | The owner is carried and the refusal recorded. Watched red both ways. |
| N1 | A refusal named its cause to the caller — `EXPIRED` for a code that ran out — which answers "is this address registered?" without a clock. | Every refusal is answered `UNKNOWN`; the trail records the real cause. Watched red. |
| N2–N6 | This section absent; two `.env.example` variables validated but not honoured; three files still over-commented; a guard comment naming a mechanism the test does not pin; the supersede comment crediting the wrong mechanism. | All fixed. The guard comment is the interesting one: removing `finish()` alone does **not** go red on this Ktor version, so the comment now names the branch the test does pin and says so. |

**2026-09-05 · CHANGES REQUIRED, 1 BLOCKING, 5 NON-BLOCKING (independent review, round 4).**

| # | Finding | Fix |
|---|---|---|
| B1 | **The credential's kind was taken from the presented prefix and never from the row.** A selector is unique across the whole table, so `nod_ref_…` re-typed as `nod_pat_…` addresses the same row — and both kind gates, round 2's refresh-token refusal and rotation's mirror of it, read `token.kind`. Three edited characters turned a captured refresh token back into a thirty-day bearer credential, and turned a personal access token into something rotation would convert into a session. Every existing negative presented its token unmodified, so all of them passed with the gate wide open. | `CredentialVerifier` compares the row's kind with the presented one and refuses as `UNKNOWN`, in the one place both callers already pass through; the two prefix gates become defence in depth. Watched red in both directions. |
| N1–N5 | The migration's table comment still credited supersession — round 3's N6 with its sibling left standing, and the copy in the database is the one a reader believes; the plan named a guard symbol that does not exist and its file list was still drifted; a sort-key comment justified itself with a case the code cannot produce; two residual comment duplications. | All fixed. `V7` is uncommitted and has been applied nowhere, so its checksum was re-recorded with `scripts/lint_sql.py --accept`; the ledger's only change against `HEAD` is V7's own line. |

Round 4's finding is the one worth reading twice: **two of the three blocking findings in round 3,
and the one in round 4, were defects introduced by the previous round's fixes.** A guard added under
review pressure is not a guard until something has been watched failing without it, and B1 is the
sharpest form of that — the guard existed, had a test, and tested the attacker's input.

**2026-09-05 · CHANGES REQUIRED, 1 BLOCKING, 5 NON-BLOCKING (independent review, round 5).**

| # | Finding | Fix |
|---|---|---|
| B1 | **The sign-in code's attempt limit was decided by the read, not the write** — the third instance of the shape rounds 2 and 3 each called BLOCKING, in the one place the fix had not been applied. `mostRecent` takes no row lock, the Argon2id verification sits between that read and the counter's `update`, and `recordFailedAttempt` was unconditional with its row count discarded at both layers. Every caller arriving inside that window saw a live code, so N concurrent redemptions bought N guesses against eight digits rather than five — and the row afterwards read `attempts = N`, so the counter looked correct while the guesses had been answered. The existing limit test is sequential and green with the hole open. | A guess is **claimed before it is spent**: `claimAttempt` is `update … where attempts < n` returning whether it matched, and a claim that matches nothing is refused. Watched red against a store whose `mostRecent` hands back the snapshot from before the concurrent guesses committed. |
| N1 | "Refusals are on the trail wherever an actor is known" was true of the mutating paths and not of authentication, which is the package's main path and deliberately writes nothing. Two copies of the sentence. | Both qualified to the mutating refusals, naming where the read-path decision lands. |
| N2 | `docs/DOMAIN_MODEL.md` § 8 still described `credential` without `selector` and had no `sign_in_code` at all, so the domain model still read as though the Argon2id hash were the lookup key — the misconception the whole structural decision exists to remove. `docs/ARCHITECTURE.md` said a personal access token belongs to "one agent actor", which this package's own persistence test disproves. | § 8 gains `selector`, invariants CR3–CR5 and an § 8.1 for `sign_in_code`; the architecture line now says one *actor*. |
| N3–N5 | A count in the plan left stale by round 4's own addition; residual duplicated rationale; `CredentialLabel` validated the trimmed length and stored the untrimmed value, so the bound it appears to establish was not the bound. | All fixed. |

**2026-09-05 · APPROVED, 0 BLOCKING, 6 NON-BLOCKING (independent review, round 6).** The first
round to reach zero. All six fixed in session.

| # | Finding | Fix |
|---|---|---|
| N1 | `DOMAIN_MODEL.md` CR4 ended "Both gates read `credential.kind`" — they read the presented prefix, and it is `CredentialVerifier`'s comparison that makes the row decide. A reader trusting the domain model had a reason to delete the one line round 4 was about, and every unmodified-token negative would have stayed green. | Both the invariant and the sibling comment in `RefreshSession` now name the mechanism that actually holds. |
| N2 | `SignInCodes.redeem`'s KDoc claimed the claim-before-spend fix also stopped the counter being reset by asking again. It does not — reissue buys five fresh guesses. | The KDoc says that outright, and points at [`../../docs/plan/SEC-01.md`](../../docs/plan/SEC-01.md) § 9.3 for the per-actor budget that would bound it. |
| N3 | Four of six redemption refusals reached the trail as `reason: "unknown"`, because the caller-facing answer and the audited cause were the same value. An incident review could not tell a wrong guess from a burned-through attempt limit — the event round 5's fix exists to make detectable. | `RedemptionFailure` is the trail's vocabulary and `RejectionReason.UNKNOWN` stays the caller's. Watched red. |
| N4 | The seven-path timing property was pinned by a six-path test; the claim-failure branch this round's predecessor added was the missing one, and it is the only path a plain `World` cannot reach. | The race spec now asserts the verification count and the audited cause too. |
| N5 | The read-and-write-window rationale stood in five places. | Two. |
| N6 | The refusal called itself RFC 9457 and went out as `application/json`; RFC 9457 defines `application/problem+json`, and the contract's own example carries an `instance` the body omitted. API-01 will copy this file. | Serialised with the media type and the `instance` the contract shows. Both watched red. |

**Twenty-eight paired negatives were watched failing on the tree as it now stands**, mechanically:
each guard disabled in a copy of the repository, the spec it belongs to run, the file restored, the
failing test named. Earlier rounds' counts are their own; this list was re-established rather than
inherited. It covers the redaction conversion rules, the revoked and expired branches at the domain
rule and again through the authenticator, the signing key's presence and its length floor, the
Argon2id cost floor, the configuration `toString` overrides, the command-line refusal, the row-kind
comparison from both callers, the refresh-token refusal, the rotation and consume race checks, the
attempt claim, all three `absorb` calls, the uniform refusal reason, the audited cause, the
request-refusal audit, the replay audit's owner on the wrong-verifier branch, supersession, the
revocation's ownership condition against Postgres, the plugin's refusal branch, the problem
document's media type and `instance`, the token alphabet, and the secret types' `toString`.

**Two guards were watched *staying green*, and that is a result rather than a gap.** Removing the
`CONSUMED` and `EXHAUSTED` branches leaves their specs passing, because `claimAttempt`'s
`consumed_at is null and attempts < ?` refuses first. Both are defence in depth, and the comments
that used to claim otherwise now say which half their spec is red for. A negative that stays green
is how the claim was found; the harness's own first run reported every guard red because it never
reached Gradle, which is the same lesson one layer down.

Round 3's two design questions were both answered in its favour, and round 4's finding is
recorded beside them, in [`../../docs/plan/SEC-01.md`](../../docs/plan/SEC-01.md) § 6.1 and § 9.4.

**2026-09-05 · CHANGES REQUIRED, 1 BLOCKING, 9 NON-BLOCKING (independent review, round 7 — fresh,
over the whole tree).** The round 6 fixes had never been re-reviewed; this is the confirming round
that was owed, run as a fresh review rather than a re-check of a list.

| # | Finding | Fix |
|---|---|---|
| B1 | **A guard comment naming a paired negative that does not exist.** `SignInTest`'s supersession spec claimed to be red without `SignInCodeStore.issue`'s supersession; it is not. Redemption reads `mostRecent` only, so the second code's row answers and the first is refused as a wrong guess whether or not the first row was consumed — the assertion never touched it. The comment also credited supersession with bounding redeemability, which `V7`'s table comment and `JdbcSignInCodeStore`'s own comment correctly say is `limit 1`'s doing: round 3's N6 corrected in two homes of three. | The spec now asserts that exactly one row is live after a second request, which is what supersession buys. **Watched: green with supersession, red without it** — and watched STAYED-GREEN beforehand, which is what made the finding a fact rather than a reading. The comment now names the mechanism that holds. |
| N1 | Two more guard comments said "remove either" while pinning one of the two. Dropping the `CONSUMED` and `EXHAUSTED` branches leaves both specs green, because `claimAttempt`'s `consumed_at is null and attempts < ?` refuses first. Both **watched STAYED-GREEN**. | Each comment names the half its spec is red for, and says the other is defence in depth. |
| N2 | § 9.4 called timing "the two unauthenticated paths"; there are three. Requesting a code costs a hash and three statements for a known address and one indexed select for an unknown one — a wider gap than the one the section argues about. | Recorded as the third path with its residual gap and the same recommendation, rather than papered over with a decoy that would narrow it without closing it. |
| N3–N6 | The plan's and the ticket's file lists still omitted `docs/ARCHITECTURE.md` and `docs/DOMAIN_MODEL.md` (the third recurrence of a drifted file list); § 6.1 still said "five things" with round 5's sixth guard missing; `backup-restore.md` still said a complete backup is "three things" after gaining a third secret; `.env.example` and this ticket claimed the OIDC path is "selected" when nothing reads `IdentityConfig.oidc`. | All fixed. |
| N7 | Authentication opened a transaction around the parse, and `JdbcUnitOfWork` takes a pooled connection eagerly — so `Authorization: Bearer garbage`, which reads no table, checked out one of ten connections and committed. | Parsing and access-token verification happen outside; the transaction opens only on the branches that read a row, in `CredentialAuthenticator` and in `RefreshSession` alike. |
| N8–N9 | Over-commenting in five files, and the read-before-write rationale back in five places after round 5 reduced it to two. | Trimmed to the pointer plus the one statement of the rule. |
| C1 | Found while re-establishing the negatives, not by a reviewer: `CredentialVerification.Refused.owner`'s KDoc names the wrong-verifier branch, and **no spec covered it**. Dropping the owner there left every test green while a guess against a real selector stopped reaching the trail — the most diagnostic event the rotation path sees. | A spec in `RefreshSessionTest` presents a wrong verifier against a live selector and asserts the denial names the owner. Watched red. |
| C2 | Also found here: `compose.prod.yml`'s own usage block still created two secrets and one `.env` variable. Following it verbatim produced a compose file that refuses to interpolate — the sibling of the `backup-restore.md` count. | Both added to the block. |

**2026-09-05 · APPROVED, 0 BLOCKING, 9 NON-BLOCKING (independent review, round 7b — the written
record against the code).** Run in parallel with round 7 and scoped to the half that five rounds
kept failing: every claim this package makes in prose, checked against what the code does. It found
the OIDC "the path is selected" claim independently, while round 7's fix for it was being written.

| # | Finding | Fix |
|---|---|---|
| N1 | § 7 told API-01's author that `docs/API_CONTRACT.md` "already specifies" `/auth/*` and `/me/credentials`. It specifies `/me/credentials`; there is no `/auth/*` contract at all. | § 7 says which exists and which API-01 has to write. |
| N2 | `.env.example` scoped "validated but not honoured yet" to two TTLs. It is true of `NODERA_ACCESS_TOKEN_TTL` too: it reaches `JwtAccessTokens`, whose `issue` is called only by `SessionIssuer`, which `serve` never constructs. | The caveat covers all three. |
| N3–N4 | A KDoc cross-reference that resolved to one of the two claims it covered; § 6's "each one" over a table of the five testable criteria. | Both narrowed to what is true. |
| N5 | `docs/ARCHITECTURE.md` § 5 — the section a reader reaches first — still said humans "sign in via OIDC, or local email + one-time code where no provider is configured". The same claim as the OIDC one, in the place its fix had not reached, and its PAT paragraph still read as though the hash were the lookup key. | § 5 describes what is built and points at CR3. |
| N6 | `ConfigTest.kt` and `DispatchTest.kt` in neither file list: the plan's "tests beside each of the above" sits under **New** and does not cover a changed test. The fourth recurrence of the drifted-file-list shape. | Both listed, in both places. |
| N7–N8 | A comment recording why a previous comment was wrong; three more restatements, one of them the token-alphabet argument standing in nine places. | Trimmed. The alphabet argument keeps one Kotlin home — CR3 — with `Token.kt` pointing at it; the copies in `V7`, `.gitleaks.toml`, the plan, the changelog and this ticket stay, because each addresses a different reader. |
| N9 | Round 7's own N7 fix was described as done and was done in `CredentialAuthenticator` only — `RefreshSession` still opened a transaction around the parse. | The same shape in both, and the row above says so. |

Round 7b's verdict came with a caveat worth keeping: **it read a tree that was being edited under
it**, because round 7's fixes were landing while it worked. It said plainly that a verdict on a
moving tree is not a verdict for closure. The confirming round below was run on a frozen one, which
is the step this package never reached before.

**2026-09-05 · APPROVED, 0 BLOCKING, 7 NON-BLOCKING (independent review, round 7c — the confirming
re-review, on a frozen tree).** The rule rounds 3, 4 and 5 each taught is that a fix made under
review pressure is the most dangerous code in the change, and rounds 7 and 7b left twenty-three
fixes nobody had read. This round reviewed them, and the whole package again from the charter.

| # | Finding | Fix |
|---|---|---|
| N1–N2 | § Current state still read "round 7 in progress" and would have carried that into the closed ticket; the backend figures were one spec stale, quoting the run that predated round 7's own C1 test. | Both rewritten against the tree as committed. |
| N3 | § 5 of `docs/ARCHITECTURE.md` still said a personal access token "carries its own scopes". The column exists from `V1`; nothing in this package reads or writes it, so every token minted here carries an empty array the section says constrains it. | The sentence says where a scope would be chosen instead. |
| N4 | **A personal access token with no expiry is mintable**, and `skills/secure-coding.md` — a skill this ticket routes to — says a credential that never expires is one nobody will rotate. Every other departure is recorded in § 7; this one was recorded nowhere. | Recorded as a non-goal with its reason: the mechanism refuses an expired credential and is watched doing it, but the default and the ceiling are the issuing route's arguments. |
| N5–N7 | A loose list item in `CHANGELOG.md`; round 7b's own N7–N8 row claiming a larger reduction than was made; and a § Current state sentence naming two of the three audited rotation refusals after round 7's C1 added the third. | All three narrowed to what is true. |

Round 7c raised one thing it could not check and named it rather than assuming: it read the Gradle
test-result XMLs on disk and confirmed they post-date every source file, but a cache restore writes
those files too, so it could not confirm the run was `--rerun-tasks`. The run above was; the two
statements are independent, which is why both are here.
