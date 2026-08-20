---
id: MCP-03
title: MCP resources and prompts
priority: P3
status: open
effort: ~1 d
depends_on: [MCP-02, CORE-05]
created: 2026-08-20
updated: 2026-08-20
---

# MCP-03 · MCP resources and prompts

**Priority:** P3
**Effort:** ~1 d

## Motivation / context

Resources give an agent context rather than query results, and prompts make a workflow reproducible
across runtimes instead of re-invented per contributor.

## Current state (honest)

MCP-01 and MCP-02 deliver the tool surface. No resources, no prompts.

## Approach

1. Resources: project list, project overview, backlog in working order, ticket as Markdown, thread.
2. Prompts: `start_work_package`, `review_work_package`, `close_work_package`.
3. The ticket resource reuses the CORE-05 exporter — one serialiser, not two.

## Acceptance criteria

- [ ] The ticket resource output is byte-identical to the file exporter for the same ticket.
- [ ] Resource visibility follows the same permission checks as the tools.
- [ ] Each prompt is exercised end to end by at least one MCP client.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/api-mcp/src/main/kotlin/ai/nodera/api/mcp/resources/` and `.../prompts/`.

## Verification

`./gradlew :api-mcp:test` plus a manual client session reading each resource.
