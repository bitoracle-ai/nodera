---
id: CORE-05
title: Markdown ticket import and export with round-trip fidelity
priority: P3
status: open
effort: ~2 d
depends_on: [CORE-04]
created: 2026-08-20
updated: 2026-09-03
---

# CORE-05 · Markdown ticket import and export with round-trip fidelity

**Priority:** P3
**Effort:** ~2 d

## Motivation / context

This repository's own backlog lives in Markdown, and so does the reference workflow Nodera is
designed against. An adoption path that drops the review history asks teams to discard the most
expensive thing they have. It is also the acceptance test for self-hosting.

## Current state (honest)

The format is specified in `docs/DOMAIN_MODEL.md` section 10 and demonstrated by every file in
`tickets/`. No importer or exporter exists.

Comment bodies are sanitised on construction (CORE-04): `CommentBody.of` is the only constructor,
it escapes every `<`, and the maintainers decided on 2026-09-03 that this stays until a renderer
exists. An importer can therefore promise byte-identity only for the bodies the sanitiser leaves
unchanged — the second criterion below says what it promises for the rest.

## Approach

1. Parser for the frontmatter and the body sections, tolerant of the variation real files contain.
2. Exporter producing byte-identical output for an unmodified import — measured through the
   sanitiser: byte-identical where it leaves a body unchanged, convergent where it does not (the
   second criterion).
3. Mapping for acceptance criteria and the review record, which is the part a naive
   implementation loses.
4. A property test over generated tickets rather than a single fixture.

## Acceptance criteria

- [ ] `import(export(t))` is semantically equal to `t`, review history included, proved by a
      property test over generated tickets.
- [ ] The file round trip is measured through the sanitiser, never around it: for a file whose
      bodies the sanitiser leaves unchanged, `export(import(f))` is byte-identical to `f`; for a
      body the sanitiser changes, `import` stores the sanitised form and the round trip converges
      on it in one step — `export(import(f))` differs from `f` in that body alone, and re-importing
      that export reproduces it byte for byte. No importer path constructs a body other than through
      the constructor that sanitises (`CommentBody.of` today). The property test generates bodies of
      both kinds.
- [ ] Importing this repository's own `tickets/` directory succeeds for every file.
- [ ] The MCP ticket resource returns byte-identical output to the file exporter.
- [ ] A malformed file is reported with the file and the reason, never silently skipped.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/application/src/main/kotlin/ai/nodera/application/interchange/`.

## Verification

`./gradlew :application:test`, plus importing `tickets/` and diffing the re-export against the
originals — an empty diff is the acceptance evidence.
