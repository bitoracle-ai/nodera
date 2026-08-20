-- V1 — Actors, projects, membership, capability grants, credentials.
--
-- The organising decision of this schema: there is no `users` table. A human and an
-- agent are two subtypes of `actor`, and every reference to a participant anywhere in
-- the database points at `actor`. See docs/DOMAIN_MODEL.md § 2.
--
-- Conventions enforced by CI (scripts/lint_sql.py):
--   * identifiers are unquoted lowercase snake_case;
--   * every table carries created_at/updated_at where mutable;
--   * every foreign key is indexed.

create extension if not exists "pgcrypto";
create extension if not exists citext;

-- ---------------------------------------------------------------------------
-- Enumerations
-- ---------------------------------------------------------------------------

create type actor_kind   as enum ('human', 'agent');
create type actor_status as enum ('active', 'suspended', 'retired');
create type project_role as enum ('owner', 'maintainer', 'contributor', 'observer');
create type credential_kind as enum ('session', 'personal_access_token', 'oidc_link');

-- ---------------------------------------------------------------------------
-- actor — the single participant type
-- ---------------------------------------------------------------------------

create table actor (
    id            uuid primary key default gen_random_uuid(),
    kind          actor_kind   not null,
    handle        citext       not null unique,
    display_name  text         not null check (length(trim(display_name)) between 1 and 200),
    status        actor_status not null default 'active',
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);

comment on table actor is
    'Human or agent. Every assignment, comment, grant and audit row references this table.';
comment on column actor.handle is
    'Mention target, unique across BOTH kinds so an agent can never shadow a person (invariant A3).';

-- Invariant A1: kind is immutable. A mutable kind would silently rewrite the meaning of
-- every historical audit row that references this actor.
create or replace function actor_kind_is_immutable() returns trigger
language plpgsql as $$
begin
    if new.kind is distinct from old.kind then
        raise exception 'actor.kind is immutable (actor %, % -> %)', old.id, old.kind, new.kind
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger actor_kind_immutable
    before update on actor
    for each row execute function actor_kind_is_immutable();

-- ---------------------------------------------------------------------------
-- human_actor / agent_actor — the two subtypes
-- ---------------------------------------------------------------------------

create table human_actor (
    actor_id  uuid primary key references actor (id) on delete restrict,
    email     citext not null unique,
    locale    text   not null default 'en',
    timezone  text   not null default 'UTC'
);

create table agent_actor (
    actor_id        uuid primary key references actor (id) on delete restrict,
    -- Invariant A4: the accountable owner. The chain must terminate at a human.
    owner_actor_id  uuid not null references actor (id) on delete restrict,
    -- Descriptive only. Invariant A5: no code path may ever branch on this value.
    runtime_hint    text,
    contact_url     text,
    retired_at      timestamptz,
    constraint agent_is_not_its_own_owner check (actor_id <> owner_actor_id)
);

create index agent_actor_owner_idx on agent_actor (owner_actor_id);

-- Invariant A4, enforced: walking owner links must reach a human without cycling.
-- Depth is bounded at 16 — a supervisor spawning workers is a real shape, a 17-deep
-- ownership chain is a mistake, and the bound is what makes a cycle terminate loudly.
create or replace function agent_owner_chain_is_valid() returns trigger
language plpgsql as $$
declare
    cursor_id uuid := new.owner_actor_id;
    cursor_kind actor_kind;
    hops int := 0;
begin
    loop
        if hops > 16 then
            raise exception 'agent ownership chain from % exceeds 16 hops (cycle or misconfiguration)',
                new.actor_id using errcode = 'check_violation';
        end if;
        select kind into cursor_kind from actor where id = cursor_id;
        if cursor_kind is null then
            raise exception 'owner actor % does not exist', cursor_id using errcode = 'foreign_key_violation';
        end if;
        exit when cursor_kind = 'human';
        if cursor_id = new.actor_id then
            raise exception 'agent ownership cycle involving actor %', new.actor_id
                using errcode = 'check_violation';
        end if;
        select owner_actor_id into cursor_id from agent_actor where actor_id = cursor_id;
        if cursor_id is null then
            raise exception 'agent ownership chain from % does not terminate at a human', new.actor_id
                using errcode = 'check_violation';
        end if;
        hops := hops + 1;
    end loop;
    return new;
end;
$$;

create trigger agent_owner_chain_valid
    before insert or update of owner_actor_id on agent_actor
    for each row execute function agent_owner_chain_is_valid();

-- The subtype tables must be exhaustive and exclusive: exactly one row per actor,
-- matching actor.kind. Checked by scripts/check_schema_integrity.sql in CI rather than
-- by a deferred constraint, because the insert order (actor then subtype) is legitimate
-- inside one transaction.

-- ---------------------------------------------------------------------------
-- project — the multi-project boundary
-- ---------------------------------------------------------------------------

create table project (
    id           uuid primary key default gen_random_uuid(),
    key          citext      not null unique check (key ~ '^[a-z][a-z0-9_]{1,29}$'),
    name         text        not null,
    description  text,
    archived_at  timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

comment on column project.key is
    'Short, stable, immutable. Globally unique ticket reference is project.key/ticket.key.';

-- ---------------------------------------------------------------------------
-- project_membership — role per actor per project
-- ---------------------------------------------------------------------------

create table project_membership (
    project_id         uuid         not null references project (id) on delete cascade,
    actor_id           uuid         not null references actor (id) on delete restrict,
    role               project_role not null,
    granted_by_actor_id uuid        not null references actor (id) on delete restrict,
    expires_at         timestamptz,
    created_at         timestamptz  not null default now(),
    primary key (project_id, actor_id)
);

create index project_membership_actor_idx  on project_membership (actor_id);
create index project_membership_granter_idx on project_membership (granted_by_actor_id);

-- ---------------------------------------------------------------------------
-- capability_grant — individual verbs on top of the role
-- ---------------------------------------------------------------------------
-- Capability is text rather than an enum on purpose: adding a verb must not require a
-- type migration that locks the table, and the authoritative list lives in
-- :domain (Capability.kt) where the permission engine can be exhaustive over it.

create table capability_grant (
    id                  uuid        primary key default gen_random_uuid(),
    project_id          uuid        not null references project (id) on delete cascade,
    actor_id            uuid        not null references actor (id) on delete restrict,
    capability          text        not null check (capability ~ '^[a-z_]+\.[a-z_]+$'),
    -- true = grant, false = explicit denial that overrides the role default.
    granted             boolean     not null default true,
    granted_by_actor_id uuid        not null references actor (id) on delete restrict,
    expires_at          timestamptz,
    created_at          timestamptz not null default now(),
    unique (project_id, actor_id, capability)
);

create index capability_grant_actor_idx   on capability_grant (actor_id);
create index capability_grant_granter_idx on capability_grant (granted_by_actor_id);
create index capability_grant_project_idx on capability_grant (project_id);

comment on table capability_grant is
    'Invariant C1 (attenuation) is re-checked at USE time against the grantor''s current '
    'effective set, not only at grant time — revoking a person''s access must revoke it for '
    'every agent that holds it through them, in the same instant.';

-- ---------------------------------------------------------------------------
-- credential — sessions, personal access tokens, OIDC links
-- ---------------------------------------------------------------------------

create table credential (
    id           uuid            primary key default gen_random_uuid(),
    actor_id     uuid            not null references actor (id) on delete restrict,
    kind         credential_kind not null,
    -- Invariant CR1: Argon2id hash only. The plaintext is returned once, at creation,
    -- and never stored, logged or echoed afterwards.
    token_hash   text            not null,
    label        text            not null,
    scopes       text[]          not null default '{}',
    expires_at   timestamptz,
    last_used_at timestamptz,
    revoked_at   timestamptz,
    created_at   timestamptz     not null default now()
);

create index credential_actor_idx on credential (actor_id);
create unique index credential_token_hash_idx on credential (token_hash);
-- Partial index: the authentication path only ever looks at live credentials.
create index credential_live_idx on credential (actor_id)
    where revoked_at is null;
