# Plan — API-01 · REST API skeleton with a contract-first OpenAPI document

**Status:** `implemented`
**Ticket:** [`../../tickets/closed/API-01.md`](../../tickets/closed/API-01.md)
**Invariants this implements:** #11 (contract-first, generated types) and the adapter half of #2 and
#5 — a route translates and decides nothing —
[`../../skills/critical-invariants.md`](../../skills/critical-invariants.md)

---

## 1. What phase 1 found

Eight findings, and five of them bind the package rather than merely inform it.

1. **`docs/API_CONTRACT.md` already exists**, contrary to the ticket's "Current state". OPS-01 wrote
   the health section and CORE-02 the `RequestId` shape; it is linked from
   [`../INDEX.md`](../INDEX.md). What it does **not** carry is `/auth/*` — the gap
   [`../../tickets/INDEX.md`](../../tickets/INDEX.md) names explicitly and this package's first job.

2. **The project-scoped half of the contract cannot be served yet, and the reason is a schema
   decision.** `PermissionService` needs a `PermissionDirectory`, which has no implementation;
   `JdbcUnitOfWork` establishes no `nodera.project_ids`; and establishing it needs the acting
   actor's memberships, which `V4`'s `project_membership_visible` policy makes invisible until the
   project scope is already set. The bootstrap is circular, and breaking it means a new policy (an
   actor may always read its own memberships, keyed on a session setting of its own) or a
   `security definer` resolver. Either is an RLS decision with a security surface, recorded as an
   ADR, and it is not an API package's to take. § 7 files it.

3. **The obvious escape is the violation itself.** Setting `nodera.project_ids` from the `{key}` in
   the path is what invariant #5 forbids in as many words — "never from a request parameter, a
   header, a path segment the client controls" — restated in
   [`../../skills/secure-coding.md`](../../skills/secure-coding.md) § Scoping. A permission check
   afterwards does not repair it: the scope would already have come from the caller.

4. **`RequestSignInCode` has no delivery adapter.** SEC-01 shipped none on purpose
   ([`SEC-01.md`](SEC-01.md) § 7) — a "log the code for now" adapter writes a live credential into
   the log file. The route cannot be constructed, so it is specified and not served.

5. **`RedeemSignInCode` spends one Argon2id evaluation on every path**, including addresses that
   resolve to nobody — deliberately, to level the clock ([`SEC-01.md`](SEC-01.md) § 9.4). At the
   shipped default cost that is 64 MiB per unauthenticated request, and no request budget exists;
   SEC-01 § 9.3 assigns budgets to "the REST surface that can return `429`". Mounting it before that
   surface exists publishes an amplifier. Specified, not served, and filed with the budget it needs.

6. **No port reads an actor's handle or display name.** `ActorDirectory` answers with
   `ActorPrincipal` — id, kind, status — which is everything *authentication* needs and less than
   the actor envelope of `API_CONTRACT.md` § 2 requires. `/me` therefore needs one narrow read port
   and one adapter.

7. **`frontend/scripts/generate-zod.mjs` throws on any construct it does not cover** — objects,
   strings, enums, integers, numbers, booleans, arrays and local `$ref`s are the whole subset. A
   nullable field written the 3.1 way (`type: [string, 'null']`) reaches its `default` branch and
   fails the frontend lane. Optional-by-omission is the shape the contract uses instead.

8. **CORE-02 left one question here by name:** `RequestId` carries a `Uuid` because
   `audit_event.request_id` is `uuid not null`, and "what a surface does with a client-supplied
   header that is not one is left to API-01".

## 2. The scope decision, stated before the diff

This package delivers **the contract for the whole REST surface and the routes that can be served
completely today**. The project-scoped routes are not among them, for finding 2, and the two
unauthenticated sign-in routes are not among them, for findings 4 and 5.

What is served after this package:

| Method | Path | Use case |
|---|---|---|
| `GET` | `/api/v1/me` | `WhoAmI` |
| `POST` | `/api/v1/me/credentials` | `IssuePersonalAccessToken` |
| `DELETE` | `/api/v1/me/credentials/{id}` | `RevokeCredential` |
| `POST` | `/api/v1/auth/refresh` | `RefreshSession` |
| `GET` | `/openapi.yaml` | none — the contract serving itself |

Plus `/health/live` and `/health/ready`, unchanged.

That is the agent's own path end to end: an agent presents `nod_pat_…`, learns who it is, mints a
second token and revokes the first — which is capability 1 of
[`../VISION.md`](../VISION.md) § 4 reaching a surface for the first time. It is not the whole ticket,
and § 6 says exactly which of the ticket's criteria move to the follow-up rather than being quietly
dropped.

**Why not build the project-scoped routes unmounted.** They would compile, test against fakes and be
reachable from nothing — and the composition root is where the missing pieces actually are. A route
nothing can mount is a claim that the surface exists; the honest form of that claim is a contract
entry marked "specified, not served", which is what `API_CONTRACT.md` already uses.

## 3. The error taxonomy, designed for two consumers

MCP-01 depends on this package for exactly this and for nothing else, so the codes are a value
rather than literals scattered across handlers — and the value lives in **`:application`**, not in
`:api-rest`. That is forced rather than chosen: `:api-mcp` is a sibling of `:api-rest` and cannot see
it (ADR-0005), so a taxonomy declared in one adapter would be re-declared in the other, and two lists
drift the way two permission engines do.

`ErrorCode` therefore carries the wire string and nothing else. What each becomes on **this** wire —
the status, the title, the `type` URI — is three extension properties in `:api-rest`, exhaustive over
the enum so adding a code is a compile error until this surface says what it means. The eight are
`API_CONTRACT.md` § 4's table and nothing beyond it: renaming one is a breaking change, so adding one
deliberately costs a contract edit first.

`respondProblem(code, detail)` renders RFC 9457 with the `application/problem+json` media type — the
media type is half of what the RFC defines, and a gateway keying on it does not recognise
`application/json`. Both caller-visible strings go through `SecretRedaction` on the way out; § 5's
M7 is why.

`AuthenticationPlugin` already hand-rolls a `ProblemDetail` for its one code. It moves onto the
shared type in this package rather than keeping a second copy, which is the same argument invariant
#2 makes one layer up.

`RejectionReason` maps to `unauthenticated` in every case. The reason is in `detail` for the caller
that already holds the credential, and never a distinct status: a `403` for a revoked token and a
`401` for an unknown one would tell an attacker which selectors exist.

## 4. Contract-first, and the drift check that makes it true

The OpenAPI document is written first and the routes are written against it. That is unfalsifiable
as a claim about a diff, so the package ships two mechanical checks instead:

1. **The served bytes are the committed bytes.** `GET /openapi.yaml` streams the same classpath
   resource the repository holds, so "the document the server serves" is the file by construction
   rather than by a comparison that could be forgotten.
2. **The served routes are the documented routes.** A test walks Ktor's routing tree, renders every
   `(method, path)` it exposes, and asserts set equality with the `paths` of the parsed document.
   A route added without a contract entry and a contract entry with no route both go red, and the
   test says which side is missing. This runs in `:api-rest:test`, so `make check` and the CI
   backend lane both carry it.

The second is the one with teeth, and the direction a contributor actually produces — a route
served with no contract entry — is the one watched red (§ 5, M10). The opposite direction is
demonstrated by a case that runs the check against a known difference; making it red would mean
deleting a path from the committed file.

## 5. Test plan

Adapter tests, in the shape `AuthenticationPluginTest` established: a real Ktor application over
fakes of the `:application` ports. `:api-rest` cannot see `:persistence`, so there is no database in
this lane and nothing to tear down.

| # | Claim | Test | Mutation watched |
|---|---|---|---|
| M1 | An absent owner is an absent key, never a null one | `IdentityRoutesTest` | `explicitNulls = true` |
| M2 | A body that will not deserialise is `422`, not `500` | `IdentityRoutesTest` | `receiveOrNull` → `receive` |
| M3 | Another actor's credential is indistinguishable from an absent one | `IdentityRoutesTest` | the ownership condition dropped from the store's `revoke` |
| M4 | The presented refresh token is revoked as its replacement is minted | `AuthenticationRoutesTest` | `revoke` returning the row unmarked |
| M5 | A refresh token is not a bearer credential | `AuthenticationRoutesTest` | SEC-01's kind guard changed so it never fires |
| M6 | A refusal never quotes the credential | `SecretsNeverLeaveTest` | the presented header appended to the refusal's `detail` |
| M7 | The problem document's `instance` is redacted | `SecretsNeverLeaveTest` | the redaction removed |
| M8 | A client id that is not a UUID is replaced, not echoed | `CorrelationTest` | the raw header echoed |
| M9 | The id echoed is the id the trail recorded | `CorrelationTest` | the plugin given a fresh id per call |
| M10 | An undocumented route is a difference the drift check reports | `ContractDriftTest` | one more route registered |
| M11 | A stale bearer header does not stop the refresh it accompanies | `AuthenticationRoutesTest` | the refresh path removed from the exemption |
| M12 | The middleware's exemption list is the contract's `security: []` | `ContractDriftTest` | one more path in the exemption |
| M13 | …and in the other direction | `ContractDriftTest` | one path removed from the exemption |
| M14 | The exemption is per path, not global | `AuthenticationRoutesTest` | a secured path added to the exemption |
| M15 | Every refusal detail survives redaction unchanged | `ProblemDetailTest` | the hyphen removed from `bearer-scheme` |
| M16 | …and *every* means all seven, not the two it started with | `ProblemDetailTest` | `bearer ` put into `LABEL_INVALID` |
| M17 | An identifier that is not one answers as an absent one, never as a malformed one | `IdentityRoutesTest` | that refusal changed to `validation_failed` |
| M18 | A body carrying a field this build does not know is refused | `AuthenticationRoutesTest` | `ignoreUnknownKeys = true` |

Every mutation is applied, the named spec run, and the tree restored — by a script rather than by
hand, and a run that produces no `BUILD` line is an error rather than a result. That last rule is
SEC-01's: its harness's first run reported thirty negatives red because it never reached Gradle.

**Two did not go red on the first pass, and both are recorded rather than tidied away.** M1 stayed
green because `owner` carried a `= null` default, so `encodeDefaults` dropped the key and the
setting the comment named was doing nothing — the default is gone and the setting is now the guard.
M6 stays green **by design**: the same `SecretRedaction` that M7 proves catches the leak on the way
out, so this sweep shows that no credential reaches a caller rather than that no call site tries to
put one there. The test says so.

**M11 and M12 exist because review round 1 found the blocking defect they now guard** (§ 9), and
**M18 because round 3 found the claim it guards had no isolating case** (§ 11) — the two bodies that
sent an unknown key also omitted a required one, so both stayed green with the guard off. All three
were written with the fix, not before it, which is the honest order for a defect found in review.

The repository's own record is why this is done at all: round 4 of SEC-01 had a guard, a test, and
the attacker's input in it, and round 7 found a comment naming a paired negative that did not exist.

## 6. Acceptance criteria — how each is met

| Criterion | Met by |
|---|---|
| Every route maps to a use case and contains no permission decision, state transition, SQL or audit write | § 2's table; `:api-rest` has no `:persistence` on its classpath, so the SQL half is a compile error |
| The committed OpenAPI document matches what the server serves; CI fails on drift | § 4, both checks |
| `not_found` for something the caller cannot see, never `forbidden` | `IdentityRoutesTest`, on `DELETE /me/credentials/{id}`: the same identifier and caller, once with the row present and owned by somebody else and once with no such row, compared byte for byte. **The project form of this criterion moves to the follow-up** — there is no project route to prove it on |
| Every mutating route accepts an idempotency key | **Moves to the follow-up.** Accepting a header this package cannot honour would advertise replay protection that does not exist; the record is `idempotency_record`, and reading it is an `:application` mechanism, not a header parse |
| `make check` green | Run in full, named in the ticket with which lanes executed |
| Independent review: 0 BLOCKING | Phase 4, `reviewer` sub-agent |

Both moves are **visible**, in the ticket, with this section as the reason — and they are two moves,
not one. `not_found` for an invisible project is the project-scoped surface's and lands with it. The
idempotency criterion is **not** the project-scoped surface's: it applied to this package's three
mutating routes and is not met, and it moves because the mechanism reads `idempotency_record` in
`:application`, not because there is no route to prove it on. One reason covering both would be
false of the second, which is what review round 1 found.

## 7. Deliberate non-goals

- **Every project-scoped route.** Finding 2. Filed as
  [`../../tickets/open/API-02.md`](../../tickets/open/API-02.md), which carries the RLS bootstrap
  decision, `PermissionDirectory`'s adapter, and the `not_found`-for-an-invisible-project criterion.
  Two of [`../PROJECT_MANAGEMENT.md`](../PROJECT_MANAGEMENT.md) § 8's criteria carry it — a
  structural decision, and a foreign subtree this package must not edit.
- **The idempotency key on every mutating route.** Not a project-scoped route's problem: this
  package's three mutating routes are the ones it applies to. API-02 carries it because the record
  is `idempotency_record` and reading it belongs in the mutation's own transaction — § 6.
- **The two unauthenticated sign-in routes.** Findings 4 and 5. Specified in the contract and named
  there as not served, with the blocker beside each. **Not ticketed**, because
  [`../PROJECT_MANAGEMENT.md`](../PROJECT_MANAGEMENT.md) § 8's net rule allows this session one new
  ticket and API-02 is it; a delivery adapter and a request budget are two packages, not one, and
  `API_CONTRACT.md` § 2b is where a reader meets both.
- **Rate limiting.** Per actor, and it is the mechanism that makes an unauthenticated Argon2id
  endpoint publishable at all. Named where it blocks, not implemented here.
- **The OIDC callback.** `ARCHITECTURE.md` § 5 names it as this surface's, and the handshake is a
  package with a provider, a redirect and a state parameter in it — not a route added in passing.
- **A `GET /me/credentials` listing.** The contract specifies one; the port to read a list does not
  exist, and adding a persistence adapter for it is the foreign subtree this package is avoiding for
  larger reasons. It stays specified.
- **Pagination.** No collection is served, so the cursor machinery has nothing to page. Specified in
  the contract, built by the package with the first collection.
- **Changing `/health/*`.** They are outside `/api/v1` deliberately and this package does not move
  them.

## 8. Open questions, each with a recommendation

1. **How the project scope is bootstrapped.** The circularity in finding 2 has two answers: a second
   RLS policy admitting `project_membership` rows whose `actor_id` matches a `nodera.actor_id`
   session setting, or a `security definer` function returning an actor's project ids. *Recommendation:*
   the policy. It keeps every read under RLS rather than adding a function that runs outside it, the
   new setting is written from the authenticated context exactly as `nodera.project_ids` is, and a
   `security definer` resolver is a standing privilege-escalation surface that has to be argued about
   for the life of the schema. It is an ADR, and it is the maintainers'. API-02 carries it.

2. **Whether `POST /api/v1/me/credentials` should cap the expiry it accepts.**
   `skills/secure-coding.md` says a credential that never expires is one nobody will rotate, and
   SEC-01 left the default and the ceiling to "the route that issues the token" — this one.
   *Recommendation:* require `expiresAt` rather than defaulting it. A default is a policy invented at
   the layer that cannot see the deployment; a required field makes the caller state the intent, and
   a ceiling can be added later without breaking a client that already sends one. Recorded in the
   contract as a deliberate divergence from "optional" in the schema.

3. **What `GET /me` returns for an agent's owner.** The envelope carries one level and says the full
   chain is at `/actors/{id}`, which this package does not serve. *Recommendation:* return the one
   level, from the same read. It is one join, it is what the contract already specifies, and omitting
   it would make the first client write the heuristic invariant F2 exists to forbid.

## 9. What review round 1 changed

The phase-4 reviewer returned **CHANGES REQUIRED — 1 BLOCKING, 9 NON-BLOCKING**, and the blocking
finding was a defect this plan's own reasoning had produced.

**The refusal SEC-01 built was applied where the contract says there is nothing to refuse.** The
credential middleware sits on the whole pipeline and answers `401` before routing for any presented
credential it cannot use. A client keeps a default `Authorization` header; its access token expires;
it calls `POST /auth/refresh` with a valid refresh token in the body and that stale header still
attached — and the middleware refused it, naming the wrong credential. **Renewing a session was
impossible for a client behaving normally**, and the same header stopped a probe reading the health
endpoints and a client reading the contract it was about to call.

§ 2's table already said which routes carry no security; the middleware did not know. The fix gives
it that list, and `ContractDriftTest` asserts the list is exactly the operations the document
declares `security: []` — so the two statements of one fact are compared rather than trusted to
agree. M11 and M12 watch both halves.

The nine non-blocking findings are fixed in the same session, and three of them were claims rather
than code: the shared codes are `docs/MCP.md` § **9**, not § 4; the secrets sweep said "every served
route" and drove five of seven; and the scope amendment gave one reason for two criteria, which was
true of one of them. `runCatching` was swallowing `CancellationException`, an expiry fallback could
have reported an expiry the credential does not have, and the drift walk dropped handlers that are
not method-selected — the shape the static content routing itself takes.

## 10. What review round 2 changed

**CHANGES REQUIRED — 1 BLOCKING, 4 NON-BLOCKING, and the blocking one was a regression round 1's own
fix introduced.** That is the third package in this repository's record to produce that shape, and
it is the argument for reviewing again after fixing rather than once.

Round 1's non-blocking N5 trimmed the comments and, with them, the hand-rolled problem document in
`AuthenticationPlugin` moved onto the shared `respondProblem`. `respondProblem` redacts `detail` —
and `SecretRedaction`'s third rule replaces `bearer` or `basic` followed by whitespace and a token.
The one detail string in the package containing the word is `"the Authorization header must carry a
bearer credential"`, so every unsupported-scheme refusal answered **"a bearer ***"**. It also
falsified a sentence this package had just written into `docs/API_CONTRACT.md` § 4 — that the
redaction changes nothing about `detail`.

Nothing caught it because SEC-01's test asserts `shouldContain "bearer"`, which `"bearer ***"`
satisfies. **A substring assertion is what let it through**, so the guard that replaces it compares
whole strings: `ProblemDetailTest` asserts every detail this surface can emit — the module's own and
`RejectionReason`'s — is byte-identical after redaction, and asserts two whole served bodies. The
sentence is hyphenated (`bearer-scheme`), because where prose and the redactor disagree the prose is
what changes.

The four non-blocking findings were all the same family and all fixed: two comments claiming a
watched negative that the recorded run did not have (now M13 and M14, run), a KDoc still promising
"no third state" that the exemption had made false on four paths, and a byte-for-byte test that
compared the classpath copy with the wire and never opened the repository's own file — it opens all
three now, because `generate-zod.mjs` reads the source and a filtering step between source and jar
would split the contract in two with that case still green.

**Running M14 rather than asserting it found a third defect, and it is the same defect in a third
costume.** "A stale bearer header still refuses a route the contract secures" asserted only the
status, and with `/api/v1/me` added to the exemption it **stayed green**: the header is then ignored,
the route sees no context and answers `401` as well. The same status for the opposite behaviour. Only
the detail separates them — the middleware says "not in a form this deployment issues", the route
says "none was presented" — so the assertion is on the detail now, and M14 is red.

Three rounds, three defects, and all three were a **weak assertion** rather than wrong logic: a
substring that a corrupted string satisfied, a status that two different refusals share, and before
them a comment naming a negative nobody had run. What the record says about this package is that the
assertions needed reviewing harder than the code did.

## 11. What review round 3 changed

**CHANGES REQUIRED — 1 BLOCKING, 7 NON-BLOCKING.** The blocking finding is the first in this package
that is neither a false claim nor a weak assertion: **the document and the server disagreed**, and
the disagreement was shipped rather than drifted into.

`/api/v1/me` declared `200` and `401`. The route also answers `404` — `WhoAmIResult.Unknown` is a
credential that resolved to an actor that no longer exists, and failing closed there is deliberate,
commented at both `WhoAmI` and `ActorProfiles`, and asserted by `IdentityRoutesTest`. So
`openapi-typescript` typed `operations["whoAmI"].responses` as `{200, 401}` and a frontend written
against the generated types had **no member for an answer the server gives**. That is precisely what
invariant #11 — contract-first, types generated from the contract — exists to prevent, and it means
acceptance criterion 2 was ticked and not met. The code was right; the document was wrong, and the
document is what changed.

**Why nothing caught it, and what it costs to catch.** § 4's second check walks the routing tree and
compares `(method, path)` sets. A response map is not in that comparison, and it cannot be added for
free: Ktor's routing tree knows which statuses a handler *can* produce only by executing it, so a
mechanical check of this class is not a widening of the existing walk but a different check — a
per-operation test that drives each documented status, or a convention that every `respondProblem`
call site is reachable from a case that asserts the documented code. Both are real work with real
value and neither is this package's. **Recorded as a proposal for the maintainers**, not started
here: growing a package inside its third review round is the shape this repository's record already
warns about.

**Seven non-blocking, all fixed in the same session, and four of them were claims.** Two live
present-tense sentences in [`../../tickets/INDEX.md`](../../tickets/INDEX.md) — the SEC-01 paragraph
and the **Working order** — still said no route exposed any of the identity surface and that
`docs/API_CONTRACT.md` did not yet specify `/auth/*`. This package falsified both halves of both, and
had already corrected a third copy of the same class in the same file. That is
[`../../skills/code-review.md`](../../skills/code-review.md) § 3's rule — a corrected claim has
siblings — applied to the file `CLAUDE.md` makes the second thing every session reads.

§ 6 of this plan restated, for **both** moved criteria, the single framing round 1 found false: the
idempotency criterion is not the project-scoped surface's, it applied to this package's three mutating
routes and is not met, and § 6's own table two rows above already said so. § 7 grouped the two under
"Every project-scoped route" a second time. The plan is what the ticket points at for reasoning, so
the copy left standing is the one the next reader believes; both now state the two reasons separately.

`ProblemDetailTest`'s KDoc credited the sweep's widening to "round 3", a round whose findings are
these. `tickets/closed/DB-01.md` records what a pre-written account of a review round cost last time.
The change is named without a round number now.

`docs/API_CONTRACT.md` § 4's `validation_failed` row said "Names the offending fields". Two of the
three details this package serves do (`LABEL_INVALID`, `EXPIRY_INVALID`); the third and most common,
`MALFORMED_BODY`, names none on purpose — the deserialiser's own message quotes what it was reading
and one of those bodies is a refresh token. Before this package nothing returned `validation_failed`
and the row was unfalsifiable. It is false now, so it changed.

The two that were code: `noderaJson`'s KDoc claimed unknown keys are refused, and both cases sending
an unexpected field also **omitted a required one**, so both stayed green with `ignoreUnknownKeys =
true` — a kotlinx default the builder does not state, and therefore a one-line convenience change
nothing would have caught. A case sending every required field plus one unknown key is the guard now.
And two comments restated documentation: 16 lines in `NoderaApi.kt` retelling round 1's defect
narrative already carried in four other places, and `Serve.kt` restating the project-scope circle
already in four. Round 2's blocking regression came out of round 1's comment trim, so this one moved
prose and touched no call site.

**The gitleaks question, settled by running it.** `.gitleaks.toml` is untouched while this package
adds five fixture literals, and the file says a differently-shaped fixture means adding an entry.
Both that and the implementing session's green scan are true, and the reconciliation is *what each
scan saw*: the green run scanned committed history, which this package is not in — so it could not
have seen the new fixtures at all. A working-tree scan
(`gitleaks detect --no-git`, throwaway container, whole repository) reports **no leaks**. Removing
the allowlist and rescanning yields six findings, all of them SEC-01 literals already named — the one
new file among them, `RestFixtures.kt`, is flagged only for the compact JWS, which is byte-identical
to SEC-01's and already covered. The five new selectors and verifiers match no rule: `generic-api-key`
wants a keyword beside the value, and `REFRESH_SELECTOR`/`MINTED_VERIFIER` are not keywords. So the
allowlist correctly gains nothing. What did change is the sentence that produced the finding — it is
a list of suppressions, not a register of fixtures, and adding a value the scanner never flags would
make it a second source of truth nothing verifies.

Two findings the implementing session raised against its own work are fixed with these: the
`shouldContain "bearer"` that round 2 indicted and that survived in `AuthenticationPluginTest` — kept
alive only because `ProblemDetailTest` now covers the same string, which makes it a trap rather than
a guard — and the unknown-keys claim above.

**The whole negative harness was re-established mechanically on the fixed tree**, not re-read: every
mutation in § 5 applied by script, the named spec run, the tree restored, and each failure traced to
the spec name that failed rather than to a `BUILD FAILED` line — a mutation that stops the module
compiling proves the mutation does not build, not that the assertion watches the guard. Seventeen
went red, M6 stayed green exactly as § 5 records, and M18 is red.

**Re-running it corrected a guard comment's reader rather than the comment.** The first attempt at
M5 disabled the kind comparison in `CredentialVerifier` — and `AuthenticationRoutesTest` **stayed
green**. The comment names a different guard, `CredentialAuthenticator.authenticate`'s, and it is
right: a `SESSION` token is refused there and never reaches the verifier, so the verifier's guard is
unreachable on that path. Disabling the one the comment names is red. The verifier's own claim — the
kind is checked against the row, never against the prefix that arrived — is SEC-01's and is watched
by SEC-01's own `CredentialAuthenticatorTest`, in `:application`.

### Proposal, not started here

**Should `ContractDriftTest` compare response maps as well as route sets?** It is what would have
caught this round's blocking finding mechanically. It is not a widening of the existing walk (see
above) and it is the maintainers' to weigh: the cheap form asserts that every `ErrorCode` a route
file can pass to `respondProblem` appears in that operation's documented responses, which is static
and catches this exact class; the thorough form drives each documented status per operation, which
is a test per response and grows with the surface. API-02 adds the first project-scoped routes and is
the natural place to decide, before there are twenty operations rather than seven.

## 12. What review round 4 changed

**APPROVED — 0 BLOCKING, 2 NON-BLOCKING.** The round exists because of the arithmetic in this
repository's record rather than because anything was suspected: three of the four blocking findings
after SEC-01's round 2 were defects a previous round's own fix had introduced, so a package whose
last act is a fix diff gets read again. This fix diff introduced none.

What the reviewer did that the earlier rounds could not: it re-derived, from the handlers, every
status each of the seven served operations can produce and compared the set with the document by
hand — the check round 3's blocking finding proved nothing mechanical performs — and it re-ran both
generators and diffed their output against the committed `schema.ts` and `schemas.ts`. Both agree.

**The second finding is this package's signature defect, once more.** `correlationId()`'s KDoc said
its fallback is "the same answer it would have produced for a request that carried no usable header".
It is not. With [`installRequestCorrelation`] the id is minted once, stored in the call's attributes
and echoed on the response, so the header and the audit row are the same value — which is the whole
of M9. Without the plugin the fallback mints a fresh id on **every call**, echoes none, and two
readers inside one request disagree. That is not a hypothetical branch: `AuthenticationPluginTest`
builds an application with the middleware and without the plugin, so the fallback is the path it
takes. The comment now says so.

Four rounds, and the tally is what the package is for the record: one working defect (round 1), one
regression a fix introduced (round 2), one document that disagreed with its server (round 3), and
four claims that were false in prose while the code around them was right. **Not one of the four
blocking findings was wrong logic.** Every one was something the tree *said* — a contract, a comment,
an assertion — that the code did not do.
