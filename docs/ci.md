---
summary: Every CI job, what it enforces, and the exact local command that reproduces it — so a red pipeline is always reproducible on a laptop and never requires a guess-and-push cycle.
read_when:
  - When a CI job fails and you want to reproduce it locally.
  - Before adding, removing or renaming a job or a gate.
  - When wondering why the branch ruleset names only one required check.
---

# CI — jobs and their local equivalents

One workflow, five lanes, one aggregated required check.

**The branch ruleset for `main` requires exactly one status check: `CI Gate`.** Every lane feeds
into it via `scripts/ci_gate.py`. Adding a lane is a change to `needs:` and to that script —
nothing on GitHub has to be reconfigured, and the ruleset never learns the job list. That also
means a lane that is accidentally *skipped* cannot pass silently: a required job that did not
succeed makes the gate red.

## Job-to-local mapping

| Job | What it enforces | Run it locally |
|---|---|---|
| **Secret scan** | No credential in the history | `gitleaks detect --config .gitleaks.toml` |
| **Repository checks** | Executable bits, line endings, docs, tickets, adapters, language, invariants, release triggers, TODO/FIXME ban | `make check-repo` |
| **Backend** | ktlint, detekt, module boundaries, tests, build | `make check-backend` |
| **Frontend** | Generated client fresh, lint, types, coverage, build | `make check-frontend` |
| **Database** | SQL conventions, migrations apply twice, schema integrity | `make check-db`, then `make verify-db` |
| **CI Gate** | Every lane above succeeded | — (aggregation only) |

`make check` runs all of it **except the secret scan**: gitleaks is a separate binary that
`make check` does not invoke, so that lane stays CI-only unless you install gitleaks and run the
command above yourself. Every other lane has a `make` equivalent (the backend and database lanes
additionally need a running Docker daemon).

## Repository checks, step by step

Always run, never conditional — doc and ticket drift must be caught on a docs-only pull request
too, which is exactly the kind that tempts people to skip the heavy lanes.

| Step | Script | Catches |
|---|---|---|
| Executable bits | `scripts/lint_executable_bits.py` | A file with a shebang recorded `100644` in the git index (or the reverse) — the defect that made every CI run before CI-01 fail at its first `./gradlew` line |
| Line endings | `scripts/lint_line_endings.py` | A text blob recorded CRLF where `.gitattributes` stores LF — the file then reads as modified in every fresh clone (FIX-01) |
| Documentation frontmatter | `scripts/docs_list.py` | A knowledge doc with no `summary`/`read_when` |
| Documentation map fresh | `scripts/generate_docs_map.py --check` | Heading drift in the generated inventory |
| Ticket consistency | `scripts/check_tickets.py --check` | Status/directory mismatch, duplicate id, dependency cycle, dead link, stale views |
| Adapter consistency | `scripts/lint_adapters.py` | A missing adapter, a cross-reference between root adapters, an unfilled placeholder, a drifted scoped pair |
| Documentation discoverability | `scripts/lint_docs_index.py` | A document unreachable from the hub, a dead relative link |
| Repository language | `scripts/lint_language.py` | Non-English prose outside an allowlisted, reasoned exception |
| Release stays manual | `scripts/lint_workflow_triggers.py` | An event trigger added to `release.yml` |
| Invariant firewall | `scripts/lint_invariants.py` | A permission decision branching on actor kind; SQL interpolation; a second `PermissionService` |
| No TODO/FIXME | inline `grep` | A finding hidden in a comment |

## The database lane runs the same migrator the image runs

`./gradlew :app:run --args=migrate` — not a Gradle Flyway plugin with its own url, locations and
placeholders. There was such a plugin, and it was a second implementation of "apply the migrations"
configured separately from the one that actually runs in production. The copy that drifts is always
the one CI does not exercise, so there is now one.

## Why the database lane runs the migrations twice

A migration that applies once but not twice is a migration that will fail on the next
deployment. Running the sequence against an already-migrated database proves it is a no-op at the
Flyway level, rather than assuming it — and the failure it catches would otherwise appear during
an upgrade, which is the worst possible moment.

## Why the frontend lane regenerates the API client

`src/api/generated` comes from the committed OpenAPI document. Regenerating in CI and finding a
diff means the contract moved and the client did not. That drift is exactly what generation exists
to prevent, so it fails here rather than as a runtime type error nobody traces back to a schema
change three weeks earlier.

## Why every action is pinned to a SHA, and when the pins move

`uses:` names a commit SHA with the tag in a trailing comment. A tag is a mutable pointer: a
compromised maintainer account can repoint `v4` and change what runs here without touching a
line of this repository.

The cost is that pins do not move on their own, so they move in a package that says why. They
were last moved wholesale for the Node runtime retirement — GitHub made Node 24 the runner
default on 2026-06-16 and removes Node 20 on 2026-09-16, after which an action declaring
`using: node20` stops running. Every JavaScript action here now declares node24.
`sigstore/cosign-installer` is composite, so no deadline reached it, but it moved to v4 anyway in
[#21](https://github.com/bitoracle-ai/nodera/pull/21). That was not only a pin move: v4 defaults to
`cosign-release: v3.0.6`, and cosign 3 writes the new protobuf bundle format and stores container
signatures as OCI Image 1.1 referring artifacts, both on by default. `release.yml` has never run, so
this is correct by inspection only, and the operator-facing half is untested. Both halves of the
workflow run the same CLI on the same runner, so its own verification step should hold; the reader
is the open question. cosign gained this format in 2.6.0 behind `--new-bundle-format`, so a verifier
older than 2.6.0 cannot read the signature at all, and a 2.6.x one reads it only when told to.
Whatever `cosign verify` command OPS-02 publishes will therefore have to name a minimum cosign
version — there is no such command anywhere in the tree yet.
[OPS-02](../tickets/open/OPS-02.md) is the package that first runs the release path, and it now
inherits that question.

Resolve a pin with `gh api repos/<owner>/<repo>/commits/<tag> --jq .sha`, not with
`git/ref/tags/<tag>`: for an annotated tag the latter returns the tag object, and `uses:` will
never match it.

## Why the Gradle cache is the basic one

`gradle/actions/setup-gradle` defaults to `cache-provider: enhanced`, which its own `action.yml`
describes as "the full-featured commercial caching service (gradle-actions-caching)" — closed
source since v6, used under gradle.com's Terms of Use. It is free for public repositories, but it
puts an MIT project on separate commercial terms by default and silently. All three `setup-gradle`
steps therefore set `cache-provider: basic`, the open-source implementation over the ordinary GitHub
Actions cache. Nothing here needs what `enhanced` adds; a build of this size is not where a cache
provider decides the wall-clock time.

## What a Dependabot bump gets past every lane

Dependabot commits through the GitHub API, which writes blob bytes verbatim: git's clean filter
never runs, so `.gitattributes` normalisation does not apply to what it pushes. The wrapper bump in
[#23](https://github.com/bitoracle-ai/nodera/pull/23) landed `backend/gradlew.bat` as a CRLF blob
where the repository stores LF, and every lane stayed green because no lane looked — leaving the
file modified in a fresh clone before anyone had touched it. `scripts/lint_line_endings.py` is the
answer (FIX-01).

The executable bit is the same defect on the other axis, and the two together are why a wrapper bump
is read rather than waved through: `backend/gradlew` did survive #23 as `100755`, but that was
confirmed after the merge, not a property the merge could assume.

## The image is verified separately

`make check` proves the code compiles, lints and passes its tests. It cannot prove the *image*
behaves — that migrations apply as the owner and refuse the application role, that readiness fails
while the schema is behind, that the root filesystem can be read-only, that `SIGTERM` drains. Those
are properties of an artefact:

```
docker build --build-arg VERSION=0.0.0-local -t nodera:local .
sh scripts/verify_image.sh nodera:local
```

**CI does not run this yet.** It needs a Docker daemon and roughly two minutes, and wiring it into a
lane is its own decision. Stated here rather than left to be discovered, because a gate list reads
as a completeness claim.

## What CI does not check

Stated rather than implied, because a gate list reads as a completeness claim and this one is not:

- **Semantic drift between layer 2 and its distillates.** `lint_adapters.py` checks structure, not
  meaning. A rule changed in `skills/` while `CLAUDE.md` still says the old thing passes every
  gate. That is what [`prompts/maintain-adapters.prompt.md`](prompts/maintain-adapters.prompt.md)
  and the same-work-package rule are for.
- **Whether a ticket's `[x]` marks are true.** Only a reviewer reading the code can tell, and
  tickets disagreeing with their diffs is the most common real review finding.
- **Whether a test proves anything.** Coverage counts lines, not meaning. The paired-negative rule
  in [`../skills/testing.md`](../skills/testing.md) is enforced by review.
- **Non-English labels.** The language gate detects prose. A terse foreign word in a table cell
  slips through; a green run means "no foreign prose", not "no foreign words".

## Adding a lane

1. Add the job to `.github/workflows/ci.yml`.
2. Add it to `needs:` on `ci-gate`.
3. Add it to `REQUIRED` in `scripts/ci_gate.py` if it must always pass.
4. Add a row to the mapping table above, with a local command that genuinely reproduces it.

Step 4 is not paperwork. A gate a contributor cannot run locally is a gate they will discover by
pushing, which is slow enough that people start working around it.
