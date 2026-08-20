---
id: CORE-04
title: Comments, mentions and the review record
priority: P2
status: open
effort: ~2 d
depends_on: [CORE-02, CORE-03]
created: 2026-08-20
updated: 2026-08-20
---

# CORE-04 · Comments, mentions and the review record

**Priority:** P2
**Effort:** ~2 d

## Motivation / context

Comments and reviews are two of the five first-class capabilities. The review record in particular
carries the requirement that is hardest to retrofit: rounds are append-only and a contradicting
verdict stays visible.

## Current state (honest)

The schema carries `comment`, `comment_mention`, `review` and `review_finding` with their triggers.
No services exist.

## Approach

1. Comment creation with server-side mention extraction and Markdown sanitising.
2. Edit stamps `edited_at` and preserves authorship; delete is a tombstone.
3. Review submission with round allocation, refusing the author and the assignee.
4. Finding resolution feeding the closure gate from CORE-03.

## Acceptance criteria

- [ ] An agent comment is stored, threaded and returned identically to a human comment; the only
      difference in the response is `author.kind`.
- [ ] Raw HTML in a comment body is sanitised; a test covers a script payload.
- [ ] Submitting a review as the assignee is refused, for both actor kinds.
- [ ] A round-2 verdict contradicting round 1 leaves both readable; nothing is collapsed.
- [ ] An unresolved blocking finding from round 1 still blocks closure after a clean round 2.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/domain/src/main/kotlin/ai/nodera/domain/collaboration/`.
- `backend/application/src/main/kotlin/ai/nodera/application/collaboration/`.

## Verification

`./gradlew test`. The cross-round test is the important one — it is the exact case a naive
"latest review" implementation gets wrong.
