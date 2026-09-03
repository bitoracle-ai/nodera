# Plan — CORE-04 · Comments, mentions and the review record

**Status:** `implemented`
**Ticket:** [`../../tickets/closed/CORE-04.md`](../../tickets/closed/CORE-04.md)
**Invariants this implements:** #9 (the reviewer is not the author; rounds are append-only) and the
write side of #8 (the closure gate finally has something that feeds it) —
[`../../skills/critical-invariants.md`](../../skills/critical-invariants.md)

---

## 1. What phase 1 found

`V3` carries `comment`, `comment_mention`, `review` and `review_finding` with every trigger DB-01
proved: the reply-scope trigger, the R1 independence trigger, the review append-only pair, and the
transitive row-level-security policies that reach the project through `ticket`. Nothing above them
exists.

CORE-03 left four things that bind this package rather than merely informing it, and one that this
package is the first to be able to break.

1. **`ClosureGate` already reads `acceptance_criterion`, `review` and `review_finding` across all
   rounds.** So `review_submit` and `finding_resolve` do not re-implement gate logic; they write the
   rows, and the gate responds. `JdbcClosureFacts` reads findings by joining through `review`, with
   no round filter at all, which is what makes AC5 a property of the existing reader rather than
   something this package has to add.
2. **Every mutation goes through `UnitOfWork.inTransaction { … }` plus `AuditRecorder.record`, and
   `:persistence`'s completeness harness enforces it** — one audit row per mutating transaction, no
   more and no fewer. So the integration tests live in `:persistence`, and a use case that writes a
   review plus three findings writes **one** event, not four.
3. **The transaction rides in the coroutine context**; adapters read it with `currentConnection()`.
   `RequestId` is a `Uuid`.
4. **`nodera.project_ids` is still unestablished** (SEC-01's seam). An unscoped caller is refused at
   the policy, loudly, and this package does not work around it.

**And the new one: this is the first package that can write a blocking finding**, which makes a race
between the closure gate and a review submission reachable for the first time. § 4.4.

## 2. The four properties that carry the package

**A comment is a comment.** One table, one author column, one code path. Nothing anywhere reads
`actor.kind` to decide how to store, thread or return a comment — the reader is *told* the kind and
never left to infer it (invariant CM2). Proved by driving the identical assertions over a human
author and an agent author from one parameterised spec, so a divergence cannot hide in a branch
only one of them takes.

**Raw HTML never survives a write.** The body is Markdown and raw HTML is not permitted in it
(`skills/secure-coding.md` § Input validation). Every `<` is escaped, with no exemption for code:
§ 4.1 records what four review rounds cost to establish that a hand-rolled scanner cannot decide
where code begins, and what the exemption's removal costs in return.

**Rounds are append-only and a contradiction stays visible.** There is no "current verdict" anywhere
in this package: no `latest`, no `order by round desc limit 1`, no collapse. The reader returns every
round in ascending order, and the spec that proves it is red against a reader that returns only the
last one.

**An empty answer and an unknown answer are never the same value.** This is CORE-03's hazard, and
every read this package adds has the same shape: a thread nobody can see returns zero rows, and zero
comments is exactly what a ticket with no discussion looks like. Every reader here therefore returns
a nullable aggregate — `null` is *not visible* — and every use case maps it to a `NotFound` case
distinct from the empty one. § 4.5.

## 3. The shape

```
  :domain (pure)                    :application                     :persistence
  ┌────────────────────┐          ┌────────────────────┐           ┌────────────────────────┐
  │ CommentMarkdown    │◀─────────│ CreateComment      │──ports──▶ │ JdbcCommentRepository  │
  │  sanitiseMarkdown  │          │ EditComment        │           │ JdbcMentionDirectory   │
  │  mentionedHandles  │          │ DeleteComment      │           │ JdbcReviewRepository   │
  │ Comment            │          │ ListComments       │           └────────────────────────┘
  │ Review             │          │ SubmitReview       │
  │ reviewerIndependence          │ ResolveFinding     │
  └────────────────────┘          │ ListReviews        │
                                  └────────────────────┘
```

`:domain` decides; `:application` sequences the decision, the permission check and the audit row
inside one transaction; `:persistence` reads and writes rows and takes locks. Exactly CORE-03's
arrangement, deliberately — a second arrangement for the same problem is how two of anything start.

## 4. Decisions taken here

### 4.1 Sanitising is an escape, not a strip, and it makes no exception for code

`skills/secure-coding.md` says raw HTML is not permitted in comment bodies. Three ways to honour
that, and the choice matters:

| Approach | Rejected because |
|---|---|
| Strip tags with an HTML sanitiser library | A runtime dependency for a problem that is not HTML parsing. `:domain` is framework-free, and a stripping sanitiser can empty a body — which then violates V3's `body_present_unless_deleted` at the insert rather than at the boundary. |
| Refuse a body containing HTML | The ticket says *sanitised*. Refusing also punishes the honest author who typed `a < b`. |
| **Escape every `<` to `&lt;`** | Chosen — after four review rounds killed the version that made an exception for code. |

A raw HTML tag needs a `<`. Every one becomes `&lt;`, which a Markdown renderer emits as a literal
`<` **character in a text node** — visible, inert, and not a tag. There is no exemption for code,
and that is the design: with nothing to classify there is nothing to classify wrongly.

**The exemption is what four rounds of phase 4 destroyed, and the history is the argument.** The
first version left code alone, recognising inline backtick spans and fenced blocks. Rounds 1 to 3
each produced a body the scanner called code and a renderer called live HTML — a fence opening that
swallowed the rest of the body, a span crossing a blank line, a span crossing a heading or a lone
`\r`, a span opening on part of a longer run, a backslash-escaped run — and each round's fix was
defeated by the next.

Round 3 named the mechanism, and it is general: **a marker this scanner declines to treat as an
opener leaves its CommonMark partner unconsumed, a later marker pairs with that partner instead, and
the scanner marks as code a stretch the renderer reads as a tag.** So "recognise fewer constructs"
is not the conservative act it appears to be. Spans were dropped and fences kept, on the reasoning
that a fence is found by scanning lines from its own opening and so has no partner to leave behind.
**Round 4 showed that reasoning was wrong too**, from three-line bodies: a closing fence indented
four spaces is block *content* to CommonMark, so the scanner closes early and reads the real closing
fence as a new opener; an opening fence indented one space is a legal opener to CommonMark and not
to this scanner, so the scanner opens on the *closing* line instead. Both store a live `<script>`.
Patching the indentation rules then opens a third leak inside a list item, because CommonMark's
fence columns are relative to the container.

The conclusion is structural rather than a run of bad luck: a line scanner that does not model block
containers cannot be made to agree with CommonMark by adding rules, and agreeing with CommonMark is
what the exemption requires. This is `:domain` — framework-free, no parser available — on the path
that decides whether a script tag is stored. The exemption is gone.

**`&` is deliberately not escaped.** Nothing can become a tag without a `<`, so escaping `&` buys no
safety and costs the author's literal `&amp;`.

**The transformation is idempotent, and that is a tested property rather than an observation.**
`sanitise(sanitise(x)) == sanitise(x)` — because the output contains no `<` at all, and the second
pass therefore finds nothing to change. An edit re-sanitises the stored body and the read path
re-sanitises again, so a non-idempotent transformation would eat one more character of the author's
text on every pass.

**Three limits, stated rather than left to be discovered.**

- **A `<` in a code sample is stored and shown as `&lt;`**, inline and in a fence alike. This is the
  price of the paragraphs above, it is the one real regression in this package, and it has its own
  test so that changing it is a decision.
- **A handle in a code sample is a mention.** The same gap with the polarity reversed — an unwanted
  notification rather than an unescaped tag, which is why it is acceptable where the equivalent was
  not for the sanitiser.
- **URL schemes in Markdown link destinations are not filtered.** A link whose destination is a
  `javascript:` URL passes through this package untouched. It is a real vector and it is *not*
  closed here: closing it belongs with the renderer that the same skill requires ("rendered with a
  sanitising renderer"), and no renderer exists yet. Naming it is the point — a sanitiser that stops
  `<script>` and silently passes `javascript:` reads as protection while the same door stands open.
  Carried as an open end (§ 8).

**The stored body is the sanitised one.** The original is not retained. One transformation, at the
one point of entry, so every later reader — REST, MCP, the CORE-05 exporter — gets a safe body
without each re-deriving what safe means.

### 4.2 Mentions are extracted from the text that is actually stored

Server-side, on write, into `comment_mention` — never re-parsed on read, because a mention drives
notification and "was this actor notified?" must not depend on the parser version
(`docs/DOMAIN_MODEL.md` § 6).

- **The grammar is defined here**, which is where `Handle`'s own documentation says it belongs:
  `@` followed by a letter or digit, then letters, digits, `_`, `-` or `.`, and the `@` must not be
  preceded by a word character. That last clause is what stops `user@example.com` producing a
  mention of `example.com`.
- **Trailing punctuation is not part of a handle.** `@anna.` mentions `anna`. Sentence-final periods
  are commoner than handles ending in one.
- **A handle in a code sample is a mention.** Nothing here knows what code is any more (§ 4.1),
  and the cost of that gap is an unwanted notification rather than an unescaped tag.
- **Extraction runs on the sanitised body, not on the input.** The rows must describe the text that
  was stored; anything else records a mention of something no reader can see. (Sanitising cannot
  create or destroy a mention today — it only touches `<` — so the ordering is currently unobservable
  and is fixed anyway, because the next change to either function would make it observable.)
- **Duplicates are collapsed.** `comment_mention` is keyed `(comment_id, actor_id)`, so `@anna @anna`
  without a dedupe is a primary-key violation on the second row — an exception where the answer is
  one row. Order of first appearance is preserved so the rows read as the text does.
- **Only actors with a membership in the ticket's project resolve.** A mention of a non-member would
  notify someone who cannot open the ticket it is on, and leave a row that no reader of that comment
  can ever resolve. `JdbcMentionDirectory` joins `project_membership`, which is itself
  row-level-security protected, so the boundary is the database's and not a `where` clause somebody
  has to remember.
- **An unresolved handle is not an error.** It is text that looks like a mention and matches nobody;
  the comment is created with the mentions that did resolve. There is no row type for a handle
  without an actor, and inventing one is CORE-05's problem at the earliest.

### 4.3 Round allocation locks the ticket row

`review` is `unique (ticket_id, round)`. Two reviewers submitting at the same moment both compute
`max(round) + 1`, both get 2, and one of them gets a unique violation — an exception, on a path where
the right answer is round 3.

So allocation locks the parent row first — `select id from ticket where id = ? for update` — and only
then reads `coalesce(max(round), 0) + 1`. There is no sequence table to lock, and there should not
be: rounds are per ticket, dense, and derived from the rows that exist, which is exactly the case
`ticket_sequence` exists to *avoid* for keys (a key must survive deletion; a round has nothing that
can be deleted, because `review` refuses `delete`).

**CORE-03's lesson applies directly and was checked rather than assumed.** Its race passed with
`for update` deleted because the contender was blocking on a unique index instead. Here the unique
index also exists, so the paired negative has to distinguish the two: without the lock the second
submission **fails** with a unique violation, and with it the second submission **succeeds as round
2**. The assertion is on the successful round numbers, not on the absence of a collision, so an
allocator that merely collides is red rather than green. The deterministic race additionally
releases its holder only on a wait naming `from ticket where id`, so a contender blocked on
something else cannot release it early.

### 4.4 The gate's reads take the same lock, and this package is why

Before CORE-04 nothing could write a `review_finding`, so the closure gate read a table only tests
wrote. That changes here, and it makes a race reachable:

1. A reads the ticket, runs the gate — no unresolved blocking findings — and decides to close.
2. B submits round 2 carrying a blocking finding, and commits.
3. A's compare-and-set still matches, because B never touched the `ticket` row. The ticket closes
   as `done` with an unresolved blocking finding, which is precisely what invariant #8 forbids.

The fix is one word in CORE-03's adapter: `JdbcClosureFacts`'s visibility probe becomes
`select 1 from ticket where id = ? for update`, so the gate's three reads and the transition's write
are serialised against a concurrent review submission **on that ticket only**. CORE-03 rejected
holding a row lock across the gate's reads on cost grounds; the cost it feared is one ticket's
closures queueing behind one another for the duration of three indexed reads, and it is worth paying
now that the thing it excludes can actually happen.

`ResolveFinding` deliberately does **not** take the lock. Resolving *removes* a blocker, so the race
it has is A refusing a closure that B has just unblocked — stale, but refusing, which is the direction
the gate is allowed to be wrong in. Its own lost-update race is handled by § 4.5's compare-and-set.

### 4.5 Every read-then-write is a compare-and-set, and every reader answers "not visible" distinctly

CORE-03 was forced into a compare-and-set after review. This package starts there, because comments
and findings are written by more than one actor **by design**.

| Write | Condition in the `where` | What the condition stops |
|---|---|---|
| `EditComment` | `deleted_at is null` | Editing a tombstone back into a comment. |
| `DeleteComment` | `deleted_at is null` | A second deleter overwriting the first's identity and timestamp, so the trail names the wrong actor. |
| `ResolveFinding` | `resolved_at is null` | A second resolver overwriting the first's note and provenance — the same lost update CORE-03 shipped a fix for. |

Zero rows updated is then ambiguous between *not visible* and *already in the target state*, and the
two are different answers. Each use case therefore **reads first, project-scoped** (`null` → a
`NotFound` result) and treats the compare-and-set's zero rows as the state answer
(`AlreadyDeleted` / `AlreadyResolved`). Two reads rather than one, on purpose: collapsing them
would tell a caller that their comment does not exist when someone else has just deleted it.

**The project-scoped read is also the cross-project guard**, and it is the sharpest thing in this
package. `EditComment`, `DeleteComment` and `ResolveFinding` take an id. Row-level security admits
every project in the caller's session, and the permission check is against **one** project — so
without a scope in the query, a caller permitted in project A could edit a comment in project B
merely by being a member of both. Every one of these reads therefore carries
`and t.project_id = ?` from the authenticated context (invariant #5), and the paired negative drives
exactly that: a caller with both projects in scope and `comment.create` in A is refused a comment in
B, and the row is untouched.

### 4.6 Reviewer independence is restated in the domain, not delegated to the trigger

Invariant #9 says "enforced in the domain service", and `V3`'s trigger is the floor beneath it. The
rule is the trigger's, exactly:

- the reviewer is never the **assignee**;
- when the ticket has **no** assignee, the reviewer is not the **reporter** either.

The asymmetry is deliberate and DB-01 already pins it: with an assignee, the reporter is not the
author of the work and is a legitimate reviewer. Restating it in `:domain` means a refusal is a
*value* the caller can render — `SubmitReviewResult.NotIndependent` — rather than a `SQLException`
arriving from a trigger two layers down, which is the LSP failure `skills/backend-kotlin.md` names.
Deleting the domain rule turns the integration cases red, because they assert the sealed result.

**Nothing in the rule reads `actor.kind`** (invariant #1). It compares identity, and the spec runs
every case twice — human reviewer and agent reviewer — so a kind branch introduced later fails the
half it was not written for.

### 4.7 Which capability each verb checks

From `docs/MCP.md` § 3, which is normative. Two are not in that table and are decided here:

| Use case | Capability | Note |
|---|---|---|
| `CreateComment` | `comment.create` | `comment_create`. |
| `ListComments` | `comment.read` | `comment_list`. |
| `SubmitReview` | `review.submit` | `review_submit`. |
| `ResolveFinding` | `review.submit` | `finding_resolve`. |
| `ListReviews` | `ticket.read` | `review_list` — the review record is part of reading the ticket. |
| `EditComment` | `comment.create`, **and the caller must be the author** | Editing is authoring. Nobody edits another actor's words under that actor's name; `V3` says authorship is never rewritten, and an edit by a third party is that rewrite in everything but the column. |
| `DeleteComment` | `comment.create` for one's own, `comment.moderate` for another's | The two verbs that exist, used for the two cases that exist. |

No new capability verb is invented. `comment.update` would be a change to `docs/MCP.md`'s normative
table and to the role defaults, which is a specification decision rather than an implementation one
(§ 8).

## 5. Files

| File | Change |
|---|---|
| `backend/domain/src/main/kotlin/ai/nodera/domain/collaboration/CommentMarkdown.kt` | new — `sanitiseMarkdown` and `mentionedHandles`. It carried a code-segment scanner through four review rounds; § 4.1 records why there is none now. |
| `backend/domain/src/main/kotlin/ai/nodera/domain/collaboration/Comment.kt` | new — `CommentId`, `CommentBody`, `CommentContent`, `Comment`. |
| `backend/domain/src/main/kotlin/ai/nodera/domain/actor/ActorSummary.kt` | new — the author or reviewer as a reader is shown them, carrying the kind for display. |
| `backend/domain/src/main/kotlin/ai/nodera/domain/collaboration/Review.kt` | new — `ReviewId`, `ReviewRound`, `ReviewVerdict`, `FindingId`, `FindingDraft`, `Finding`, `Review`, `reviewerIndependence`. |
| `backend/application/src/main/kotlin/ai/nodera/application/collaboration/CollaborationPorts.kt` | new — the narrow ports the seven use cases need. |
| `backend/application/src/main/kotlin/ai/nodera/application/collaboration/CollaborationResults.kt` | new — the sealed results. |
| `.../collaboration/usecase/CreateComment.kt` · `EditComment.kt` · `DeleteComment.kt` · `ListComments.kt` | new. |
| `.../collaboration/usecase/SubmitReview.kt` · `ResolveFinding.kt` · `ListReviews.kt` | new. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/collaboration/JdbcCommentRepository.kt` | new — insert, project-scoped read, the two compare-and-sets, the thread read, the mention rows. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/collaboration/JdbcMentionDirectory.kt` | new — handles to actors, inside the project. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/collaboration/JdbcReviewRepository.kt` | new — the locking round allocator, the review and finding writes, the round read, the finding compare-and-set. |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/ticket/JdbcClosureFacts.kt` | edited — the visibility probe becomes the lock (§ 4.4). |
| `backend/persistence/src/main/kotlin/ai/nodera/persistence/Binding.kt` | edited — a `text[]` binder, so the mention directory can pass a list of handles without building SQL. |
| Tests | `:domain` for the pure rules, `:persistence` for everything a database decides. Nothing in `:application`: every branch these use cases have is reachable against a real database, so there is no case here of the kind CORE-03 needed `TransitionTicketTest` for. |
| `docs/plan/README.md` · `tickets/` | the catalogue row and the closure record. |

## 6. Test plan

Each line names the criterion it carries and the guard it is red without.

| Test | Proves | Red when |
|---|---|---|
| an agent's comment and a human's are stored, threaded and returned identically but for `author.kind` | AC1 | any path branches on kind |
| a reply is threaded under its parent, and a reply to another ticket's comment is refused | AC1 | the parent's ticket is not checked |
| a `<script>` payload is escaped, and the stored body contains no `<` at all | AC2 | the sanitiser is bypassed |
| every shape that once carried a payload past this file is escaped, all 34 of them | AC2 | any code exemption returns |
| a code sample's `<` is escaped too, inline and in a fence | AC2 | the stated price stops being paid |
| `sanitise(sanitise(x)) == sanitise(x)` over the whole corpus | AC2 | the transformation stops being idempotent |
| a body that is only whitespace is refused before any statement runs | AC2 | `CommentBody` stops restating V3's rule |
| `@anna @anna` writes one mention row | mentions | the dedupe is dropped |
| `user@example.com` writes no mention; `@bot` in a code sample writes one | mentions | the grammar is weakened, or the stated gap silently closes |
| a mention of an actor who is not a member of the project resolves to nothing | § 4.2 | the membership join is dropped |
| an edit stamps `edited_at` and leaves `author_actor_id` unchanged | approach 2 | authorship is rewritten |
| an edit by an actor who is not the author is refused, with the row untouched | § 4.7 | the author check is dropped |
| a delete blanks the body, keeps the row and its thread position, and names the deleter | approach 2 | the tombstone becomes a delete |
| a second delete answers `AlreadyDeleted` and does not rewrite the first deleter | § 4.5 | the `deleted_at is null` clause is dropped |
| an edit of a deleted comment answers `AlreadyDeleted` | § 4.5 | the same clause is dropped |
| a caller scoped to two projects cannot edit, delete or resolve in the project it lacks permission in | § 4.5 | `and t.project_id = ?` is dropped from any of the three |
| the assignee cannot submit a review — human assignee and agent assignee, one case each | AC3 | the independence rule is dropped |
| the reporter of an **unassigned** ticket cannot; the reporter of an assigned one can | AC3 | the rule stops matching V3's trigger |
| round 2 contradicting round 1 leaves both readable, in order, with both verdicts | AC4 | any reader collapses to a current verdict |
| two concurrent submissions produce rounds 1 and 2, both succeeding | § 4.3 | `for update` is dropped — the loser then fails on the unique index |
| the same race against a deliberately unlocked allocator collides | § 4.3 | the lock stops being load-bearing |
| an unresolved blocking finding from round 1 still blocks closure after a clean round 2 | AC5 | the gate is given a round filter |
| resolving it lets the same closure through; a non-blocking one never blocked | AC5 | severity or resolution is ignored |
| two concurrent resolutions leave one `Resolved`, one `AlreadyResolved`, and one resolver on the row | § 4.5 | `resolved_at is null` is dropped |
| a review submitted while a closure holds the gate's lock cannot land after the gate has read | § 4.4 | `for update` is dropped from the visibility probe |
| a thread on a ticket the caller cannot see answers `NotFound`, not an empty thread | § 4.5 | the reader returns a list instead of a nullable one |
| the review record of an invisible ticket answers `NotFound`, not "no reviews" | § 4.5 | the same |
| a comment, a review and a finding resolution each write exactly one audit event, in their own transaction | #3 | the recorder call is dropped (the harness refuses the commit) |
| a denied create, edit, delete, submit and resolve each write `outcome = 'denied'` and mutate nothing | #3 | a denial path skips the recorder |
| an unscoped comment create is refused by the policy and writes no mention rows | #5 | `comment` loses its row-level security |
| every `ReviewVerdict` and `FindingSeverity` value is a label its column accepts, in the same order | — | an enum gains a value the column refuses |

Concurrency cases use real connections from the audited data source, one per transaction, against
Testcontainers — CORE-03's `race`/`awaitContention` shape, reused rather than re-derived.

## 6.1 Every guard watched going red

One experiment per guard, each disabling it, running the specs that claim it, and restoring — the
table in the ticket's *Verification* has one row each, with the counts stated there. Three things
are worth carrying here.

**The first two runs of that harness reported every guard green, and both times the harness was
wrong.** It could not find `gradlew.bat`, so nothing ran — and it answered an absent result
directory with "no failures", which is the empty-versus-unknown confusion in § 2 committed by the
very file that exists to catch it. Fixed to answer `None` for a run that did not happen. Then it
still reported most of them green, because it matched test cases with a regular expression that
treated a self-closing `<testcase/>` — a **passing** case — as an opening tag and ran on to the next
`</testcase>`, collecting the following failure into the wrong name. Parsed as XML now.

**Nothing was wrong with the guards then.** The lesson is the one this repository keeps re-learning
at a level further out each time: CORE-02's defects were in the completeness check, CORE-03's were
in the race that proved the lock, and CORE-04's were in the harness that proved the races. A checker
that cannot fail is worth exactly as much as a guard that cannot fire.

**And the third instance of it was in the tests, not the harness.** Phase 4's first round found two
inputs the sanitiser did not hold for; the fix was pinned by exactly those two inputs, and the
second round walked around it with a heading, a quote, a list item, a CR line ending and a
backslash. A test written against the input that exposed a defect proves the defect is gone and
nothing else. The sanitiser is now pinned by a corpus asserting one sentence — no angle bracket
survives any shape a renderer does not read as code — and that corpus found two further holes on its
first run, one in the scanner and one in the corpus's own idea of what a renderer does.

## 7. Deliberate non-goals

- **No `criterion_set`.** `docs/plan/CORE-03.md` § 7 assigned it here, and it is not in CORE-04's
  approach or its acceptance criteria. This package is already the larger half of what the ticket
  describes; adding the third gate input would push it further, and a package delivered whole is
  worth more than one delivered wide. Raised in § 8 with a proposal, and named in the ticket rather
  than left as a surprise for CORE-05.
- **No notification delivery.** `comment_mention` records who was mentioned. Turning a row into a
  notification needs a transport, and there is none.
- **No REST or MCP surface.** API-01 and MCP-01. Nothing here is reachable from a running process,
  exactly as after CORE-03.
- **No comment search, no pagination.** `comment_list` is specified as the thread, oldest first.
  Cursor pagination is a surface concern with a contract in `docs/API_CONTRACT.md`.
- **No `JdbcPermissionDirectory`, no project-context establishment.** CORE-01's port and SEC-01's
  seam, unchanged.
- **No repository-wide comment sweep.** Only the regions this package edits.

## 8. Open questions

**1. `criterion_set` has no package.** The closure gate reads three inputs and this package writes
two of them. `acceptance_criterion` is still written only by tests, so a ticket cannot be closed as
`done` through the domain by any route that does not seed rows by hand.
**Recommendation:** a small follow-up package — one use case (`ticket.update`), one adapter, the
`met_carries_provenance` rule restated, and the same compare-and-set shape as § 4.5. It is CORE-05's
dependency in practice, because an importer that reads a ticket file has to write its criteria.

**2. Markdown safety belongs in the renderer, and the renderer does not exist.** `skills/secure-coding.md`
says Markdown "is rendered with a sanitising renderer"; there is none, so this package escapes on the
write side instead — and four review rounds were spent on the one thing a write-side escape cannot
do without a Markdown parser, namely decide what is code. Two consequences are still open: a link
destination of `javascript:…` reaches storage untouched, and a `<` in a code sample is escaped like
any other, inline and in a fence alike (§ 4.1).
**Recommendation:** when WEB-01/WEB-02 lands a renderer, put both halves there — scheme filtering
and code-aware escaping — and reduce this to what a write-side rule can be right about. That is a
decision about what a comment *is* and belongs in a ticket rather than in this diff.

**3. Editing has no capability verb of its own.** § 4.7 uses `comment.create` plus an author check,
because inventing `comment.update` changes `docs/MCP.md`'s normative tool table and the role
defaults. **Recommendation:** leave it. The author check is the real rule, and a verb that only ever
appears alongside `comment.create` would add a row to two documents and decide nothing.
