---
id: DOC-01
title: Deployment guide and the self-hosting path
priority: P3
status: open
effort: ~1 d
depends_on: [API-01, WEB-01]
created: 2026-08-20
updated: 2026-08-23
---

# DOC-01 · Deployment guide and the self-hosting path

**Priority:** P3
**Effort:** ~1 d

## Motivation / context

Nodera is self-hostable, which is only true if someone who has never seen the repository can stand
it up. Until that is written and followed by someone else, it is an intention.

## Current state (honest)

`README.md` documents `make dev` for a development machine.

OPS-03 has since delivered the *operator* half: [`docs/ops/deploy.md`](../../docs/ops/deploy.md)
(install, verification, update, rollback, the role split, the secret files) and
[`docs/ops/backup-restore.md`](../../docs/ops/backup-restore.md), whose restore was executed rather
than described. What is left is the half that needs an application to point at: a guide someone who
has never seen this repository can follow end to end.

## Approach

1. `docs/DEPLOYMENT.md`: prerequisites, TLS, reverse proxy, the MCP HTTP transport, and the shape of
   a first login — the narrative an operator reads once, linking to `docs/ops/` for the procedures
   rather than restating them.
2. Confirm the ops runbook against a **real host**. OPS-03's rehearsal was local; the deploy runbook
   says so in its own § What is proved and what is not, and that section is what this ticket closes.

## Acceptance criteria

- [ ] Someone who has not worked on the project follows the guide and reaches a running instance;
      what tripped them up is fixed in the guide, not explained to them.
- [ ] The guide links to `docs/ops/` for install, update and restore instead of restating them.
- [ ] The host-specific entries in the deploy runbook's "not proved on a real host" list are
      retired against an actual host — the secret-file permissions, the reboot, the reverse proxy.
      The image-registry and `arm64` entries belong to OPS-02 and stay there.
- [ ] `docs/DEPLOYMENT.md` is linked from `docs/INDEX.md` in the same commit.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `docs/DEPLOYMENT.md` — new. `docs/INDEX.md` — one curated link.
- `docs/ops/deploy.md` — its § What is proved and what is not, corrected by what the host shows.

## Verification

A person other than the author follows the guide on a clean machine. That is the only verification
that means anything here.
