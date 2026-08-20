---
id: CORE-05
title: Markdown ticket import and export with round-trip fidelity
priority: P3
status: open
effort: ~2 d
depends_on: [CORE-04]
created: 2026-08-20
updated: 2026-08-20
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

## Approach

1. Parser for the frontmatter and the body sections, tolerant of the variation real files contain.
2. Exporter producing byte-identical output for an unmodified import.
3. Mapping for acceptance criteria and the review record, which is the part a naive
   implementation loses.
4. A property test over generated tickets rather than a single fixture.

## Acceptance criteria

- [ ] `import(export(t))` is semantically equal to `t`, review history included, proved by a
      property test over generated tickets.
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
