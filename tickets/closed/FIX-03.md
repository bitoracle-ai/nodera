---
id: FIX-03
title: Both lanes on main are red after the dependency merges
priority: P2
status: closed
effort: ~0.5 d
depends_on: []
note: Every other open package needs a green gate to close, so this P2 goes before the rest of the band.
created: 2026-09-15
updated: 2026-09-15
closed: 2026-09-15
---

# FIX-03 · Both lanes on main are red after the dependency merges

**Priority:** P2
**Effort:** ~0.5 d
**Skills:** `critical-invariants.md` + `testing.md`, `secure-coding.md`

## Motivation / context

Five Dependabot pull requests merged on 2026-09-14 left `main` red in two independent ways. The CI
lane fails in `:persistence:test`, so no package can close on a green gate. The CodeQL lane fails
before it extracts anything, so the backend — credentials, tokens, the permission engine — has had
no static security analysis since. Neither failure is a defect the bumps carried in: one exposed a
hole that was already in the audit harness, the other moved the compiler past what the installed
CodeQL bundle understands.

## Current state (honest)

**CI.** `AuditCompletenessTest`, "a statement hands back the watched connection, not the one
underneath it", fails with `expected:<PgConnection@4eabe0ae> but was:<PgConnection@4eabe0ae>` — one
object reported as unequal to itself. `AuditCompleteness` proxies the JDBC connection and forwards
every unhandled call to the target, `equals` among them, so `proxy.equals(proxy)` asks
`target.equals(proxy)` and is answered `false`. Reproduced in isolation on JDK 21: a
`java.lang.reflect.Proxy` with a forwarding handler prints `proxy.equals(proxy) = false` and
`proxy.equals(target) = true`, and both objects print the same `toString`, which is why the failure
message names the same instance twice.

The second of those is the finding. **The watched connection answers `equals` with `true` for the
raw one it hides**, and with `false` for itself — this harness's guarantee, inverted. The case that
asserts "not the one underneath it" does not expose that, and never did. Under kotest 5 it was green
because `shouldBe` short-circuited on reference identity and never reached `equals`; under kotest 6
it is red — and red identically, same message, whether the `getConnection` guard is present or
deleted. Watched rather than assumed: the guard was deleted on the committed harness and the case
failed with the message CI reports, so it does not tell the two apart. What the inversion does reach
is every comparison written the other way round — `raw shouldBe watched`, a `contains`, an `indexOf`
— each of which accepts the raw connection as the watched one. That is the class SEC-01 round 7
recorded, one layer over: a case whose name claims more than what it tests.

**CodeQL.** `:domain:compileKotlin` fails with `Kotlin version 2.4.20 is too recent. CodeQL
currently supports versions below 2.4.20`. The catalogue moved 2.4.10 → 2.4.20 in c941c51, and the
extractor is a compiler plugin: it aborts the compile it traces rather than extracting less. The job
then uploads a failed-run SARIF, so the lane reports a configuration error and analyses nothing.
Bumping the action does not help — v4.38.0 is the newest release and bundles exactly CodeQL 2.27.0.
Support for 2.4.20 is merged upstream, where the version-check fixture now names 2.4.30 as the cap,
and arrives in the next bundle.

Going back to Kotlin 2.4.10 is not an option on the shipped build: 2.4.20-Beta1 is where
CVE-2026-53914 (GHSA-r937-wjx7-w2jp, 6.7, unsafe deserialization of build-cache metadata) is fixed,
and that group bump is what fixed it here.

**Outside this repository.** The `developer` ruleset requires `CI Gate` on `main`, and every one of
the five pull requests was merged before its run finished — the ruleset grants organisation admins
and the admin repository role `bypass_mode: always`. The red lane was not overridden by accident;
the required check is advisory for the people who merge. Only the owner can change that, so it is
reported rather than filed.

## Approach

1. Answer `equals`, `hashCode` and `toString` in the proxy handlers instead of forwarding them —
   identity semantics, on the connection proxy and on both statement proxies.
2. Make the escape case assert what its name says (`shouldBeSameInstanceAs`), and add a case that
   states both polarities of the equality: equal to itself, not equal to the raw connection.
3. Compile the CodeQL analysis build — and only that one — with a compiler the bundle accepts,
   through a Gradle property the CodeQL workflow passes and nothing else does.
4. File the removal of that pin separately: it waits on a CodeQL release, which this repository
   cannot produce (CI-03).

## Acceptance criteria

- [x] The connection proxy and the statement proxies answer `equals` by identity: equal to
      themselves, not equal to the object underneath.
- [x] `AuditCompletenessTest` asserts both polarities, and the escape case asserts identity rather
      than equality.
- [x] The three equality assertions watched red on the committed harness, and the escape case
      watched red with its guard deleted — not merely green on the fixed one.
- [x] `./gradlew classes -x test -PcodeqlKotlinVersion=2.4.10` compiles every module under a
      compiler CodeQL 2.27.0 accepts; without the property the catalogue's 2.4.20 still resolves.
- [x] `make check` green.
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.

## Affected files

- `backend/persistence/src/test/kotlin/ai/nodera/persistence/audit/AuditCompleteness.kt` — the three
  `Object` methods answered in the handlers instead of forwarded.
- `backend/persistence/src/test/kotlin/ai/nodera/persistence/audit/AuditCompletenessTest.kt` — the
  escape case says identity; a new case states both polarities of the equality.
- `backend/settings.gradle.kts` — the catalogue's `kotlin` version becomes overridable by one
  property, unset everywhere but the CodeQL workflow.
- `.github/workflows/codeql.yml` — the analysis build passes it.
- `CHANGELOG.md` — two entries under `### Fixed`.
- `docs/ci.md` — both failures added to the register of what a Dependabot bump gets past every lane.
- `backend/gradle/wrapper/gradle-wrapper.properties` — a stale provenance comment beside a checksum
  the review checked; the sum is 9.7.1's, the comment named 8.14.5.

## Verification

This machine has no JDK, so every backend gate ran in a throwaway `eclipse-temurin:21-jdk`
container over the mounted repository, reusing only the `nodera-gradle-cache` dependency volume.
Testcontainers reached the host daemon through the mounted socket and its reaper removed each
`postgres:16-alpine` it started, with the network that went with it. Nothing was left running:
`docker ps -a` shows no residue, and no volume beyond the dependency cache.

**Lanes that executed.** Every Gradle invocation carried `--no-build-cache --rerun-tasks`, so none
of these was skipped as up to date or served from a cache.

| Lane | Command | Result |
|---|---|---|
| Repository checks | `make PY=py check-repo` | green, twelve steps including both self-tests |
| Database conventions | `make PY=py check-db` | green, the SQL gate proved on its five fixtures |
| Backend | `./gradlew ktlintCheck detekt checkModuleBoundaries test build` | green, **74 tasks executed** |
| Frontend | `yarn install --frozen-lockfile`, `api:generate` + `git diff --exit-code`, `lint`, `typecheck`, `test:coverage`, `build` | green |
| CodeQL analysis build | `./gradlew classes -x test -PcodeqlKotlinVersion=2.4.10` | green, **13 tasks executed** |

The reviewer re-ran the first three independently and reached the same result; the frontend lane it
did not run, and says so.

**Not run, and why.** `make verify-db` drives Flyway from the host rather than from a container and
needs a JDK there; it stood up its own Postgres, failed on `JAVA_HOME` and removed the container,
volume and network on the way out — the ephemerality the target promises, observed on the failing
path. The gitleaks scan is CI-only. Neither lane is reachable from this diff: no migration and no
credential is touched.

**That CodeQL accepts the pinned compiler** is not something a laptop can prove — the extractor is
not available locally. The evidence is this repository's own history: the scheduled run
`34830351867`, on a tree where the catalogue still read `kotlin = "2.4.10"`, concluded `success`,
and every run at or after c941c51 failed in `Build for analysis`. The next push settles it.

**The negatives, watched rather than assumed.** Each was applied to the committed tree, run, and
reverted; each names the guard it kills.

| Guard disabled | What went red |
|---|---|
| `OBJECT_METHODS` emptied — the three `Object` methods forwarded again | the new case, all three soft assertions: `1) expected:<true> but was:<false>  2) expected:<false> but was:<true>  3) expected:<true> but was:<false>` |
| `"getConnection" -> proxy` deleted | the escape case: `PgConnection@… should be the same reference as audit-watched Connection` |
| the `OBJECT_METHODS` branch deleted from the `PreparedStatement` overload alone | only the `prepareStatement` assertion — the second copy of the guard has its own watcher |
| *the committed harness and the committed test, with `"getConnection" -> proxy` deleted* | the **old** assertion, with the same message it gives while the guard is present — the diagnosis behind the fix, and the reason the case now asserts identity |

That last row is the finding rather than a check: the case did not discriminate, and the new
`toString` is what makes the message in the second row name the two objects apart.

## Review result

**APPROVED — 0 BLOCKING, 7 NON-BLOCKING.** Sub-agent `reviewer`, which re-ran the paired negatives
itself, re-ran three of the four `make check` lanes, and built a standalone Gradle 9.7.1 probe to
settle how the catalogue override resolves rather than infer it.

Six of the seven are fixed in this package. **N1** was the one with teeth: the new case asserted the
identity `equals` on the connection proxy and on the plain-`Statement` proxy, but the
`PreparedStatement`/`CallableStatement` overload holds a *second copy* of the guard, and deleting
that copy alone left the suite green — the negative only looked convincing because emptying the
shared set kills all three at once. A `prepareStatement` assertion now watches it, and the third row
of the table above is that negative. **N2** — CI-03 removed the pin but not the comment explaining
it, which would have left a claim standing over a step that no longer carries it. **N3** — three
files this package changes were missing from Affected files. **N4** — `docs/ci.md` § "What a
Dependabot bump gets past every lane" is this repository's register of exactly this defect class,
and both of today's belong in it. **N5** — the record did not say that the analysis job now runs the
plugin carrying CVE-2026-53914, nor why that is safe there. **N7** — a pre-existing stale provenance
comment beside the wrapper checksum, named 8.14.5 while the URL is 9.7.1; both published sums were
fetched and the recorded one is 9.7.1's, so the wrapper was sound and only the comment was wrong.

**N6 is recorded rather than fixed, because it cannot be.** Three acceptance criteria were ticked
`[x]` before their evidence existed — `make check` while the backend lane was still running, and
the review before it had been run. They are all true now, so nothing false was committed, but
ticking ahead of the evidence is the habit the honesty rules exist to prevent, and no gate can catch
it. Written down here so the next session sees that it happened.
