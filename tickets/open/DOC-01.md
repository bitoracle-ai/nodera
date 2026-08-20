---
id: DOC-01
title: Deployment guide and the self-hosting path
priority: P3
status: open
effort: ~1 d
depends_on: [API-01, WEB-01]
created: 2026-08-20
updated: 2026-08-20
---

# DOC-01 · Deployment guide and the self-hosting path

**Priority:** P3
**Effort:** ~1 d

## Motivation / context

Nodera is self-hostable, which is only true if someone who has never seen the repository can stand
it up. Until that is written and followed by someone else, it is an intention.

## Current state (honest)

`README.md` documents `make dev` for a development machine. There is no production guide, no
role-and-password setup, no backup or upgrade procedure. `V4` creates `nodera_app` with a
placeholder nobody has been told to replace.

## Approach

1. `docs/DEPLOYMENT.md`: prerequisites, the two database roles, secret generation, TLS, reverse
   proxy, the MCP HTTP transport.
2. Backup and restore, with a restore that is actually performed once and recorded.
3. Upgrade procedure, given migrations are forward-only.

## Acceptance criteria

- [ ] Someone who has not worked on the project follows the guide and reaches a running instance;
      what tripped them up is fixed in the guide, not explained to them.
- [ ] The role split is explicit: migrations run as the owner, the application as `nodera_app`.
- [ ] The restore procedure has been executed at least once and the result recorded.
- [ ] `docs/DEPLOYMENT.md` is linked from `docs/INDEX.md` in the same commit.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `docs/DEPLOYMENT.md` — new. `docs/INDEX.md` — one curated link.

## Verification

A person other than the author follows the guide on a clean machine. That is the only verification
that means anything here.
