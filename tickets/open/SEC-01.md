---
id: SEC-01
title: Credential issuance and authentication for humans and agents
priority: P1
status: open
effort: ~3 d
depends_on: [CORE-01, DB-01]
created: 2026-08-20
updated: 2026-08-20
---

# SEC-01 · Credential issuance and authentication for humans and agents

**Priority:** P1
**Effort:** ~3 d

## Motivation / context

An agent authenticating as itself is the first of the five first-class capabilities, and nothing
else can be demonstrated without it. Both surfaces need the same `ActorContext`, produced from two
different credential shapes.

## Current state (honest)

The `credential` table exists. No issuance, hashing, verification or middleware exists.
`.env.example` declares the Argon2id cost parameters and the JWT settings, which nothing reads yet.

## Approach

1. PAT issuance: generate, prefix `nod_pat_`, hash with Argon2id, return the plaintext **once**.
2. Verification middleware producing `ActorContext` with the correct `surface`.
3. Human sign-in: local email plus one-time code, with the OIDC path behind configuration.
4. Access JWT (15 minutes) plus a rotating opaque refresh token.
5. Redaction at the logging boundary, so a mistake upstream is still contained.

## Acceptance criteria

- [ ] A PAT plaintext appears exactly once, in the creation response, and never again in any
      response, log line or error — proved by a test that inspects captured log output.
- [ ] A revoked or expired credential is rejected; a test covers both.
- [ ] The process refuses to start when a required secret is absent, rather than defaulting.
- [ ] A token supplied as a command-line argument is rejected with a message naming why.
- [ ] Both credential shapes produce an equivalent `ActorContext`, differing only in `surface` and
      the actor identified.
- [ ] `make check` green.
- [ ] Independent review: 0 BLOCKING findings.

## Affected files

- `backend/application/src/main/kotlin/ai/nodera/application/identity/`.
- `backend/api-rest/src/main/kotlin/.../AuthenticationPlugin.kt`.
- `backend/app/src/main/kotlin/.../Configuration.kt` — fail-closed startup validation.

## Verification

`./gradlew test`. The redaction test captures appender output and asserts the token plaintext is
absent from it.
