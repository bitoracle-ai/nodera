---
id: CORE-06
title: Direct closure from open for non-done resolutions, and two decisions recorded
priority: P3
status: closed
effort: ~0.5 d
depends_on: [CORE-03, CORE-04]
created: 2026-09-03
updated: 2026-09-04
closed: 2026-09-04
---

# CORE-06 · Direct closure from open for non-done resolutions, and two decisions recorded

**Priority:** P3
**Effort:** ~0.5 d
**Skills:** `critical-invariants.md` + `backend-kotlin.md` + `testing.md`

## Motivation / context

CORE-03 and CORE-04 each closed with questions raised for the maintainers rather than answered in
the diff: whether the status machine should carry `open → closed`, where Markdown safety belongs
while no renderer exists, and whether editing a comment needs a capability verb of its own. The
maintainers answered all three on 2026-09-03. This package records each answer where it is
normative and implements the one that is code, so the next reader meets a decision rather than an
open question — and so none of the three is raised a third time.

## The three decisions

1. **The status machine gets a direct `open → closed` edge, for `wont_do`, `duplicate` and
   `superseded` only — never for `done`.** A ticket recognised as a duplicate the moment it is filed
   no longer has to be walked through `in_progress` and `in_review` to be closed. `done` stays off
   the edge because a direct `done` would route around the closure gate and the review the gate
   reads.
2. **Markdown safety stays on construction for now; the renderer becomes its home.** Comment bodies
   keep being sanitised in `CommentBody.of`, so a `<` in a code sample is stored and shown as
   `&lt;` — an interim cost the maintainers accept. The renderer WEB-02 delivers is the home of both
   halves — code-aware escaping and link-destination filtering, `javascript:` included — and that
   ticket carries the scope. CORE-05's round-trip criterion is restated so that no importer path
   skips the sanitiser.
3. **`comment.update` does not become a capability verb.** Editing stays `comment.create` plus the
   author check. Recorded beside the normative tool table in `docs/MCP.md` § 3.4 so the question is
   answered once.

## Current state (honest)

Before this package: `transition()` in `ai.nodera.domain.ticket` refused `open → closed` as an
`UnknownEdge`, and `docs/DOMAIN_MODEL.md` § 5.1 said so, pointing at `docs/plan/CORE-03.md` § 8 for
the proposal. `docs/plan/CORE-04.md` § 8 carried the other two questions with recommendations.
`docs/MCP.md` did not mention `comment.update` at all — the name appeared only in that plan's
§§ 4.7 and 8. CORE-05 promised a byte-identical export for an unmodified import without saying what
a sanitiser that changes bodies does to that promise. WEB-01 and WEB-02 said nothing about Markdown
rendering or its safety.

After it: the edge exists in the domain behind a per-origin resolution table — `in_review` any,
`blocked` `wont_do` only, `open` everything but `done`. An origin without a row closes with nothing,
the fail-closed direction; no current test exercises that branch, because every origin whose edge
set contains `closed` has a row and every other origin is refused as an unknown edge first — the
twenty-five-pair matrix is what would surface a missing row the day an edge is added.
`open → closed` with `done` is refused as the named value `ResolutionNotPermittedFrom(open, done)`:
a structured refusal for the surfaces to render once they exist, not an exception, and not the
closure gate's `closure_gate_failed` — the gate is never reached on that edge. The attempt is audited `failed`
against the ticket like every other refusal. The documents listed under *Affected files* say what
the code does. No migration: the schema constrains `resolution` to `closed` (V2,
`resolution_iff_closed`) and says nothing about the edge set, so the database needed no change.

## Approach

1. `EDGES` gains `open → closed`; the closing rule becomes a table of permitted resolutions per
   origin, and the `done`-from-`open` refusal reuses the refusal `blocked → closed` already had
   rather than inventing a case.
2. Tests with a paired negative in both modules: the domain matrix and a resolution sweep from
   `open`; in `:persistence`, a direct `duplicate` closure audited like any other, and a direct
   `done` refused **with a review and a met criterion seeded** — so an implementation that ran the
   gate would close the ticket and the case would go red.
3. The documented machine wherever it is normative, the two plans' open questions stamped with the
   decision, the MCP note, CORE-05's criterion, WEB-02's scope and WEB-01's pointer, the changelog.

## Acceptance criteria

- [x] `transition(open, closed, r)` is `Permitted` for `wont_do`, `duplicate` and `superseded` and
      `Refused(ResolutionNotPermittedFrom(open, done))` for `done`; every other pair of the
      twenty-five is as before. Red with `CLOSED` removed from `open`'s edge set, and red with the
      `done` refusal removed — both watched, both restored (*Verification*).
- [x] Through `TransitionTicket` against Postgres: `open → closed/duplicate` transitions, stamps
      `closed_at`, and writes exactly one `success` audit event carrying `before.status = open`,
      `after.status = closed`, `after.resolution = duplicate` and the ticket's id;
      `open → closed/done` is refused as `ResolutionNotPermittedFrom(open, done)` even when a
      review exists and every criterion is met, leaves the ticket `open`, and writes exactly one
      `failed` event carrying the ticket's id.
- [x] `docs/DOMAIN_MODEL.md` § 5.1 draws the edge, lists it in the edge table with its resolutions,
      and no longer calls the gap open; `docs/plan/CORE-03.md` §§ 3.1 and 8 and
      `docs/plan/CORE-04.md` § 8 carry the decisions, dated, with their history left as written.
- [x] `docs/MCP.md` § 3.4 carries the `comment.update` note, and § 4 tells an agent that the three
      non-`done` resolutions may be taken from `open` while `done` may not.
- [x] `tickets/open/CORE-05.md` states the round-trip criterion in three clauses: byte-identical for
      a body the sanitiser leaves unchanged; a body the sanitiser changes round-trips to its
      sanitised form; no importer path skips sanitisation.
- [x] `tickets/open/WEB-02.md` names the renderer as the home of both halves of Markdown safety,
      `javascript:` link-destination filtering included, with an acceptance criterion for it;
      `tickets/open/WEB-01.md` says the shell ships no Markdown rendering and points there.
- [x] `CHANGELOG.md` `[Unreleased]` records the edge.
- [x] `make check` green — `make PY=py check`, exit 0, all four lanes: repository checks, SQL
      conventions, backend (ktlint, detekt, module boundaries, 567 tests, 0 failures, 0 skipped —
      561 before this package; the six added are the four resolution cases from `open` and the two
      audited cases), frontend (generated client fresh, lint with the F1 self-test, types, 15 tests
      under the coverage gate, build).
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings — recorded below.

## Affected files

- `backend/domain/src/main/kotlin/ai/nodera/domain/ticket/TicketStatus.kt` — the edge and the
  per-origin resolution table.
- `backend/domain/src/test/kotlin/ai/nodera/domain/ticket/TicketStatusTest.kt` — the edge joins the
  hand-written edge set; the resolution sweep from `open`; the "names the edge it refused" case
  moves to an edge that is still unknown.
- `backend/persistence/src/test/kotlin/ai/nodera/persistence/ticket/TicketLifecycleTest.kt` — the
  two audited cases; the unknown-edge case moves to `open → in_review`.
- `backend/persistence/src/test/kotlin/ai/nodera/persistence/ticket/TicketFixtures.kt` — a reader
  for what the trail says moved.
- `docs/DOMAIN_MODEL.md` § 5.1 · `docs/plan/CORE-03.md` §§ 3.1, 8 · `docs/plan/CORE-04.md` § 8 ·
  `docs/MCP.md` §§ 3.4, 4.
- `tickets/open/CORE-05.md` · `tickets/open/WEB-01.md` · `tickets/open/WEB-02.md` — the scope notes.
- `CHANGELOG.md` · `tickets/INDEX.md` and the generated views.

## Verification

`./gradlew :domain:test :persistence:test`, then `make check`.

Guards watched going red, each restored afterwards:

| Guard disabled | Went red |
|---|---|
| `CLOSED` removed from `open`'s edge set | `:domain`, 5 of 38: "OPEN to CLOSED is allowed" and the four "closing an open ticket directly as …" cases. `:persistence`, 2 of 17: "an open ticket closes directly as a duplicate, and the closure is audited like any other", and "an open ticket cannot close directly as done, even when the closure gate would be satisfied" — the second because the refusal becomes `UnknownEdge` rather than the named resolution refusal. |
| the `done`-from-`open` refusal removed | `:domain`, 1 of 38: "closing an open ticket directly as DONE is refused". `:persistence`, 1 of 17: "an open ticket cannot close directly as done, even when the closure gate would be satisfied" — the ticket closed, which is the fail-open the case exists to catch. |

Run by a small harness rather than by hand, in the shape CORE-04 arrived at: it parses the JUnit
XML, reports a missing or stale results file as unknown rather than as green, and restores the
file after each run, checked byte for byte against a copy. Restored, both specs ran green again —
on the first watch (2026-09-03), `:domain` re-executed and `:persistence` was served from Gradle's
build cache, its inputs being identical to the executed green run.

The package was finished by a second session after the first was cut off between closure and
commit. That session re-watched both guards on 2026-09-04 with the build cache off: both specs
re-executed on both restored runs, and the same cases went red. Its harness answered its first
attempt with *unknown* rather than green — it could not find the Gradle wrapper — which is the
answer the shape exists to give. It then ran `make PY=py check` over the finished tree.

## Review result

**2026-09-04 · APPROVED, 0 BLOCKING, 3 NON-BLOCKING (independent review, round 1).** All three
fixed in session.

| # | Finding | Fix |
|---|---|---|
| N1 | `docs/plan/CORE-03.md` § 8 names `tickets/closed/CORE-06.md`, a path that exists only once this package closes; and `tickets/INDEX.md` linked `open/CORE-06.md` in three places, not one. | Closure followed in the same session, so the path is true; all three INDEX links repointed. |
| N2 | "No row means no resolution — fail closed" is true by reading, but no test can go red if it were fail-open: every origin whose edge set contains `closed` has a row, and the others are refused as unknown edges first. The ticket listed it beside the two watched guards without saying it is unwatched. | *Current state* now says the branch is unexercised by the current edge set and that the twenty-five-pair matrix is what would surface a missing row. No code change. |
| N3 | The comments on `CLOSING_RESOLUTIONS` and on the domain test's resolution sweep restated the rationale that § 5.1, `docs/MCP.md` § 4, the changelog and this ticket already carry — five copies that will drift. | Both trimmed to a pointer at `docs/DOMAIN_MODEL.md` § 5.1. |

The reviewer reproduced guard B itself — with the `done` refusal removed, exactly the one domain
case and the one persistence case went red; the file was restored byte-identically, checksums
compared, and both specs re-ran green, executed rather than served from cache — and verified guard
A from the harness log against the timestamps of the tree under review. It followed the closure
gate to its single caller and confirmed that no path from `open` with `done` reaches it, and that
the negative in `:persistence` is real: the seeded review and met criterion are facts the gate
answers `Satisfied` to, so a gate-running implementation closes the ticket. It ran the repository,
SQL, ktlint, detekt, `:domain` and `:persistence` gates independently, all green. Not verified by
the reviewer: the frontend lane and the remaining backend modules under `make check` (run by the
implementer, exit 0) and `verify-db`, which the diff does not need — no migration, and V2's
`resolution_iff_closed` is the only schema rule on the closing edge. Nothing in the added lines
describes a system, path or repository outside this one — the boundary DOC-03 to DOC-05 drew.

**2026-09-04 · APPROVED, 0 BLOCKING, 1 NON-BLOCKING (independent review, round 2).** Run by the
session that finished the package, on the tree as committed: the views regenerated, nothing else
changed since round 1. Fixed in session.

| # | Finding | Fix |
|---|---|---|
| N1 | *Verification* recorded the first watch's cache-served `:persistence` restore run as the evidence, while the re-watch the package now rests on ran with the build cache off and re-executed both specs. The ticket is the durable record, and it carried the weaker evidence. | *Verification* now says which watch executed what. |

The reviewer reproduced both guards with the harness — the same five, two, one and one cases red,
the file's sha256 identical before and after each restore, both specs re-executed green — and ran
ktlint, detekt, the module-boundary check and every backend module's tests with the build cache off
and every task re-run: 567 tests (`:domain` 352, `:persistence` 159, `:application` 33, `:app` 19,
`:api-rest` 4), 0 failures, 0 skipped; the six over CORE-04's 561 are the four resolution cases
from `open` and the two audited cases. It confirmed each of round 1's three fixes in the tree,
checked every `[x]` above against the code and the documents, and swept the added lines and this
file for any reference to a system, path or repository outside this one — none. It noted that no
REST or MCP transition adapter exists yet, so what the surfaces do with
`ResolutionNotPermittedFrom` is a claim about later packages; *Current state* now says so. Not
verified by the reviewer: the SQL-conventions lane, the frontend lane, the backend `build` task
(all under `make check`, run by the finishing session — *Verification*) and `verify-db`, which the
diff does not need.
