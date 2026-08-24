---
id: OPS-03
title: "Operate the production surface: deploy runbook and a restore that has been walked"
priority: P2
status: closed
effort: ~1 d
depends_on: []
created: 2026-08-23
updated: 2026-08-24
closed: 2026-08-24
note: The production topology ships without a procedure for running it or getting the data back.
---

# OPS-03 · Operate the production surface: deploy runbook and a restore that has been walked

**Priority:** P2
**Effort:** ~1 d
**Skills:** `critical-invariants.md`, `database-design.md`

## Motivation / context

`compose.prod.yml` is a production topology: an application container, a Postgres with a `pgdata`
volume, file-based secrets, a role split between migrator and server. `release.yml` publishes the
image it names. What is missing is everything an operator does with it after `up -d` — updating,
rolling back, and getting the data back after the volume is gone.

The repository is honest about the reasoning and silent about the procedure. ADR-0007 states that
per-customer backup and restore is a *consequence* of the deployment model; that is a design
statement, not something anyone can follow. Nothing else in the tree mentions backup or restore at
all, and there is no `docs/ops/`.

A restore that has never been executed is not a control. This package therefore does not end at
writing the procedure — it ends at running it, against the real image and the real compose file,
and correcting whatever the run proves wrong.

## Current state (honest)

- No `docs/ops/`. No deploy procedure, no upgrade path, no rollback, no backup, no restore.
- `docker-compose.yml` line 1 points a reader at `docs/DEPLOYMENT.md`, which does not exist. So does
  ADR-0006. The pointer has been dangling since OPS-01, which recorded it as a known gap.
- The only occurrences of "backup" or "restore" in the tree are two lines in ADR-0007 describing
  per-customer backup as a property of the tenancy model.
- `compose.prod.yml` itself carries usage in a header comment: two `openssl rand` lines and one
  `docker compose up -d`. That is a first install and nothing else.
- DOC-01 (P3, blocked on API-01 and WEB-01) plans a self-hosting guide and lists "backup and
  restore, with a restore that is actually performed once" among its steps. It is blocked behind an
  API and a frontend that do not exist, while the production surface exists now. OPS-02 will cut the
  first release, which is the moment the compose file reaches somebody else's host.

## Approach

1. `docs/ops/deploy.md` — the operator runbook for `compose.prod.yml`: what the host needs, first
   install, reaching it, updating, rolling back, when something is wrong, what must never happen.
2. `docs/ops/backup-restore.md` — what is in the `pgdata` volume, what a `pg_dump` does *not* carry,
   how to take a backup, how to restore it, and the drill.
3. Walk the restore locally with Docker against `compose.prod.yml` and the real image: bring the
   stack up, migrate, insert recognisable rows, back up, destroy the volume, restore, prove the rows
   came back. Record the transcript in the ticket and correct the procedure from it.
4. Register both documents in `docs/INDEX.md` and in `ALLOWLIST_DOCS`, repoint the dangling
   `docker-compose.yml` pointer, and narrow DOC-01 to what is left of it.

## ⚠️ To decide before starting

- **Scripts or prose?** Recommendation: prose with exact commands, no new shell scripts. oracleai
  has `backup_db.sh`/`restore_db.sh` because it has a host, a timer and an off-box repository to
  drive. Nodera has none of those yet and no scheduled backup to hang a script off. A script nobody
  runs on a timer is a second thing to keep true. Revisit when OPS-02 puts an instance somewhere.
- **Relationship to DOC-01.** Recommendation: this package owns the *operator* procedure for the
  topology that already exists; DOC-01 keeps the *self-hosting guide* — prerequisites, TLS, reverse
  proxy, the MCP transport — and links here for backup and restore rather than restating them.

## Acceptance criteria

- [x] `docs/ops/deploy.md` exists and covers first install, verification, update, rollback,
      diagnosis, and the boundaries the topology must keep.
- [x] `docs/ops/backup-restore.md` states what the backup covers and, explicitly, what it does not.
- [x] The restore has been **executed**, against `compose.prod.yml` and a locally built image, and
      the transcript is recorded in this ticket — including what the run proved wrong.
- [x] Both documents distinguish what was rehearsed locally from what remains unproven on a real
      host, in the document itself and not only in this ticket.
- [x] Both documents are reachable from `docs/INDEX.md`; `python scripts/lint_docs_index.py` green.
- [x] `docker-compose.yml` no longer points at a file that does not exist.
- [x] DOC-01 no longer claims the backup and restore work that this package delivered.
- [x] `make check-repo` and the doc gates green. The backend, frontend and database lanes are
      **not** run in this session — see § Verification.
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.

## Affected files

- `docs/ops/deploy.md` — new.
- `docs/ops/backup-restore.md` — new.
- `docs/INDEX.md` — one curated section; both documents reachable.
- `scripts/_common.py` — the two new documents added to `ALLOWLIST_DOCS`, so their frontmatter is
  gated rather than merely present.
- `docker-compose.yml` — the header pointer, currently naming a file that does not exist.
- `compose.prod.yml` — the header's digest advice, which the rehearsal proved does not work as
  spelled. The file ships with the release, so the correction belongs where the operator reads it.
- `docs/PROJECT_MANAGEMENT.md` — § 6 gains the `OPS-` prefix it has been using since OPS-01.
- `tickets/open/DOC-01.md` — scope narrowed to what this package does not deliver.

## Verification

The restore transcript below is the verification. It is a **local rehearsal**: same image, same
compose file, same migrations, same role split — a laptop, one host, one volume, and no off-box
copy. What that does not prove is stated in both documents.

## Restore rehearsal — transcript

Windows 11 workstation, Docker 29.7.2, image built from `c1fc3ef` with
`docker build --build-arg VERSION=0.0.0-local -t nodera:local .`. Run under `-p nodera-drill`,
because `compose.prod.yml` declares `name: nodera` and the development `docker-compose.yml` inherits
the same project name from the directory — without `-p`, the drill would have destroyed the
developer's `nodera_pgdata` volume. That collision is the first thing walking it found.

**Up, and the version variable.** `up -d` succeeded; the next command did not:

```
$ docker compose -p nodera-drill -f compose.prod.yml logs migrate
error while interpolating services.migrate.image: required variable NODERA_VERSION is missing a
value: set NODERA_VERSION to a released version
```

Every subcommand re-interpolates the file, `logs` and `down` included. Exporting the variable for
`up` is not enough; it belongs in `.env`. Documented in `docs/ops/deploy.md` § First install.

**Migrated and seeded.**

```
migrate-1  | Successfully applied 5 migrations to schema "public", now at version v5
migrate-1  | Applied 5 migration(s); schema is current.
$ curl -s http://127.0.0.1:8080/health/ready
{"status":"ready","version":"0.0.0-local","detail":"schema is current"}
$ psql … -c "…"
project=RESTORE-CANARY-2026-08-23
actor=restore-canary
audit_rows=1
```

**Backup.**

```
$ docker compose … exec -T postgres pg_dump -U nodera_owner -d nodera --format=custom > drill.dump
$ ls -l drill.dump   → 89057 bytes
$ pg_restore -l drill.dump
;     TOC Entries: 238   Format: CUSTOM   Dumped from database version: 16.15
```

**Volume destroyed.** `down`, then `docker volume rm nodera-drill_pgdata`; `docker volume inspect`
confirms it is gone. The developer's `nodera_pgdata` was untouched throughout.

**The restore failed the first time — this is the finding.** Fresh volume, Postgres up, restore
straight into the empty database, which is the procedure anyone would write:

```
pg_restore: error: could not execute query: ERROR:  role "nodera_app" does not exist
Command was: CREATE POLICY acceptance_criterion_visible ON public.acceptance_criterion TO nodera_app …
--- pg_restore exit 1 ---
```

`nodera_app` is a **cluster-level** role. `pg_dump` of one database does not carry it, and a
destroyed volume is a destroyed cluster. `--exit-on-error` stopped at the first policy, leaving the
schema half-created, so the retry also needed the database replaced:

```
$ psql -U nodera_owner -d postgres -c "drop database nodera" -c "create database nodera owner nodera_owner"
DROP DATABASE
CREATE DATABASE
```

**The sequence that works.** `migrate` first — not for the schema, which is about to be replaced,
but because `V4` creates `nodera_app` with the password from `secrets/app_role_password`:

```
$ docker compose … up migrate
migrate-1  | Applied 5 migration(s); schema is current.
$ docker compose … exec -T postgres pg_restore -U nodera_owner -d nodera \
    --clean --if-exists --exit-on-error < drill.dump
--- pg_restore exit 0 ---   (no output)
```

**The data came back, and so did the guarantees:**

```
project=RESTORE-CANARY-2026-08-23
actor=restore-canary
audit_rows=1
flyway=5
app_role_exists=1
rls_policies=14
audit_grants=INSERT,SELECT
triggers=3
$ psql -c "update audit_event set outcome='failed'"
ERROR:  audit_event is append-only (invariant AU1)
CONTEXT:  PL/pgSQL function audit_event_is_append_only() line 3 at RAISE
```

Row counts alone would not have shown the last four lines. A restore that returned the audit rows
but not the `INSERT,SELECT` grant and the three triggers would have silently downgraded AU1 from a
privilege to a convention.

**End to end.**

```
$ docker compose … up -d
migrate-1  | Schema "public" is up to date. No migration necessary.
migrate-1  | Applied 0 migration(s); schema is current.
$ curl -s http://127.0.0.1:8080/health/ready
{"status":"ready","version":"0.0.0-local","detail":"schema is current"}
```

`Applied 0` proves the restored Flyway history is coherent with the image, and `ready` proves the
server authenticated as `nodera_app` — with a password that came from the secret file, never from
the dump.

**Also rehearsed:** update (`NODERA_VERSION` → `local2`, `up -d`, container recreated, still ready)
and rollback (back to `local`, same). And two boundary properties: `docker compose ps` shows
Postgres as `5432/tcp` with no host binding, and `nodera` as `0.0.0.0:8080->8080/tcp` — the app is
published on **every interface**, which `docs/ops/deploy.md` § Reaching it now states plainly.

**Two smaller findings, both documented:**

- `compose.prod.yml`'s header advises pinning a digest. Spelled the obvious way it produces
  `ghcr.io/bitoracle-ai/nodera:@sha256:…`, an invalid reference; the digest has to be split across
  the colon the template already provides. Checked with `docker compose config --images`.
- The image carries **no OCI labels** (`.Config.Labels` is `null`), so "which build is running" has
  exactly one answer: the `version` field of the health endpoints.

**Cleanup:** `down -v`, drill volume removed, `.env` and `secrets/` deleted, the `local2` tag
removed. `docker volume ls` shows `nodera_pgdata` and `nodera-gradle-cache` as before.

## What was not run

`make check-repo` is green, in full, and so are the doc and ticket gates — run on this tree, on this
machine. The **backend, frontend and database lanes were not run locally**: this workstation has no
JDK, which is why the image was built through Docker instead.

They did not run on CI against this tree either — it is uncommitted as this is written, so no run
has ever seen this content. On the base commit `0396642`, `Backend (Kotlin)` and `Database
(migrations + schema rules)` passed while **`Frontend (React)` failed**, taking `CI Gate` with it:
`yarn install --frozen-lockfile` rejects a lockfile computed before the other frontend major landed
([WEB-03](WEB-03.md)). That failure predates this package, touches no file it changes, and is not
this package's to fix. Nothing here claims a lane it did not run.

## Review result

Two independent sub-agent rounds, each against the staged diff.

**Round 1** — 2 BLOCKING. (a) § What was not run claimed the backend, frontend and database lanes
"ran on CI against the same tree"; CI had never seen this tree, and the frontend lane was red on the
base commit. Rewritten to say exactly what ran where. (b) § Rolling back contradicted
[ADR-0006](../../docs/adr/0006-one-image-three-entrypoints.md) § 4 — "Rollback is a rollback of the
image, never of the database" — and would have steered an operator into restoring a dump, discarding
every write since the upgrade, in the case expand/contract exists to make loss-free. Rewritten
around the ADR. Seven NON-BLOCKING fixed in the same pass: the `compose.prod.yml` digest advice the
rehearsal disproved, the release asset as the install route, the `.env` / `.env.example` collision,
the one-directional Flyway claim, `docker login` without a producer, the DOC-01 criterion
overshooting into OPS-02's territory, and the duplicated git wrapper.

**Round 2** — 1 BLOCKING, and it was a defect introduced by a round-1 fix. The secrets line read
`chown root:100`, putting the image's **uid** in the **group** position nine lines above the
document's own `gid=101`. Under `0640` that resolves to *other* — `---` — so a Linux operator
following § First install verbatim would have had `migrate` die on an unreadable secret and the
stack never start: the exact failure the paragraph promised to prevent. Fixed to `root:101`, with
the numbers now labelled as alpine allocation artefacts to re-check with `id` rather than as a
contract.

That fix was verified directly rather than argued, in a Linux volume where permission bits are real:

```
$ docker run --rm -v sec-test:/s alpine sh -c "printf secret > /s/pw; chmod 640 /s/pw; chown 0:100 /s/pw"
$ docker run --rm -v sec-test:/run/secrets --entrypoint sh nodera:local -c 'cat /run/secrets/pw'
cat: /run/secrets/pw: Permission denied
$ docker run --rm -v sec-test:/s alpine sh -c "chown 0:101 /s/pw"
$ docker run --rm -v sec-test:/run/secrets --entrypoint sh nodera:local -c 'test -r /run/secrets/pw && echo readable'
readable
```

Three NON-BLOCKING fixed with it: the readability check printed a live credential into the
operator's shell history *and* could not run in the situation it existed to detect (`exec` needs a
container that the failure prevents from starting — now `run --rm` with `test -r`); § When something
is wrong had no row for an unreadable secret and routed the operator to the wrong cause; and two `§`
cross-references named headings that do not exist, which `lint_docs_index.py` cannot catch because
it validates files and not anchors. The "not run" list was also widened to cover `git clone`, the
`/opt/nodera` paths and the `chmod`/`chown` pair.

**Honest note on the third round:** there was none. The round-2 BLOCKING was fixed and then proved
by the container test above rather than by a further review pass. The test is stronger evidence than
a re-read would have been for that particular claim, but it is not a review of the fix's prose, and
the prose changed too.

Deferred deliberately, not ticketed: no `docs/plan/OPS-03.md` (§ 10's "≥ ~1 day" threshold has no
precedent either way in this repository — OPS-01 at ~3 d has one, CI-01 at ~0.5 d does not), and the
dangling `docs/DEPLOYMENT.md` pointers in ADR-0006 and `V4` are left standing on purpose: an ADR is a
record and an applied migration is forward-only, and DOC-01 still delivers that file.
