---
summary: Operator runbook for compose.prod.yml — what the host needs, first install, verification, updating, rolling back, diagnosis, and the boundaries the topology must keep.
read_when:
  - Before standing up an instance, or when changing one that is already running.
  - When an instance will not start, will not become ready, or is serving the wrong build.
  - Before an upgrade, and before a rollback — the two are not symmetric here.
---

# Runbook — running and updating Nodera (OPS-03)

`compose.prod.yml` is the production topology: Postgres with a `pgdata` volume, a `migrate`
container that runs to completion as the schema owner, and a server that connects as `nodera_app`
and holds no data-definition rights. The reasoning is
[ADR-0006](../adr/0006-one-image-three-entrypoints.md) and
[ADR-0007](../adr/0007-deployment-is-the-tenant-boundary.md); this file is the procedure.

Getting the data back is [`backup-restore.md`](backup-restore.md). Read it before the first install,
not after the first incident.

## What is proved and what is not

The procedure below was walked end to end on a Windows workstation, against `compose.prod.yml` and
an image built from this repository with `docker build --build-arg VERSION=0.0.0-local -t
nodera:local .`: the install, the readiness checks, the update, the rollback, and the full
backup-and-restore cycle in the companion document. The transcript is in
[OPS-03](../../tickets/closed/OPS-03.md).

Not every command here was run, and the exceptions are these. `gh release download` and
`docker login ghcr.io` need a release that does not exist. The `chmod`/`chown` pair in § First
install could not be exercised on Windows — see the last item below. And the rehearsal ran inside an
existing checkout, so `git clone` and the `/opt/nodera` paths are illustration rather than
transcript: the same procedure was walked, in the repository directory.

**A local rehearsal is not a production proof.** None of the following has been exercised anywhere:

- A real host. One laptop, one Docker daemon, no reboot, no firewall, no reverse proxy, no TLS.
- Pulling the image from `ghcr.io`. **No release has ever been cut** — `release.yml` has zero runs,
  which is what [OPS-02](../../tickets/open/OPS-02.md) exists to change. Every instruction here that
  names a published version describes a version that does not yet exist.
- `linux/arm64`. The release workflow is configured for two architectures; only `amd64` was built.
- Restarting after a host reboot, surviving a disk-full condition, or any concurrent load.
- **The host half of the secret-file permissions in § First install.** The *container* half is
  proven: a `0640` file owned `root:101` in a Linux volume is readable by the image's process and
  the same file owned `root:100` is not — run both ways, `Permission denied` and `readable`
  respectively. What was not exercised is the host half, because the rehearsal ran on Docker Desktop
  for Windows, where host permission bits do not survive the mount in the first place. On a Linux
  host, check readability before trusting this file — with `test -r`, not by printing the credential
  into your terminal and your shell history:

  ```sh
  docker compose -f compose.prod.yml run --rm --entrypoint sh nodera     -c 'test -r /run/secrets/app_role_password && echo readable'
  ```

  `run` rather than `exec`, deliberately: when the permissions *are* wrong, `migrate` fails and the
  server never starts, so `exec nodera` answers "not running" instead of the answer you asked for.

## What the host needs

- Docker with the Compose plugin.
- Outbound access to `ghcr.io`, and read access to the image, with a token carrying
  `read:packages`:

  ```sh
  cat ~/ghcr-token | docker login ghcr.io -u <github-user> --password-stdin
  ```

  `--password-stdin` and never a token as an argument: an argument lands in the shell history and
  in the process table.
- A way to terminate TLS in front of the application. The compose file publishes plain HTTP on
  `8080` — see § Reaching it, which is the part with the sharp edge.
- Disk for `pgdata` plus room for a dump beside it.

## First install

`compose.prod.yml` is a **release asset** — `release.yml` attaches it to every GitHub release — so
the file and the image it pins come from the same version:

```sh
mkdir -p /opt/nodera && cd /opt/nodera
gh release download v0.1.0 --pattern compose.prod.yml
```

Until [OPS-02](../../tickets/open/OPS-02.md) cuts the first release there is nothing to download,
and the interim route is a clone:

```sh
git clone https://github.com/bitoracle-ai/nodera.git /opt/nodera
```

Prefer the asset once it exists. A clone gives you `main`'s compose file while `NODERA_VERSION` pins
an older image, so the pin stops describing the deployment — and it puts the whole build tree on a
host where § What must never happen forbids building.

**The two secrets.** They are files, never environment variables, and the application reads them
through `*_FILE` variables so the values never appear in `docker inspect` or in a process listing:

```sh
cd /opt/nodera && mkdir -p secrets
printf '%s' "$(openssl rand -base64 32)" > secrets/db_owner_password
printf '%s' "$(openssl rand -base64 32)" > secrets/app_role_password
chmod 640 secrets/* && chown root:101 secrets/*
```

`printf '%s'`, not `echo`: a trailing newline becomes part of the password. `secrets/` is
git-ignored, and it must stay that way.

The ownership line is not decoration. Compose outside swarm bind-mounts a `file:` secret with the
host's own uid, gid and mode — `uid`/`gid`/`mode` on a secret are swarm-only and silently do nothing
here — and the application image runs as **uid 100, gid 101**, not root. A root-owned `0600` secret
is therefore unreadable by the process that needs it: `migrate` mounts both files and dies before
the server is allowed to start.

`101` is the **group**, and it is not the same number as the uid. Check both against the image you
are deploying rather than trusting this line — they are artefacts of the order alpine's `adduser -S`
happened to allocate ids in (`Dockerfile`: `addgroup -S nodera && adduser -S -G nodera nodera`,
then `USER nodera`), not a contract, and a base-image change can move them:

```sh
docker run --rm --entrypoint id ghcr.io/bitoracle-ai/nodera:0.1.0
```

```
uid=100(nodera) gid=101(nodera) groups=101(nodera)
```

That the image's process can read a `0640 root:101` file, and cannot read a `0640 root:100` one, was
tested directly and is not an inference. What is still unverified is the step before it: that a file
created with those bits **on a Linux host** arrives in the container unchanged through Compose's
`file:` secret. See § What is proved and what is not.

`secrets/app_role_password` is substituted into `V4`'s `create role nodera_app` the first time
`migrate` runs. From then on it is the credential the server authenticates with, and it is **not**
contained in a database dump — [`backup-restore.md`](backup-restore.md) § What a dump covers — and
what it does not. Back both files up now, somewhere other than this host.

**The version.** Put it in `.env` beside the compose file rather than exporting it:

```sh
printf 'NODERA_VERSION=0.1.0\n' > .env
```

This `.env` is Compose interpolation and nothing else. If you took the clone route you also have
`.env.example`, which is the **development** file and which `README.md` tells you to `cp` into
place — do not copy it over this one. Doing so removes `NODERA_VERSION`, and every subsequent
compose command then fails with the message the table below attributes to a lost export.

This is not a convenience. `NODERA_VERSION` is declared `:?`, so *every* `docker compose -f
compose.prod.yml …` invocation re-interpolates it — including `ps`, `logs` and `down`. Without
`.env`, a shell that has lost the export answers a request for the logs with
`required variable NODERA_VERSION is missing a value`, which reads like a broken configuration and
is not one. `.env` is git-ignored.

To pin a digest instead of a tag — which is what you want, since a tag can be repointed — note that
the compose file joins image and version with a literal `:`, so the obvious spelling does not work:

```sh
# WRONG — yields ghcr.io/bitoracle-ai/nodera:@sha256:… , an invalid reference
NODERA_VERSION=@sha256:2b1c…

# RIGHT — the digest is split across the colon the template already provides
NODERA_IMAGE=ghcr.io/bitoracle-ai/nodera@sha256
NODERA_VERSION=2b1c…
```

Verified with `docker compose -f compose.prod.yml config --images`; that checks the reference the
file produces, not that a registry serves it, because no image has been published yet.

**Start it.**

```sh
docker compose -f compose.prod.yml up -d
```

Order is enforced by the file, not by timing: Postgres must report healthy, then `migrate` runs to
completion as `nodera_owner`, and only then does the server start. A failed migration therefore
stops the deployment instead of producing a server talking to a half-built schema.

## Verifying

Three answers, and the third is the one that matters:

```sh
docker compose -f compose.prod.yml ps -a
```

`migrate` **must** read `Exited (0)`. It is a one-shot container; "exited" is success here and a
running `migrate` would be the anomaly.

```sh
docker compose -f compose.prod.yml logs migrate
```

The last line is `Applied N migration(s); schema is current.` On an install `N` is the full set; on
an update it is usually `0`, and `0` is not a warning.

```sh
curl -s http://127.0.0.1:8080/health/ready
```

```json
{"status":"ready","version":"0.1.0","detail":"schema is current"}
```

`version` here is compiled into the binary from the build-time `VERSION` argument, so it is the
authoritative answer to *which build is actually running*. It is not the tag you asked for, and it
is not recoverable from image metadata: **the image carries no OCI labels at all**
(`docker inspect --format '{{json .Config.Labels}}'` returns `null`), so unlike other services in
this portfolio there is no `org.opencontainers.image.version` to fall back on. Ask the endpoint.

`/health/live` answers `{"status":"alive",…}` and deliberately says nothing about the schema —
readiness is the probe that fails while migrations are outstanding.

## Reaching it

The compose file publishes `8080:8080`. That is **every interface on the host**, not loopback. There
is no reverse proxy in this topology and no TLS, so on a host with a public address the application
is on the internet the moment it starts, over plain HTTP.

Decide this before `up -d`, not after. Either bind the publication to loopback and put a TLS
terminator in front of it, or keep the host off public networks. A host firewall is the minimum, and
it is the operator's to configure — nothing in this repository will do it or check it.

Postgres, by contrast, publishes nothing. `docker compose ps` shows `5432/tcp` with no host binding:
reachable on the compose network and nowhere else. Confirmed in the rehearsal. Leave it that way.

## Updating

```sh
sed -i 's/^NODERA_VERSION=.*/NODERA_VERSION=0.2.0/' .env
docker compose -f compose.prod.yml up -d
```

Compose pulls a tag it does not have, recreates the containers whose image changed, and leaves
Postgres alone. `migrate` runs again first; if the release carries no migration it reports
`Applied 0 migration(s)` and the server starts. Verify with the readiness endpoint, not with the
tag you typed.

## Rolling back

The same two lines with the previous version. **Rollback is a rollback of the image, never of the
database** — [ADR-0006](../adr/0006-one-image-three-entrypoints.md) § 4, and the reason releases here
follow expand/contract: a release ships the *expand* migration, and the *contract* that drops what
the old code still reads comes one release later. The old image therefore meets a schema that is
ahead of it but still compatible, and starts. That one-release lag is a deliberate price, paid so
that backing out a release is two lines rather than a data-loss decision.

It holds only while the discipline held. A release that shipped an expand and its contract together,
or any destructive migration, leaves the old code facing a schema it cannot read — and then the only
route back is [`backup-restore.md`](backup-restore.md) § Restore, which costs every write made since
the upgrade.

**Take a dump immediately before any update that carries a migration.** Not because the image
rollback is expected to fail, but because it is the only thing that helps when it does.

## When something is wrong

| Symptom | First thing to check |
|---|---|
| Any compose subcommand fails with `required variable NODERA_VERSION is missing a value` | The variable, not the daemon. It is declared `:?` and re-read on *every* invocation, `logs` and `down` included. Put it in `.env` beside the compose file. |
| `invalid reference format` on `up` | A digest pinned as `NODERA_VERSION=@sha256:…`. Split it across the colon — see § First install. |
| `migrate` exits non-zero, server never starts | `docker compose logs migrate`. Working as designed: the server is gated on `service_completed_successfully`, so a failed migration stops the deployment rather than half-applying it. |
| `migrate` fails on privileges | It runs as `nodera_owner` and refuses to run as `nodera_app` on purpose. Check `NODERA_DB_USER` in the `migrate` service, and that `secrets/db_owner_password` matches what the database was initialised with. |
| `migrate` fails naming a secret path it "could not read" | Not the contents — the **permissions**. The file is not readable by the image's gid; see § First install, and re-check the numbers with `docker run --rm --entrypoint id`. This is the failure the `chown` line prevents, and the one part of that section no rehearsal has exercised. |
| Server unhealthy, `/health/ready` not `ready` | Read `detail`. A schema behind the code is a migration that did not run; a connection error is usually `secrets/app_role_password` differing from the password `V4` created the role with — see [`backup-restore.md`](backup-restore.md) § When something is wrong. |
| Postgres will not initialise on a fresh volume | A trailing newline in `secrets/db_owner_password`. Rewrite with `printf '%s'`. |
| Health endpoint reports a version you did not deploy | The running container was not recreated. `docker compose -f compose.prod.yml up -d` again and re-read `ps`; the endpoint is right and the tag is not evidence. |
| On a workstation: the stack shares a volume with `make dev` | `compose.prod.yml` declares `name: nodera`, and the development `docker-compose.yml` declares none — so in a checkout it takes the directory name, which is also `nodera`. Both then resolve `pgdata` to `nodera_pgdata`. Rehearse with `docker compose -p <something-else>`; the drill in `backup-restore.md` does. |

## What must never happen

- **A published `5432`.** The database is reachable from the compose network and from nowhere else.
  Publishing it is the single most common way a self-hosted database ends up on the internet.
- **`8080` on a public interface without TLS in front of it.** See § Reaching it.
- **Secrets as environment variables.** Both passwords are file-mounted specifically so they stay out
  of `docker inspect` and the process table. `NODERA_DB_PASSWORD` exists as a variable; using it
  instead of `NODERA_DB_PASSWORD_FILE` gives that property away.
- **`secrets/` in git, or only on this host.** It is git-ignored, and the app-role password is not in
  any database dump. A backup without those two files restores a database the application cannot
  open.
- **Building the image on the host.** The artefact CI built and the artefact you run are the same
  one, or the version the health endpoint reports stops meaning anything.
- **An update without a dump, when it carries a migration.** The image rollback is the procedure;
  the dump is what is left when expand/contract was not followed.
