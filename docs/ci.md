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
| **Repository checks** | Docs, tickets, adapters, language, invariants, release triggers | `make check-repo` |
| **Backend** | ktlint, detekt, module boundaries, tests, build | `cd backend && ./gradlew ktlintCheck detekt test build` |
| **Frontend** | Generated client fresh, lint, types, coverage, build | `cd frontend && yarn lint && yarn typecheck && yarn test:coverage && yarn build` |
| **Database** | SQL conventions, migrations apply twice, schema integrity | `make check-db`, then `make up && make migrate` |
| **CI Gate** | Every lane above succeeded | — (aggregation only) |

`make check` runs all of it.

## Repository checks, step by step

Always run, never conditional — doc and ticket drift must be caught on a docs-only pull request
too, which is exactly the kind that tempts people to skip the heavy lanes.

| Step | Script | Catches |
|---|---|---|
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
