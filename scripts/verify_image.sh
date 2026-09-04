#!/bin/sh
# Container-level acceptance checks for the Nodera image.
#
# The gates in `make check` cover the code: it compiles, it lints, and its test lanes pass where
# they are not skipped as up to date (docs/ci.md). They cannot prove that the *image* behaves:
# that migrations apply as the owner and refuse as the application role,
# that readiness fails while the schema is behind, that the root filesystem can be read-only, or
# that SIGTERM drains instead of killing. Those are properties of an artefact, and this script is
# how they are checked by someone other than the person who claims them.
#
# Usage:
#   docker build --build-arg VERSION=0.0.0-local -t nodera:local .
#   sh scripts/verify_image.sh nodera:local
#
# Needs a Docker daemon and nothing else. Its trap removes the containers and the network; the
# anonymous volume the Postgres image declares survives, because the removal omits -v (CI-02).

set -u

IMAGE=${1:-nodera:local}
NET=nodera-verify
PG=nodera-verify-pg
SERVE=nodera-verify-serve
OWNER_PW=verify-owner-only
APP_PW=verify-app-only
PASS=0
FAIL=0

ok()  { PASS=$((PASS + 1)); printf 'PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL  %s\n      %s\n' "$1" "$2"; }

cleanup() {
    docker rm -f "$SERVE" "$PG" >/dev/null 2>&1
    docker network rm "$NET" >/dev/null 2>&1
}
trap cleanup EXIT
cleanup

docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" \
    -e POSTGRES_DB=nodera -e POSTGRES_USER=nodera_owner -e POSTGRES_PASSWORD="$OWNER_PW" \
    -e POSTGRES_INITDB_ARGS="--locale=C --encoding=UTF8" \
    postgres:16-alpine >/dev/null

printf 'waiting for postgres'
i=0
while [ $i -lt 60 ]; do
    if docker exec "$PG" pg_isready -U nodera_owner -d nodera >/dev/null 2>&1; then break; fi
    printf '.'
    i=$((i + 1))
    sleep 1
done
printf ' ready\n\n'

OWNER_ENV="--network $NET -e NODERA_DB_URL=jdbc:postgresql://$PG:5432/nodera -e NODERA_DB_USER=nodera_owner -e NODERA_DB_PASSWORD=$OWNER_PW"

# ---------------------------------------------------------------------------
# Configuration refuses rather than guesses
# ---------------------------------------------------------------------------
out=$(docker run --rm "$IMAGE" serve 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -q "NODERA_DB_URL"; then
    ok "an absent required variable refuses start-up, naming it"
else
    bad "an absent required variable refuses start-up" "rc=$rc out=$out"
fi

out=$(docker run --rm $OWNER_ENV -e NODERA_DB_PASSWORD_FILE=/tmp/pw "$IMAGE" serve 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -q "are both set"; then
    ok "a variable set directly and as _FILE refuses start-up rather than picking one"
else
    bad "both-set refuses start-up" "rc=$rc out=$out"
fi

# ---------------------------------------------------------------------------
# The entrypoints
# ---------------------------------------------------------------------------
sout=$(docker run --rm "$IMAGE" mcp-stdio 2>/dev/null); rc=$?
serr=$(docker run --rm "$IMAGE" mcp-stdio 2>&1 >/dev/null)
if [ $rc -ne 0 ] && [ -z "$sout" ] && echo "$serr" | grep -q "MCP-01"; then
    ok "mcp-stdio exits non-zero, names MCP-01, leaves stdout byte-for-byte empty"
else
    bad "mcp-stdio contract" "rc=$rc stdout='$sout' stderr='$serr'"
fi

out=$(docker run --rm "$IMAGE" wibble 2>&1); rc=$?
if [ $rc -eq 2 ] && echo "$out" | grep -q "Unknown command"; then
    ok "an unknown command exits 2 with usage"
else
    bad "unknown command" "rc=$rc out=$out"
fi

# ---------------------------------------------------------------------------
# Readiness fails closed while the schema is behind; liveness does not
# ---------------------------------------------------------------------------
docker exec "$PG" psql -U nodera_owner -d nodera -q -c 'create database nodera_empty' >/dev/null 2>&1
docker run -d --name "$SERVE" --network "$NET" --read-only --tmpfs /tmp \
    -e NODERA_DB_URL="jdbc:postgresql://$PG:5432/nodera_empty" \
    -e NODERA_DB_USER=nodera_owner -e NODERA_DB_PASSWORD="$OWNER_PW" \
    "$IMAGE" serve >/dev/null
sleep 8
ready=$(docker exec "$SERVE" wget -qO- http://localhost:8080/health/ready 2>/dev/null); rrc=$?
live=$(docker exec "$SERVE" wget -qO- http://localhost:8080/health/live 2>/dev/null); lrc=$?
if [ $rrc -ne 0 ]; then
    ok "readiness refuses while migrations are pending"
else
    bad "readiness while pending" "rc=$rrc body=$ready"
fi
if [ $lrc -eq 0 ] && echo "$live" | grep -q '"alive"'; then
    ok "liveness stays healthy while readiness refuses"
else
    bad "liveness while readiness refuses" "rc=$lrc body=$live"
fi
docker rm -f "$SERVE" >/dev/null 2>&1

# ---------------------------------------------------------------------------
# migrate: applies as the owner, idempotent, and refuses the application role
# ---------------------------------------------------------------------------
first=$(docker run --rm $OWNER_ENV -e NODERA_APP_PASSWORD="$APP_PW" "$IMAGE" migrate 2>&1); rc1=$?
second=$(docker run --rm $OWNER_ENV -e NODERA_APP_PASSWORD="$APP_PW" "$IMAGE" migrate 2>&1); rc2=$?
if [ $rc1 -eq 0 ] && echo "$first" | grep -qE 'Applied [1-9][0-9]* migration'; then
    ok "migrate applies the schema as the owner"
else
    bad "migrate first run" "rc=$rc1 out=$first"
fi
if [ $rc2 -eq 0 ] && echo "$second" | grep -q 'Applied 0 migration'; then
    ok "migrate run twice is a no-op the second time"
else
    bad "migrate second run" "rc=$rc2 out=$second"
fi

# Flyway needs no data-definition rights when the schema is already current, so without the
# privilege pre-check in Migrator this would exit zero and look fine — and the wrong credentials
# would surface mid-upgrade at the next release instead.
out=$(docker run --rm --network "$NET" \
    -e NODERA_DB_URL="jdbc:postgresql://$PG:5432/nodera" \
    -e NODERA_DB_USER=nodera_app -e NODERA_DB_PASSWORD="$APP_PW" \
    -e NODERA_APP_PASSWORD="$APP_PW" "$IMAGE" migrate 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -q "cannot create objects"; then
    ok "migrate refuses the application role even when the schema is already current"
else
    bad "migrate as nodera_app" "rc=$rc out=$out"
fi
# The refusal above is produced before Flyway runs anything, so this asserts that the REFUSAL
# path is clean — not that redaction works. Redaction is a separate guard with its own unit
# test in :persistence (RedactionTest); claiming it here would be claiming a check that cannot
# fail.
if echo "$out" | grep -q "$APP_PW"; then
    bad "the refusal names the role, not the password" "the password appeared in the output"
else
    ok "the refusal names the role, never the password"
fi

# ---------------------------------------------------------------------------
# serve: read-only root filesystem, as the application role, assets same-origin
# ---------------------------------------------------------------------------
docker run -d --name "$SERVE" --network "$NET" --read-only --tmpfs /tmp \
    -e NODERA_DB_URL="jdbc:postgresql://$PG:5432/nodera" \
    -e NODERA_DB_USER=nodera_app -e NODERA_DB_PASSWORD="$APP_PW" \
    "$IMAGE" serve >/dev/null
sleep 10
body=$(docker exec "$SERVE" wget -qO- http://localhost:8080/health/ready 2>/dev/null); rc=$?
if [ $rc -eq 0 ] && echo "$body" | grep -q '"ready"'; then
    ok "serve reports ready as nodera_app on a read-only root filesystem"
else
    bad "serve ready" "rc=$rc body=$body"
fi
if echo "$body" | grep -q '"version"'; then
    ok "the instance reports the version it was built with"
else
    bad "version stamp" "body=$body"
fi
index=$(docker exec "$SERVE" wget -qO- http://localhost:8080/ 2>/dev/null)
if echo "$index" | grep -q 'id="root"'; then
    ok "the web assets are served from the same origin as the API"
else
    bad "static assets" "body=$(echo "$index" | head -c 200)"
fi

# ---------------------------------------------------------------------------
# SIGTERM drains rather than being killed
# ---------------------------------------------------------------------------
start=$(date +%s)
docker stop -t 25 "$SERVE" >/dev/null
elapsed=$(($(date +%s) - start))
code=$(docker inspect -f '{{.State.ExitCode}}' "$SERVE" 2>/dev/null)
if [ "$elapsed" -lt 20 ]; then
    ok "SIGTERM shuts the server down in ${elapsed}s (exit $code), inside the grace period"
else
    bad "SIGTERM handling" "took ${elapsed}s, exit $code — the JVM was probably killed"
fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
