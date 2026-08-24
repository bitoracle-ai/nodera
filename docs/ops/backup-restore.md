---
summary: Backup and restore for the Nodera pgdata volume — what a dump does and does not carry, the restore sequence that was actually walked, and the drill that proves it.
read_when:
  - Before the first install, together with the deploy runbook — the restore has prerequisites you must create up front.
  - When restoring after data loss, a bad migration, or a move to a new host.
  - When rehearsing the drill, or when a restore fails partway.
---

# Backup and restore (OPS-03)

Everything Nodera cannot reproduce lives in one Postgres volume: actors, projects, tickets, the
credential hashes, and `audit_event`, which is append-only and therefore has no second copy anywhere.
[ADR-0007](../adr/0007-deployment-is-the-tenant-boundary.md) makes backup a per-deployment
responsibility; this file is how that responsibility is discharged.

Running the instance is [`deploy.md`](deploy.md).

> **A backup nobody has restored is not a backup.** The sequence in § Restore is not a design; it is
> what was run, in the order it worked, after the obvious version of it failed. § The drill has the
> transcript.

## What a dump covers — and what it does not

`pg_dump` of the `nodera` database carries the schema, the data, the Flyway history table, the RLS
policies, the append-only triggers on `audit_event`, and the privilege grants. All of that was
confirmed present after the rehearsal restore.

It does **not** carry:

| Not in the dump | Why it matters | Where it lives |
|---|---|---|
| The `nodera_app` role | Roles are cluster-level, not database-level. A dump restored onto a fresh cluster hits `role "nodera_app" does not exist` on the first `CREATE POLICY`. | Recreated by `migrate` from `secrets/app_role_password` — § Restore step 4. |
| `secrets/app_role_password` | Without it the restored database has a role the application cannot authenticate as. Nothing in the dump can reconstruct it. | `./secrets/`, git-ignored, **back it up separately**. |
| `secrets/db_owner_password` | A fresh volume initialises `nodera_owner` from this file. A different value gives you a database you cannot open as the owner. | Same. |

So a complete backup is **three things**: the dump, and both secret files. Store the secrets
somewhere other than the host, and not in the same place as the dump if you can avoid it — the dump
also contains credential hashes, and the two together are the whole system.

## Backup

From the deployment directory, with the stack running:

```sh
mkdir -p /var/backups/nodera
docker compose -f compose.prod.yml exec -T postgres \
  pg_dump -U nodera_owner -d nodera --format=custom \
  > "/var/backups/nodera/nodera-$(date -u +%Y%m%dT%H%M%SZ).dump"
```

`-T` matters: without it Compose allocates a TTY and the binary stream is corrupted on its way to
the file. `--format=custom` is what makes selective and parallel restore possible later.

**Check the file rather than the exit code**, every time:

```sh
docker run --rm -i -v /var/backups/nodera:/b postgres:16-alpine \
  pg_restore -l /b/nodera-20260823T205445Z.dump | head
```

A readable table of contents naming the archive format, the source database and a TOC entry count is
the smallest honest proof that the stream survived. A dump that is 0 bytes, or truncated, exits the
pipeline just as quietly as a good one.

Then copy the file off the host. Nothing here does that for you.

### What is not decided yet

Stated rather than implied, because a backup section reads as a system and this one is not:

- **No schedule.** There is no timer, no unit, no cron entry, and no script in this repository. The
  RPO is "whenever a human last ran the command above", which is not a number.
- **No off-box target and no retention policy.** Both are deployment decisions
  ([ADR-0007](../adr/0007-deployment-is-the-tenant-boundary.md)) and none is made here.
- **No monitoring.** A backup that stopped running fails silently.

Scripting and scheduling this belongs with the first real deployment, where there is a host to hang
a timer on — [OPS-02](../../tickets/open/OPS-02.md) is the package that first puts one somewhere.

## Restore

The target is an **empty cluster**: a new host, or the same host after the volume is gone. Steps 3
and 4 are the ones people skip, and step 4 is the one that makes the difference between a restore
and an error message.

**1. Stop the stack.**

```sh
docker compose -f compose.prod.yml down
```

**2. Provide an empty volume.** On a new host there already is one. To discard a damaged volume:

```sh
docker volume rm nodera_pgdata
```

**3. Start Postgres alone.** `initdb` creates the `nodera` database and the `nodera_owner`
superuser from `secrets/db_owner_password`.

```sh
docker compose -f compose.prod.yml up -d postgres
docker compose -f compose.prod.yml exec -T postgres \
  sh -c 'until pg_isready -U nodera_owner -d nodera >/dev/null 2>&1; do sleep 1; done; echo ready'
```

**4. Run `migrate` — for the role, not for the schema.**

```sh
docker compose -f compose.prod.yml up migrate
```

This looks redundant, since step 5 replaces every object it just created. It is not: `V4` creates
`nodera_app` with the password from `secrets/app_role_password`, and that role must exist before the
dump's policies and grants can reference it. Skipping this step is the failure in § The drill.

**5. Restore over it.**

```sh
docker compose -f compose.prod.yml exec -T postgres \
  pg_restore -U nodera_owner -d nodera --clean --if-exists --exit-on-error \
  < /var/backups/nodera/nodera-20260823T205445Z.dump
```

`--clean --if-exists` drops each object before recreating it from the dump, which is what makes it
safe to run onto the schema step 4 just built. `--exit-on-error` stops at the first failure instead
of continuing past it; a restore that reports problems and carries on leaves a database that looks
restored and is not. Success is silent and exits `0`.

**6. Bring the stack up.**

```sh
docker compose -f compose.prod.yml up -d
```

`migrate` runs again and must report `Applied 0 migration(s); schema is current.` If it wants to
apply something, stop and find out why before letting the server start.

That line is necessary, not sufficient. Flyway is configured with `ignoreMigrationPatterns` at its
default, so a history row *above* the highest migration in the image counts as a future version and
is left alone — restoring a newer dump onto an older image prints exactly the same success line and
then runs old code against a newer schema. Check the dump's release against the image's, in that
case, rather than the log line.

**7. Verify — the data and the guarantees.**

```sh
docker compose -f compose.prod.yml exec -T postgres psql -tA -U nodera_owner -d nodera \
  -c "select count(*) from audit_event" \
  -c "select count(*) from pg_policies where schemaname='public'" \
  -c "select string_agg(privilege_type,',' order by privilege_type)
        from information_schema.table_privileges
       where grantee='nodera_app' and table_name='audit_event'"
```

Expect your row count, **14** policies, and exactly `INSERT,SELECT` on `audit_event`. The last one
is the point: `audit_event` being append-only is a privilege and a trigger, not a convention, and a
restore that brought back the rows but not the grant would have quietly downgraded the guarantee.
Confirm the trigger too — this must be refused:

```sh
docker compose -f compose.prod.yml exec -T postgres psql -U nodera_owner -d nodera \
  -c "update audit_event set outcome='failed'"
```

```
ERROR:  audit_event is append-only (invariant AU1)
```

Finally `curl -s http://127.0.0.1:8080/health/ready` — `ready` means the server authenticated as
`nodera_app` against the restored database, which is the end-to-end answer.

## The drill

Run this on a workstation, against `compose.prod.yml` and an image built from the repository. **Use
a different project name**: `compose.prod.yml` declares `name: nodera`, the development
`docker-compose.yml` inherits the same name from the directory, and both resolve `pgdata` to
`nodera_pgdata` — so a drill run without `-p` destroys the development database.

```sh
docker build --build-arg VERSION=0.0.0-local -t nodera:local .
printf 'NODERA_IMAGE=nodera\nNODERA_VERSION=local\n' > .env
mkdir -p secrets
printf '%s' "$(openssl rand -base64 32)" > secrets/db_owner_password
printf '%s' "$(openssl rand -base64 32)" > secrets/app_role_password

docker compose -p nodera-drill -f compose.prod.yml up -d
# insert a recognisable row, take the dump, then:
docker compose -p nodera-drill -f compose.prod.yml down
docker volume rm nodera-drill_pgdata
# … § Restore, steps 3-7, with -p nodera-drill throughout
docker compose -p nodera-drill -f compose.prod.yml down -v      # clean up
```

On Git Bash under Windows, prefix `docker run … -v <dir>:/b …` with `MSYS_NO_PATHCONV=1`; without it
`/b` is rewritten to `B:/` and `pg_restore` reports a missing file that is not missing.

### Record — 2026-08-23

| Field | Value |
|---|---|
| Environment | Windows 11 workstation, Docker 29.7.2, `postgres:16-alpine` (server 16.15), image built locally from `c1fc3ef` |
| Dump | `--format=custom`, 89 057 bytes, 238 TOC entries, near-empty schema |
| RPO | Not applicable — no schedule exists |
| RTO | Not meaningful at this data volume; every step returned in under a second |
| Data recovered | Canary project, canary actor, `audit_event` row, Flyway history at v5 |
| Guarantees recovered | 14 RLS policies, 3 `audit_event` triggers, `INSERT,SELECT` grant, append-only refusal |
| Application after restore | `{"status":"ready","version":"0.0.0-local","detail":"schema is current"}` |
| Stumbling block | Step 4 omitted on the first attempt — see below |
| Paths | § Backup and § Restore are written for a host (`/var/backups/nodera`); the drill wrote its dump into the working directory. Same commands, different destination |

**What went wrong the first time.** Restoring straight into the fresh, empty database — the obvious
procedure, and the one this document would have contained if it had been written instead of run —
fails immediately:

```
pg_restore: error: could not execute query: ERROR:  role "nodera_app" does not exist
Command was: CREATE POLICY acceptance_criterion_visible ON public.acceptance_criterion TO nodera_app …
```

`--exit-on-error` stopped it there, having already created part of the schema. Recovering from that
half-restored state needs the database replaced before trying again:

```sh
docker compose -p nodera-drill -f compose.prod.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U nodera_owner -d postgres \
  -c "drop database nodera" -c "create database nodera owner nodera_owner"
```

With `migrate` run first, the same `pg_restore` command exits `0` with no output.

**What this drill does not prove:** anything about a real host, an off-box copy, a dump taken under
concurrent write load, a database large enough for the restore duration to be a number, or an image
pulled from `ghcr.io` — no release has been cut ([OPS-02](../../tickets/open/OPS-02.md)).

## When something is wrong

| Symptom | Cause / fix |
|---|---|
| `role "nodera_app" does not exist` during `pg_restore` | Step 4 was skipped. Replace the database (command above), run `migrate`, restore again. |
| `pg_restore` reports objects that already exist | `--clean --if-exists` was omitted, or the target was not empty. |
| The dump file is 0 bytes or `pg_restore -l` cannot read it | `-T` was missing from `docker compose exec`, so a TTY corrupted the stream. Take it again. |
| `migrate` wants to apply migrations after a restore | The dump is *older* than the image. Do not let the server start; check which release produced the dump. |
| `migrate` reports `Applied 0` but the app misbehaves | The dump may be *newer* than the image — a future history row is ignored, not reported. Compare the dump's release with the running version; the fix is the matching image, not another restore. |
| The app cannot log in after a restore | `secrets/app_role_password` differs from the one `V4` used when it created the role on this cluster. The role's password comes from that file, never from the dump — restore the file, or `alter role nodera_app password …` as the owner and change the file to match. |
| `pg_isready` never succeeds on a fresh volume | A trailing newline in `secrets/db_owner_password`. Rewrite with `printf '%s'` and start over — `initdb` only runs once per volume. |
