---
summary: Security rules — fail closed, credential handling, the permission check placement, input validation at the boundary, what may never be logged, and the paired-negative test requirement for every safety claim.
read_when:
  - Before any change touching auth, tokens, permissions, input handling or anything reaching the network.
  - During review of a diff that adds an endpoint, a tool or a configuration value.
---

# Secure coding — Nodera

## Fail closed, everywhere

- A missing required secret makes the process **refuse to start**. Never a default, never a warning.
  A warning in a log nobody reads is how a system runs in production with a development signing key.
- An unresolvable permission chain collapses to an **empty** capability set, never to "assume allowed".
- An RLS context that was never established matches **zero** rows, not all rows.
- An unparseable token is rejected, not treated as anonymous-with-read-access.

The pattern: when the system does not know, it does less, not more.

## Credentials

- Personal access tokens are stored **only** as an Argon2id hash. The plaintext is returned exactly
  once, at creation.
- Never log a token, a hash, a session id or an authorisation header — not at DEBUG, not "temporarily".
- Never accept a secret as a command-line argument: it lands in the process table and in shell history.
  Environment or file only.
- Never include a secret in an API response, an error message or an exception. Redact at the logging
  boundary, so a mistake upstream is still contained.
- Tokens carry an expiry. A credential that never expires is a credential nobody will ever rotate.

## Where the permission check goes

In the **use case**, before the work. Not in the route handler, not in a decorator, not in the
repository.

- Route handlers and MCP tools carry `ActorContext`; they never decide with it.
- There is one `PermissionService`. No second implementation, no bypass flag "for tests" that survives
  into production configuration.
- Check the specific capability, never a role name. Roles are an ergonomic surface over capabilities;
  the check is always about the verb.

## Input validation at the boundary

- Validate at the adapter, against a schema, before anything reaches `:application`.
- Parse, do not merely check: convert to a domain type that cannot hold an invalid value, so downstream
  code has nothing left to re-validate.
- Never interpolate into SQL. Ever. Parameterised statements only, no exceptions for "internal" values.
- Markdown from users and agents is rendered with a sanitising renderer; raw HTML is not permitted in
  comments or ticket bodies.

## Scoping

`project_id` comes from the authenticated context. Never from a query parameter, a path segment, a
header, or a token claim the client can influence. RLS is the floor beneath this, not a replacement
for it — both, always.

## Every safety claim ships with a paired-negative test

If a comment, a doc or a review says "this guard prevents X", there is a test that is **demonstrably
red when the guard is disabled**.

Without it the claim is an assertion. Assertions rot silently: the guard is refactored away, every test
still passes, and the documentation continues to promise a protection that no longer exists. This is a
BLOCKING finding wherever it is missing.

## Rate limiting

Per **actor**, not per token — minting a second token must gain nothing. Read and write budgets are
separate, so an agent polling a search cannot exhaust its own ability to comment.

## Dependencies

- Pinned. GitHub Actions are pinned to a commit SHA with the version in a trailing comment.
- A new runtime dependency is a decision with a reason, recorded in the ticket. "It was convenient" is
  not one — every dependency is a supply-chain surface and an upgrade obligation.
