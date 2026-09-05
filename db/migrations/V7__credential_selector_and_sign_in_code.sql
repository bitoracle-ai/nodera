-- V7 — The credential lookup key, and the one-time codes local sign-in redeems.
--
-- V1's unique index on credential.token_hash reads as the lookup key and is not one:
-- Argon2id is salted, so `where token_hash = hash(presented)` matches nothing. A token is
-- two parts — a selector that addresses the row, a verifier that proves it — and only the
-- second is a secret. docs/plan/SEC-01.md § 3.

-- not null in one step, deliberately: no code path has ever written a credential row, so
-- the alternative to failing loudly on one is admitting a credential nothing can
-- authenticate. Lowercase hex has no underscore, so the token splits unambiguously, and it
-- cannot spell the documented example token .gitleaks.toml allowlists.
alter table credential
    add column selector text not null
    check (selector ~ '^[0-9a-f]{24}$');

create unique index credential_selector_idx on credential (selector);

comment on column credential.selector is
    'Public lookup key. Carries no authority: it addresses a row, it does not authenticate one.';

-- ---------------------------------------------------------------------------
-- sign_in_code — local human sign-in, e-mail plus a one-time code
-- ---------------------------------------------------------------------------
-- Eight digits is what a person will retype, which makes the attempt counter part of the
-- credential rather than a policy above it. Not project-scoped, so no row-level security —
-- the same reasoning V4 records for credential: sign-in runs before a project context exists.

create table sign_in_code (
    id          uuid        primary key default gen_random_uuid(),
    actor_id    uuid        not null references actor (id) on delete restrict,
    code_hash   text        not null,
    attempts    int         not null default 0 check (attempts >= 0),
    expires_at  timestamptz not null,
    consumed_at timestamptz,
    created_at  timestamptz not null default now()
);

create index sign_in_code_actor_idx on sign_in_code (actor_id);
create index sign_in_code_live_idx on sign_in_code (actor_id)
    where consumed_at is null;

comment on table sign_in_code is
    'One-time codes, stored as Argon2id hashes like every other secret (invariant CR1). '
    'Issuing consumes the live rows first, but two concurrent issues miss each other under '
    'read committed: what makes at most one redeemable is that redemption reads the newest '
    'row only, never the set.';

grant select, insert, update, delete on sign_in_code to nodera_app;
