---
id: CORE-04
title: Comments, mentions and the review record
priority: P2
status: closed
effort: ~2 d
depends_on: [CORE-02, CORE-03]
created: 2026-08-20
updated: 2026-09-03
closed: 2026-09-03
---

# CORE-04 · Comments, mentions and the review record

**Priority:** P2
**Effort:** ~2 d

## Motivation / context

Comments and reviews are two of the five first-class capabilities. The review record in particular
carries the requirement that is hardest to retrofit: rounds are append-only and a contradicting
verdict stays visible.

## Current state (honest)

When this ticket was written the schema carried `comment`, `comment_mention`, `review` and
`review_finding` with their triggers, and no services existed.

It now holds: `ai.nodera.domain.collaboration` with the sanitiser, mention extraction and the
reviewer-independence rule; seven use cases in `:application` —
`CreateComment`, `EditComment`, `DeleteComment`, `ListComments`, `SubmitReview`, `ResolveFinding`,
`ListReviews`, each taking `ActorContext` first and opening one transaction. The five that mutate
write exactly one audit event; the two reads write one only when they refuse, which is the shape
`NextTicket` set — invariant #3 is about mutations, and a trail of successful reads answers nothing.
Three `:persistence` adapters sit under them. `ActorSummary` joins `ai.nodera.domain.actor`,
carrying the author's or reviewer's kind so a reader is **told** it rather than left to infer it.

**The closure gate now has something that feeds it.** `review_submit` and `finding_resolve` write
the rows CORE-03's gate already reads, so an unresolved blocking finding from round 1 refuses a
closure through the domain rather than through seeded fixtures.

**What is still absent, and deliberately.** Nothing is wired in `:app`; no use case is reachable
from a running process, because no surface exists (API-01, MCP-01). Nothing establishes
`nodera.project_ids` — still SEC-01's seam. And **`criterion_set` is not here**: it is the third
input the gate reads, `docs/plan/CORE-03.md` § 7 had assigned it to this package, and it is not in
this ticket's approach or its acceptance criteria. It is raised as an open question in
[`../../docs/plan/CORE-04.md`](../../docs/plan/CORE-04.md) § 8 with a recommendation rather than
folded into a package that already delivers more than the ticket describes. CORE-05 needs it.

## Approach

1. Comment creation with server-side mention extraction and Markdown sanitising.
2. Edit stamps `edited_at` and preserves authorship; delete is a tombstone.
3. Review submission with round allocation, refusing the author and the assignee.
4. Finding resolution feeding the closure gate from CORE-03.

## Decisions taken while implementing

Full reasoning in [`../../docs/plan/CORE-04.md`](../../docs/plan/CORE-04.md). Five changed what
shipped.

**Sanitising escapes every `<`, with no exemption for code — and arriving there took four review
rounds.** Raw HTML is not permitted in a comment body (`skills/secure-coding.md`), and a tag needs a
`<`, so every one becomes `&lt;` and renders as a literal character in a text node. No dependency,
no parser, nothing to classify. Unlike stripping it can never empty a body and fail V3's
`body_present_unless_deleted` at the insert, and it is idempotent, which matters because an edit
re-sanitises and the read path re-sanitises again.

**The four rounds are the decision worth reading.** The first version exempted inline code spans and
fenced blocks; rounds 1 to 3 each produced a body the scanner called code and a renderer called live
HTML, and each round's fix was defeated by the next. Round 3 named the mechanism: *refusing* to
treat a marker as an opener is not the conservative act it looks like, because the refused marker's
CommonMark partner is still there for a later marker to pair with — so the scanner ends up marking
as code a stretch the renderer reads as a tag. Spans were dropped and only fences kept. Round 4 then
demonstrated the identical mechanism **on fences**, twice, from three-line bodies — a closing fence
indented four spaces, and an opening fence indented one — and showed that patching the indentation
rules opens a third leak inside a list item. A line scanner that does not model block containers
cannot be made to agree with CommonMark by adding rules, and this is `:domain`, which is
framework-free, on the path that decides whether a script tag is stored. So the exemption is gone
rather than repaired a fourth time.

**The price is real, stated and asserted:** a `<` in a code sample is stored and shown as `&lt;`,
inline and in a fence alike, and a handle in a code sample is a mention. Each has its own test. URL
schemes in link destinations are still not filtered. The right home for all three is the sanitising
renderer the same skill assumes and that does not exist yet — raised as an open end with a
recommendation in [`../../docs/plan/CORE-04.md`](../../docs/plan/CORE-04.md) § 8, not decided here.

**Sanitising cannot be skipped, because `CommentBody` has no other constructor.** `CommentBody.of`
is the only way to make one, and it sanitises and then applies V3's non-blank rule to the text that
will actually be stored. The use cases take a `CommentBody`, so the boundary parses rather than
checks (`skills/secure-coding.md` § Input validation).

**Round allocation locks the ticket row.** `review` is `unique (ticket_id, round)`, so two
submissions that both compute `max + 1` collide on the index rather than becoming rounds 2 and 3.
The lock is the first statement, exactly as CORE-03's allocator is — and the paired negative had to
be built to distinguish the two: without the lock the second submission **fails**, so the assertion
is on the successful round numbers rather than on the absence of a collision.

**The closure gate's visibility probe became a lock, and this package is why.** CORE-04 is the first
package that can write a blocking finding, which makes a real race reachable: a submission that
commits between the gate's read and the transition's write closes a ticket as `done` carrying an
unresolved blocking finding. `JdbcClosureFacts` now reads
`select 1 from ticket where id = ? for update`, so the gate's three reads and the transition's write
are serialised against a submission on that ticket. CORE-03 rejected a row lock across the gate on
cost grounds; the cost is one ticket's closures queueing behind three indexed reads, and it is worth
paying now that the thing it excludes can happen. `ResolveFinding` deliberately takes no lock —
resolving *removes* a blocker, so its race leaves the gate refusing, which is the direction it is
allowed to be wrong in.

**Every read is project-scoped, and that is the sharpest guard here.** `EditComment`,
`DeleteComment` and `ResolveFinding` take an id; `ListComments` and `ListReviews` take a key, and
`ticket` is `unique (project_id, key)` so two projects may both own `x-1`. Row-level security admits
every project in the caller's session while the permission check is about one of them, so without
`and t.project_id = ?` a caller permitted in project A could edit a comment in project B, or be
handed B's thread and review record under its own key, merely by belonging to both. **Phase 4 found
that the key-addressed half of this had no paired negative at all** — every fixture project derived
its prefix from its own id, so no test could put one key in two projects, and the clause was
deletable from four statements with the suite green. The fixture takes an explicit prefix now.

## Acceptance criteria

- [x] An agent comment is stored, threaded and returned identically to a human comment; the only
      difference in the response is `author.kind`. One parameterised spec drives a human author and
      an agent author through the same assertions — storage, threading, the returned thread — so a
      divergence cannot hide in a branch only one of them takes.
- [x] Raw HTML in a comment body is sanitised; a test covers a script payload. Every `<` is escaped
      with no exemption, so the property holds by construction rather than by a rule. Pinned three
      ways: the six payloads directly; a regression corpus of the thirty-four shapes that carried a
      payload past this file in one of the four phase-4 rounds, crossed with all six; and
      `CommentThreadTest` asserting on the stored column that nothing survives, in prose and inside
      a fence alike. Idempotence is asserted over the whole corpus, because the read path
      re-sanitises. **Four rounds broke the versions that did have an exemption**, each defeating
      the previous round's fix — the corpus is kept as the record of what they found, not because
      any single entry can still single out a defect.
- [x] Submitting a review as the assignee is refused, for both actor kinds. `ReviewRecordTest` runs
      the case twice, once with a human assignee and once with an agent, and the refusal is a value
      (`NotIndependent`) rather than V3's trigger arriving as an exception. The reporter half of R1
      and its asymmetry are covered beside it.
- [x] A round-2 verdict contradicting round 1 leaves both readable; nothing is collapsed. Proved by
      "a round-2 verdict contradicting round 1 leaves both readable", and watched red with the
      reader changed to `order by round desc limit 1`.
- [x] An unresolved blocking finding from round 1 still blocks closure after a clean round 2. The
      closure is refused, the finding is named in the refusal, resolving it lets the same closure
      through, and an unresolved *non-blocking* finding never held anything.
- [x] `make check` green — `make PY=py check`, exit 0, all four lanes; 561 backend tests, 0
      failures, 0 skipped (252 before this package). 250 of the 309 added are the sanitiser's:
      6 payloads directly, 204 as 34 shapes crossed with those 6, and 40 idempotence cases over the
      same bodies.
- [x] Independent review: 0 BLOCKING findings — five rounds, recorded below.

## Affected files

- `backend/domain/src/main/kotlin/ai/nodera/domain/collaboration/` — `CommentMarkdown.kt`,
  `Comment.kt`, `Review.kt`.
- `backend/domain/src/main/kotlin/ai/nodera/domain/actor/ActorSummary.kt` — new; the author or
  reviewer as a reader is shown them. In `domain/actor/` because that is where the type belongs, not
  because of the invariant linter's allowance for that path.
- `backend/application/src/main/kotlin/ai/nodera/application/collaboration/` — the ports, the sealed
  results, the shared audit vocabulary, and the seven use cases.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/collaboration/` —
  `JdbcCommentRepository.kt`, `JdbcMentionDirectory.kt`, `JdbcReviewRepository.kt`,
  `CollaborationRows.kt`.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/ticket/JdbcClosureFacts.kt` — the
  visibility probe becomes a lock, and the three reads are lifted into an `internal` function so the
  test stand-in that models this adapter without the lock differs from it in exactly one statement.
- `backend/persistence/src/main/kotlin/ai/nodera/persistence/Binding.kt` — a `text[]` binder.
- Tests: `:domain` for the pure rules, `:persistence` for everything a database decides.
- `docs/plan/CORE-04.md`, `docs/plan/README.md`, `docs/docs_map.md`.

**One deviation, and it is CORE-02's, inherited unchanged.** The integration tests live in
`:persistence` rather than in `:application`, because a use case's transaction is only watched for
audit completeness there.

## Verification

`./gradlew :domain:test :persistence:test`. The concurrency cases use real connections against
Testcontainers; the deterministic races release their holder only when Postgres itself reports a
backend blocked on the statement the race is about, which is CORE-03's shape reused.

**Guards watched going red, one at a time, each restored afterwards.** Twenty-two runs over
twenty guards — the sanitiser and the independence rule are each driven in both modules — by a
harness rather than by hand, each running the specs that claim the guard. Every one went red on the
case named beside it:

| Guard disabled | Went red |
|---|---|
| the sanitiser stops escaping | 213 cases in `:domain`, and the stored-column case in `:persistence` |
| mention extraction stops de-duplicating | "a handle repeated in one body is mentioned once" |
| `CommentBody` stops refusing a blank body | "a blank body is refused before any statement runs" |
| reviewer independence always answers `Independent` | 2 cases in `:domain`, 4 in `:persistence` |
| the mention directory stops de-duplicating | "two spellings of one handle mention that actor once" |
| the mention directory stops scoping to project members | "mentions are extracted once per actor and only for members of the project" |
| the reply-scope check always passes | "a reply to a comment on another ticket is refused as a value" |
| the edit's author check always passes | the edit case |
| the edit stops being conditional on the tombstone | the tombstone case |
| the tombstone stops being conditional | the tombstone case and the concurrent-delete case |
| the comment read stops being project-scoped | "a caller permitted in one project cannot edit or delete a comment in another" |
| the finding read stops being project-scoped | "a caller permitted in one project cannot resolve a finding in another" |
| the ticket-visibility probe stops being project-scoped | "a caller scoped to two projects reads only the project it asked for" |
| the thread read stops being project-scoped | the same case |
| the review-record read stops being project-scoped | the same case |
| the resolution stops being a compare-and-set | the concurrent-resolution case and the already-resolved case |
| the round allocator stops locking the ticket | the deterministic round race |
| the review record collapses to the latest round | "a round-2 verdict contradicting round 1 leaves both readable" |
| the gate stops locking the ticket row | "a closure racing an open review submission waits for it and is refused" |
| the visibility probe always answers yes | the empty-versus-invisible case for both readers |

**One clause is deliberately absent from that table, and it is named rather than quietly kept.** The
project clause on `FINDINGS_BY_TICKET` is **not** load-bearing: `rounds` groups findings by
`review_id` and reads back only the reviews the round query returned, so another project's rows are
loaded and then never looked up. Deleting it leaves the suite green, which was measured rather than
reasoned. It stays because it keeps the read from loading them at all and because a reader that
stopped grouping would need it — and it is described as what it is, not as a guard.

**The harness that produced that table was wrong twice, and both times it reported success.** It is
recorded rather than quietly fixed. The first run said every guard was green: it could not find
`gradlew.bat`, so nothing ran, and it answered a missing results directory with "no failures" — the
empty-versus-unknown confusion this package exists to prevent, committed by the file whose job was
to catch it. The second run, after that was fixed to answer *unknown*, still called eighteen green:
it matched test cases with a regular expression that treated a self-closing `<testcase/>` — a
**passing** case — as an opening tag and ran on to the next `</testcase>`, so every red name was
attributed to the case before it. Parsed as XML, they all go red.

**Nothing was wrong with the guards.** CORE-02's defects were in the completeness check, CORE-03's
were in the race that proved the lock, and this package's were in the harness that proved the races
— the same failure one level further out each time.

**And one test was wrong in the same direction, which the harness then caught.** The cross-project
case added in phase 5 asserted only on finding titles, and findings are grouped by review id — so a
leak in the *rounds* query was invisible to it and that guard came back green. It now asserts on the
rounds themselves.

**Four review rounds all landed on the same mechanism, and the answer in the end was to remove it.**
Round 1 found two inputs the sanitiser did not hold for; the fix was pinned by those two inputs, and
round 2 walked around it. Round 2's fix added two more rules; round 3 walked around those and named
the reason — refusing to treat a marker as an opener is not conservative, because the refused
marker's partner is still there for a later one to pair with. Inline spans were dropped and fences
kept; round 4 then produced the same failure **on fences**, from three-line bodies, and showed that
patching the indentation rules opens a third leak inside a list item. Eight blocking findings, all
in one function, every fix defeated by the next round. The exemption is gone: the function no longer
needs to be right about a construct it cannot parse, and what it costs is `&lt;` in code samples.

## Review result

**2026-09-03 · Five rounds, each in a fresh sub-agent context.**

| Round | Verdict | Findings |
|---|---|---|
| 1 | CHANGES REQUIRED | 2 BLOCKING, 9 NON-BLOCKING |
| 2 | CHANGES REQUIRED | 2 BLOCKING, 4 NON-BLOCKING |
| 3 | CHANGES REQUIRED | 2 BLOCKING, 3 NON-BLOCKING |
| 4 | CHANGES REQUIRED | 3 BLOCKING, 5 NON-BLOCKING |
| 5 | APPROVED | 0 BLOCKING, 8 NON-BLOCKING |

All thirty-eight findings are fixed rather than deferred. Every round after the first reviewed the
previous round's fixes, and rounds 2 to 5 each reproduced guard experiments from *Verification*
rather than taking them on trust — round 5 re-ran fifteen of the twenty rows itself.

**Nine of the nine blocking findings were in one function**, and that is the fact this package is
worth remembering for. Every one was the sanitiser calling prose *code*, so a `<` reached the
`comment.body` column; every round's payload was a shape the previous round's fix had not
considered. Round 1: a triple-backtick span opening a fence, and a code span crossing a blank line.
Round 2: the same class through a heading, a quote, a list item and a lone `\r`, plus a
backslash-escaped run. Round 3: **the reason** — refusing to treat a marker as an opener is not
conservative, because the refused marker's CommonMark partner is still available for a later marker
to pair with, so declining to recognise a construct can *create* a mis-classification. Round 4:
the identical mechanism on fences, from three-line bodies, plus a demonstration that patching the
indentation rules opens a third leak inside a list item.

**The fix in the end was to delete the thing, not to repair it a fifth time.** There is no code
exemption; every `<` is escaped. The property is now structural rather than a rule, and the price —
`&lt;` in code samples, a mention from a handle in code — is asserted by its own tests and raised
in the plan's § 8 as a decision for the maintainers, with the sanitising renderer named as its
proper home.

**Two findings were about a guard rather than the code it guards, which is this repository's
recurring shape.** The key-addressed project-scope clause had no paired negative at all — every
fixture project derived its ticket prefix from its own id, so no test could put one key in two
projects, and the clause was deletable from four statements with the suite green. And the harness
that produced the guard table reported success twice while being wrong: first because it could not
find `gradlew.bat` and answered a missing results directory with "no failures" — the
empty-versus-unknown confusion this package exists to prevent, in the file whose job was to catch
it — and then because it matched test cases with a regular expression that read a self-closing
`<testcase/>` as an opening tag and filed each failure under the preceding, passing case's name.

**Round 5's eight non-blocking findings were mostly siblings of corrected claims** — a Files table
still advertising the deleted scanner, a `three rounds` that should have said four, a read-path KDoc
still arguing from a scanner that no longer exists, and "each writing exactly one audit event" said
of seven use cases when two of them are reads that write one only on refusal. Two were real gaps:
the denial branches of `ListComments` and `ListReviews` were unexercised, and `mentionedHandles`
carried an emptiness filter that could not execute. Both are fixed, and the two denials now assert
`outcome = 'denied'` like every other refusal in the package.
