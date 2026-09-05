# Plan — SEC-01 · Credential issuance and authentication for humans and agents

**Status:** `implemented`
**Ticket:** [`../../tickets/closed/SEC-01.md`](../../tickets/closed/SEC-01.md)
**Invariants this implements:** #6 (credentials fail closed and are never readable) and the
authentication half of #1 (one code path, two credential shapes, no branch on actor kind) —
[`../../skills/critical-invariants.md`](../../skills/critical-invariants.md)

---

## 1. What phase 1 found

`V1` carries the `credential` table with `token_hash`, `label`, `scopes`, `expires_at`,
`last_used_at` and `revoked_at`. Nothing above it exists: no issuance, no hashing, no verification,
no middleware. `.env.example` declares `NODERA_JWT_*` and `NODERA_TOKEN_HASH_*` and says in as many
words that nothing reads them yet.

Five findings bind this package rather than merely informing it.

1. **`credential` cannot be looked up by its Argon2id hash.** `V1` puts a unique index on
   `token_hash`, which reads as the lookup key and is not one: Argon2id is salted, so the same
   plaintext hashes differently every time and `where token_hash = hash(presented)` matches nothing.
   The only alternative to a lookup key is verifying the presented secret against every live row,
   which is one Argon2id evaluation per credential in the deployment, per request. § 3 is the
   structural decision this forces.
2. **`audit_event.actor_id` is `not null`.** So a mutation can only be audited once an actor is
   known. That decides the shape of the sign-in path: an e-mail that matches no actor performs no
   mutation at all, which is also what keeps it from answering the enumeration question.
3. **`scripts/lint_invariants.py` requires `ctx: ActorContext` as the first parameter of every
   `public fun` under `application/**/usecase/`.** Authentication *produces* the context and cannot
   take one. So the authenticator sits **beside** `usecase/`, exactly where `PermissionService` sits
   and for the same reason. § 4.
4. **`.gitleaks.toml` already promises something about the token format.** Its allowlist admits
   `nod_pat_EXAMPLE[A-Za-z0-9]*` with the comment *"the token issuer refuses to mint it"*. That is a
   commitment made before the issuer existed, and this package is the first that can honour or break
   it. § 3 makes it structural rather than a rule someone has to remember.
5. **`JdbcUnitOfWork` names the RLS seam as SEC-01's.** It is not: `nodera.project_ids` is set from
   the *project* an authenticated request addresses, and a credential identifies an actor, not a
   project. Authentication reads `credential`, `actor` and `human_actor`, none of which carry RLS
   (`V4` says so and gives the reason). The seam moves to the surface that resolves a project from
   the request — API-01 — and § 9 says so rather than leaving the comment pointing here.

## 2. What this package is, in one sentence

One `CredentialAuthenticator` turns either credential shape into an `ActorContext`, and everything
else here exists so that the secret behind each shape cannot be read back, cannot outlive its
revocation, and cannot reach a log line.

## 3. The structural decision: selector and verifier

A token is **two parts**, and only the second is a secret:

```
nod_pat_ 6f1c9a4b2e8d70a3c5f2b1e4 _ 9c2e…64 lowercase hex characters…
         └── selector: the lookup key ──┘   └── verifier: hashed, never stored in the clear ──┘
```

- The **selector** is 12 random bytes, stored in the clear in a new `credential.selector` column
  with a unique index. It is what the `where` clause matches, and it carries no authority: knowing
  it lets an attacker address a row, not use it.
- The **verifier** is 32 random bytes. Only its Argon2id hash is stored (invariant CR1), and the
  presented value is checked with the library's own constant-time verify.

Rejected alternatives, with the reason:

| Alternative | Why not |
|---|---|
| Look up by `token_hash` | Argon2id is salted. This does not work at all, and the unique index in `V1` makes it look as though it does. |
| Verify against every live credential | One Argon2id evaluation per credential per request. At the 64 MiB default cost this is a denial-of-service surface with the door held open from inside. |
| A fast unsalted hash (SHA-256) as the lookup key | Would work, and would make `token_hash` a value an attacker with a read of the table can attack offline with a rainbow table. Invariant CR1 says Argon2id; a second, weaker hash of the same secret beside it gives back exactly what Argon2id was chosen to remove. |
| Put the actor id in the token | The token then leaks who it belongs to, and a revoked credential's id is a stable identifier an attacker can correlate. The selector is random and means nothing outside the table. |

**The alphabet is lowercase hexadecimal, and that is load-bearing twice.** It has no `_`, so
splitting the token on `_` is unambiguous; and it cannot spell `EXAMPLE`, so the documented example
token `nod_pat_EXAMPLE…` that `.gitleaks.toml` allowlists is **unmintable and unparseable** rather
than merely unlikely. Finding 4 becomes a property of the grammar, with a test that pins it.

`V7` therefore adds `selector` as `not null` in one step rather than nullable-then-backfill. The
expand/contract discipline exists for tables with rows in them; nothing in this repository has ever
written a `credential` row, so the alternative to failing loudly on one is admitting a credential
that can never be authenticated. Stated in the migration itself.

## 4. Module placement, and the one thing that surprised the layout

| What | Where | Why there |
|---|---|---|
| Token grammar, credential state, the redaction rule | `:domain/identity/` | Pure rules about this repository's own token format. Framework-free, so the paired negatives run without a container. |
| Ports, `CredentialAuthenticator`, sign-in, refresh | `:application/identity/` | The authorisation path. One object, called by both surfaces. |
| `IssuePersonalAccessToken`, `RevokeCredential` | `:application/identity/usecase/` | Genuine use cases: an actor issues a credential, and `ctx` is its first parameter. |
| JDBC repositories | `:persistence/identity/` | |
| `AuthenticationPlugin` | `:api-rest/` | Translation only: header in, `ActorContext` on the call, `401` out. It decides nothing. |
| Argon2id, `SecureRandom`, JWT signing, the logging converters | `:app/` | Adapter implementations of ports declared in `:application`. `libs.argon2` was already declared on `:app` and unused — the layout had this seat reserved. |

**The surprise:** `CredentialAuthenticator`, `SignIn` and `RefreshSession` are *not* under
`usecase/`. They cannot take an `ActorContext` first, because producing one is what they are for.
Putting them there would either break `lint_invariants.py` or invite someone to relax it, and the
rule it enforces is worth more than the tidiness of one directory. They sit beside it, like
`PermissionService`.

## 5. Files

**New**

- `backend/domain/src/main/kotlin/ai/nodera/domain/identity/Credential.kt` — `CredentialId`,
  `CredentialKind`, `CredentialLabel`, `SecretHash`, `Credential`, `CredentialState`.
- `.../identity/Token.kt` — the token grammar: render, parse, `CredentialSelector` and `TokenSecret`
  with redacting `toString()`.
- `.../identity/SignIn.kt` — `SignInCode`, its record and its state rule.
- `.../identity/SecretRedaction.kt` — the pure rule the logging boundary applies.
- `backend/application/src/main/kotlin/ai/nodera/application/identity/IdentityPorts.kt`,
  `IdentityResults.kt`, `CredentialVerifier.kt`, `CredentialAuthenticator.kt`, `SignInCodes.kt`,
  `SignIn.kt`, `SessionIssuer.kt`, `RefreshSession.kt`, `usecase/IssuePersonalAccessToken.kt`,
  `usecase/RevokeCredential.kt`.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/identity/` — `JdbcCredentialStore.kt`,
  `JdbcSignInCodeStore.kt`, `JdbcActorDirectory.kt`, `IdentityRows.kt`; and `ConnectionPool.kt`
  beside them, because the serving process had no pool (§ 1 is silent on this — it surfaced during
  implementation, and the ticket's § Notes on the record carries the reasoning).
- `backend/api-rest/src/main/kotlin/ai/nodera/api/rest/AuthenticationPlugin.kt`.
- `backend/app/src/main/kotlin/ai/nodera/app/Identity.kt` — `Argon2idSecretHasher`,
  `SecureRandomSecrets`, `JwtAccessTokens`.
- `backend/app/src/main/kotlin/ai/nodera/app/Redaction.kt` — the two Logback converters.
- `db/migrations/V7__credential_selector_and_sign_in_code.sql`.
- Tests beside each of the above.

**Changed**

- `backend/app/.../Config.kt` — `IdentityConfig`, fail-closed validation, the OIDC all-or-nothing
  rule.
- `backend/app/.../Main.kt` — the command-line credential refusal.
- `backend/app/.../Serve.kt` — wire the identity graph, install the plugin.
- `backend/app/src/main/resources/logback.xml` — the redacting converters.
- `backend/app/build.gradle.kts`, `backend/gradle/libs.versions.toml` — `java-jwt` declared
  explicitly (§ 8).
- `backend/persistence/.../Binding.kt` — an `instant()` binder; `JdbcUnitOfWork.kt` — the RLS
  seam comment corrected (§ 9.2).
- `backend/app/src/test/kotlin/ai/nodera/app/ConfigTest.kt` and `DispatchTest.kt` — the identity
  half of the fail-closed suite, and the command-line refusal. Existing files, so they are here
  rather than under **New**.
- `.env.example`, `CHANGELOG.md`, `Makefile` (development values for the two new required
  variables), `docs/plan/README.md`, `docs/docs_map.md`.
- `docs/DOMAIN_MODEL.md` — `selector` in § 8, invariants CR3–CR5, and § 8.1 for `sign_in_code`;
  `docs/ARCHITECTURE.md` — a personal access token belongs to one *actor*, which this package's own
  persistence test disproves for "one agent actor".
- `.gitleaks.toml` — the token grammar's test vectors, named one by one rather than exempting the
  test tree by path.
- `scripts/verify_image.sh`, `compose.prod.yml`, `docs/ops/deploy.md`, `docs/ops/backup-restore.md`
  — `serve` now requires the identity block, so every place that starts it has to supply one. The
  acceptance script is the one that bites: without it each serve container exits at start-up and a
  readiness check passes for the wrong reason.
- `db/migrations/.checksums` — V7's line.

## 6. Acceptance criteria, and the guard each one is paired against

The ticket's seven are the contract. This is what each of the five testable ones is *proved* by, and
what has to be removed for the proof to go red — the report says which of these were watched. The
remaining two, a green `make check` and a review with no blocking findings, are the closure
protocol's rather than a test's.

| Criterion | Test | Guard removed to watch it fail |
|---|---|---|
| Plaintext appears exactly once | `RedactionBoundaryTest` — encode a real log event through the configured Logback encoder | the `%rmsg`/`%rex` conversion rules in `logback.xml` |
| Revoked credential rejected | `CredentialAuthenticatorTest` | the `revokedAt` branch in `CredentialState` |
| Expired credential rejected | `CredentialAuthenticatorTest` | the `expiresAt` branch in `CredentialState` |
| Refuses to start without a required secret | `ConfigTest` | `Environment.required`'s throw, and each identity-specific validation |
| Token as a command-line argument rejected | `DispatchTest` | the `credentialArgument` check at the top of `dispatch` |
| Equivalent `ActorContext` from both shapes | `ActorContextEquivalenceTest` — one actor, both shapes, same surface | — it is an equality assertion, not a guard; it goes red on any divergence |

Additionally, and not in the ticket because it is a property of the format: a test asserts that the
documented example token `nod_pat_EXAMPLE…` neither parses nor can be minted, which is the promise
`.gitleaks.toml` already makes on this repository's behalf (§ 1, finding 4).

### 6.1 What review added, and why each one is a guard rather than a preference

Six things the first pass got wrong were structural rather than local, and each is recorded here
because the shape it corrects is one the next package can repeat.

| Guard | The failure without it | Watched by |
|---|---|---|
| A conditional write's **result** decides, not the read before it | `revoke` and `consume` are conditional on the row still being live, and both were called as bare statements. Under READ COMMITTED a `select` takes no row lock, so two callers holding one refresh token — or one sign-in code — both pass verification, and the loser mints a second session anyway | a store that has already performed the write, so the call under test sees exactly what the loser of a race sees |
| A **known** actor's refusal is audited | `RequestSignInCode` skipped a suspended actor silently. Invariant #3 covers attempts, and the argument for writing nothing — `audit_event.actor_id` is `not null` — only ever covered the *unknown* address | one assertion on the outcome column |
| A refresh token is **not** a bearer credential | Accepting it meant a captured one served requests for thirty days without ever rotating, so the rotation that detects the replay never happened and the fifteen-minute access token protected nothing. § 3 gives sessions and personal access tokens one grammar deliberately; one grammar is not one purpose | a spec that refuses it at authentication and rotates it in the next statement |
| The **row's** kind decides, never the presented prefix | The first version of the guard above read `token.kind`, which comes from `CredentialKind.byPrefix` and therefore from the caller. A selector is unique across the whole table, so `nod_ref_…` re-typed as `nod_pat_…` addresses the same row with a different claim about what it is — and the guard, and its mirror in rotation, are three characters wide. § 3 chose one grammar for two kinds; that choice is what makes the row the only place the kind can be read | two specs that re-prefix a live token in each direction and assert the refusal, and that rotation minted nothing |
| Configuration objects that hold a secret override `toString` | `ServeConfig` is a `data class` composing `DatabaseConfig`, so one `"$config"` printed the database password. The comment claiming otherwise made a reader *less* likely to check | one assertion over the printed object, red if any override is removed |
| A guess is **claimed before it is spent** | The attempt limit was judged from a row read one Argon2id evaluation before the counter's write, and the write was unconditional with its count discarded. Every caller arriving inside that window saw a live code, so N concurrent redemptions bought N guesses against eight digits rather than five — and the row afterwards read `attempts = N`, so the counter looked correct while the guesses had been answered | `AlreadyExhausted`, a store whose `mostRecent` hands back the snapshot from before the concurrent guesses committed |

## 7. Deliberate non-goals

Named because each one looks close enough to reach for, and the ticket's edge is where this package
stops.

- **No REST endpoints.** No `/auth/*`, no `POST /me/credentials`. The plugin resolves a credential
  and the use cases exist; wiring routes to them is API-01. `docs/API_CONTRACT.md` specifies
  `/me/credentials`; there is no `/auth/*` contract yet, and writing one is API-01's first job.
- **No MCP surface.** Same reason, MCP-01.
- **No OIDC handshake.** The ticket puts the OIDC path "behind configuration", and that is what is
  built: the three variables are validated all-or-nothing, and a partial configuration refuses
  start-up. The authorization-code exchange needs a redirect endpoint and a callback route to exist,
  which is API-01's surface. Departure from the ticket's step 3, recorded in the ticket.
- **No one-time-code delivery.** `SignInCodeDelivery` is a port with no implementation in this
  package and nothing wired to it. The alternative — a "log the code for now" adapter — writes a
  live credential into the log file, which is the exact failure the redaction half of this package
  exists to prevent. The *seam* is finished even though the adapter is not: `RequestSignInCode`
  calls it after its transaction has committed, so the package that writes the adapter does not
  inherit an SMTP round trip inside a database transaction.
- **No expiry policy on a personal access token, and no scopes.** `CredentialTerms.expiresAt` is
  nullable and `IssuePersonalAccessToken` mints whatever it is handed, so a token with no expiry is
  mintable today. `skills/secure-coding.md` says a credential that never expires is one nobody will
  rotate, and honouring that needs a default and a ceiling — both of which are arguments of the
  route that issues the token, not of the use case beneath it. The mechanism is complete:
  `CredentialState` refuses an expired credential and `CredentialAuthenticatorTest` watches it.
  API-01 supplies the terms. `credential.scopes` is in the same position: the column exists from
  `V1`, nothing in this package reads or writes it, and a scope is chosen where a token is asked
  for. Recorded here rather than defaulted at a layer that cannot see the policy.
- **`last_used_at` stays unwritten.** Updating it on every authenticated request is a write on the
  read path, and under invariant #3 a write is a mutation that owes the trail exactly one event.
  Auditing every API call is a different decision; it belongs with rate limiting, which
  `skills/secure-coding.md` scopes per actor.
- **No `nodera.project_ids` establishment** (§ 1, finding 5).
- **No permission check inside authentication.** Authentication answers *who*; `PermissionService`
  answers *may they*, per project, and it already exists. Nothing here re-implements it.

## 8. `java-jwt`, declared rather than inherited

`com.auth0:java-jwt:4.6.0` is already on the compile classpath of `:app` and `:api-rest`,
transitively through `ktor-server-auth-jwt` in the `ktor-server` bundle. Importing it while it is
transitive means the day the bundle drops the artifact, the failure is a compile error in a file
that never asked for it. It goes into `libs.versions.toml` pinned to the version resolution already
produces, so the catalogue keeps its promise that a version appears once.

It is not a new supply-chain surface — it is on the classpath either way. Making it explicit is what
`skills/secure-coding.md` § Dependencies asks for: a dependency is a decision with a reason.

## 9. Open questions, each with a recommendation

1. **The `credential.token_hash` unique index.** It survives `V7` and now guards nothing useful:
   two Argon2id hashes of the same secret differ, so it can never collide in practice, and the
   lookup goes through `selector`. *Recommendation:* leave it. Dropping an index is a change with no
   benefit and one more migration, and a unique constraint that never fires is harmless.
2. **The RLS seam comment in `JdbcUnitOfWork`.** It names SEC-01 as the package that will establish
   `nodera.project_ids`, and § 1 finding 5 says that was misfiled. *Recommendation:* correct the
   comment here — it is one sentence in a file this package already opens — and let API-01 do the
   work. Filing a ticket to fix a sentence would fail the ticket test in
   `docs/PROJECT_MANAGEMENT.md` § 8.
3. **Rate limiting on authentication.** A PAT is 32 random bytes and is not guessable; the sign-in
   code is eight digits and is. The code therefore carries an attempt counter and a short expiry in
   `V7`. Per-actor request budgets are a separate mechanism. *Recommendation:* the attempt counter
   here, the budgets as their own package with the REST surface that can return `429`.
4. **Timing, on the three unauthenticated paths.** `CredentialVerifier` returns before hashing when
   a selector addresses nothing, and argues it: a selector is 96 random bits, so there is nothing to
   enumerate, and equalising would hand a 64 MiB amplifier to whoever sends unrecognised tokens.
   That argument does **not** carry over to redeeming a sign-in code, because the input there is an
   e-mail address and an address is enumerable — so `RedeemSignInCode` spends one verification on an
   address that resolves to nobody, and the amplification it adds is bounded by what any valid
   address already costs.

   The third path is **requesting** a code, and it is not levelled. A known address costs one hash
   plus a supersede, an insert and an audit insert; an unknown one costs a single indexed select and
   returns. A decoy hash would narrow that gap and could not close it, because the writes are the
   larger half — so equalising here would buy an appearance of safety rather than the property.
   What closes it is a budget per address, not a decoy.

   *Recommendation:* as built, all three. Reopen with a measured budget, which is the rate-limiting
   package's business rather than this one's — and that package inherits the request path as its
   first case.

5. **`sign_in_code` has no retention path.** `V7` grants `delete` and nothing deletes: rows
   accumulate per actor for the life of the deployment, and `MOST_RECENT`'s `order by created_at
   desc` is served by an index on `(actor_id)` alone, so every redemption sorts them.
   *Recommendation:* leave both. A consumed code is an Argon2id hash of eight digits that is refused
   by `consumed_at` whatever it says, so the rows are not a disclosure; the sort is over a handful
   of rows per actor and changing an index without a measurement is a guess. The pair belongs
   together in one package with a measured budget — the same one § 9.4 hands the request path to —
   because a retention job and a per-actor budget are the same conversation.
