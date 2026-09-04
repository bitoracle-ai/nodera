# Local guide — `backend/`

Read before any change in this subtree. Root rules still apply; these are the additions that
only matter here.

Full reference: [`../skills/backend-kotlin.md`](../skills/backend-kotlin.md) ·
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) § 2.

## Module boundaries — enforced by the build, not by review

```
:app -> :api-rest    -> :application -> :domain
     -> :api-mcp     -> :application -> :domain
     -> :persistence -> :application -> :domain
```

- `:domain` is **framework-free**: no Ktor, no SQL, no JSON, no logging framework.
- `:api-rest` and `:api-mcp` do **not** depend on `:persistence`, so "no SQL in an adapter" is
  a compile error rather than a review finding.
- `:api-mcp` does **not** depend on `:api-rest`. They are siblings (ADR-0005); an MCP server
  that speaks to its own REST API needs a credential to do so, and every option for that
  credential destroys per-agent identity.

`./gradlew checkModuleBoundaries` fails the build on a violation.

## The three rules broken most often here

1. **`ActorContext` is the first parameter of every use case.** Never ambient, never a
   thread-local, never a coroutine context element. Making it a parameter is what makes the
   permission check impossible to skip silently. **Use cases live in
   `application/src/main/kotlin/ai/nodera/application/<area>/usecase/`** — that path is what
   `scripts/lint_invariants.py` scans, so a use case outside it is unchecked rather than exempt.
2. **Adapters translate, they do not decide.** No permission decision, no state transition, no
   audit write, no SQL in `:api-rest` or `:api-mcp`.
3. **The transaction boundary is the use case**, in `:application`. The audit event is written
   inside it — a repository that opens its own transaction to write one produces exactly the
   failure the invariant exists to prevent.

## Errors

`:domain` and `:application` return sealed results; adapters map them to status codes or MCP
error codes. Do not throw for an expected outcome — the MCP layer needs the structured detail,
and an exception taxonomy forces every caller to reconstruct it.

## Gates

```
./gradlew ktlintCheck detekt test
```

Testcontainers needs a running Docker daemon. Skipping the backend tests locally is the most
common cause of a surprise red build.

It starts its containers per run and its reaper removes what it labelled — containers, networks,
volumes — so a test never touches the `make up` database and a killed run cleans up after itself.
The base image it pulled stays in the local image cache. That is the rule every check needing a
service follows ([`../skills/testing.md`](../skills/testing.md)).

`test` is skipped as up to date when nothing it depends on changed, or served from the build cache,
and the lane reports the same green either way. When something rests on the tests having executed,
make them: `./gradlew test --no-build-cache --rerun-tasks`.

Warnings are compile errors here. A warning nobody fixes is a warning everybody stops reading.
