-- V4 — The append-only audit trail, the application role, and row-level security.
--
-- This migration is where two of Nodera's load-bearing guarantees stop being promises:
--
--   AU1  the audit trail is append-only *at the database level* — the application role
--        holds INSERT and SELECT on audit_event and nothing else. Not "we do not update
--        it": it cannot.
--   P1   the multi-project boundary is enforced by RLS, so a forgotten WHERE clause in
--        application code returns nothing instead of another project's rows.
--
-- See docs/DOMAIN_MODEL.md §§ 3 and 9.

create type audit_surface as enum ('web', 'rest', 'mcp', 'system');

-- ---------------------------------------------------------------------------
-- audit_event
-- ---------------------------------------------------------------------------

create table audit_event (
    id                    bigint        generated always as identity primary key,
    occurred_at           timestamptz   not null default now(),

    project_id            uuid          references project (id) on delete restrict,
    actor_id              uuid          not null references actor (id) on delete restrict,

    -- Invariant AU2: denormalised on purpose. "Was this done by a human or an agent?"
    -- must be answerable from this table alone, as of the moment it happened, without
    -- joining a table whose current contents may since have changed.
    actor_kind            actor_kind    not null,

    -- Invariant AU4: the delegation chain. Turns "the agent closed the ticket" into
    -- "the agent closed it, acting on Anna's instruction".
    on_behalf_of_actor_id uuid          references actor (id) on delete restrict,

    surface               audit_surface not null,
    tool_name             text,          -- MCP tool name, or the REST route
    action                text          not null check (action ~ '^[a-z_]+\.[a-z_]+$'),
    entity_type           text          not null,
    entity_id             uuid,

    before                jsonb,
    after                 jsonb,
    outcome               text          not null default 'success'
                                        check (outcome in ('success', 'denied', 'failed')),
    request_id            uuid          not null
);

create index audit_event_entity_idx    on audit_event (entity_type, entity_id, occurred_at desc);
create index audit_event_actor_idx     on audit_event (actor_id, occurred_at desc);
create index audit_event_project_idx   on audit_event (project_id, occurred_at desc);
create index audit_event_request_idx   on audit_event (request_id);
create index audit_event_behalf_idx    on audit_event (on_behalf_of_actor_id)
    where on_behalf_of_actor_id is not null;
-- Denials are the rows an incident review reads first, and they are a small minority.
create index audit_event_denied_idx    on audit_event (occurred_at desc)
    where outcome = 'denied';

comment on table audit_event is
    'Append-only. Invariant AU3: every mutation writes exactly one row, in the same '
    'transaction as the mutation — a mutation that commits without its audit row is a '
    'defect of the same severity as losing the mutation.';

-- Belt and braces alongside the privilege grant below: even a role that somehow acquired
-- UPDATE or DELETE is refused by the table itself.
create or replace function audit_event_is_append_only() returns trigger
language plpgsql as $$
begin
    raise exception 'audit_event is append-only (invariant AU1)' using errcode = 'insufficient_privilege';
end;
$$;

create trigger audit_event_no_update before update on audit_event
    for each row execute function audit_event_is_append_only();
create trigger audit_event_no_delete before delete on audit_event
    for each row execute function audit_event_is_append_only();
create trigger audit_event_no_truncate before truncate on audit_event
    execute function audit_event_is_append_only();

-- ---------------------------------------------------------------------------
-- idempotency_record — see docs/MCP.md § 5
-- ---------------------------------------------------------------------------
-- Agents retry. A retried ticket_create that produces a second ticket is a defect, so
-- the key is stored with the entity it produced and replayed for 24 hours.

create table idempotency_record (
    actor_id        uuid        not null references actor (id) on delete cascade,
    idempotency_key text        not null check (length(idempotency_key) between 8 and 200),
    operation       text        not null,
    -- Hash of the canonicalised arguments. A repeat with the same key but different
    -- arguments is an error, never a silent overwrite: two different intents, one event.
    argument_hash   text        not null,
    entity_type     text        not null,
    entity_id       uuid        not null,
    created_at      timestamptz not null default now(),
    expires_at      timestamptz not null default now() + interval '24 hours',
    primary key (actor_id, idempotency_key)
);

create index idempotency_record_expiry_idx on idempotency_record (expires_at);

-- ---------------------------------------------------------------------------
-- The application role
-- ---------------------------------------------------------------------------
-- Migrations run as the owner; the application connects as nodera_app, which is
-- deliberately unable to bypass RLS or rewrite history.
--
-- Created here with a placeholder password that the operator MUST replace — see
-- docs/DEPLOYMENT.md. Flyway substitutes ${nodera_app_password} at migration time.

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'nodera_app') then
        create role nodera_app login password '${nodera_app_password}';
    end if;
end
$$;

grant usage on schema public to nodera_app;

grant select, insert, update, delete on
    actor, human_actor, agent_actor,
    project, project_membership, capability_grant, credential,
    ticket, ticket_sequence, acceptance_criterion, ticket_dependency,
    label, ticket_label,
    comment, comment_mention,
    review, review_finding,
    idempotency_record
to nodera_app;

-- Invariant AU1, as a privilege rather than a convention.
grant select, insert on audit_event to nodera_app;
grant usage, select on all sequences in schema public to nodera_app;

-- ---------------------------------------------------------------------------
-- Row-level security — the multi-project boundary (invariant P1)
-- ---------------------------------------------------------------------------
-- nodera.project_ids is set from the AUTHENTICATED context at the start of each
-- transaction, never from a request parameter. An empty setting matches nothing, so a
-- code path that forgot to establish context reads zero rows rather than everything —
-- the polarity that fails loud instead of silent.

create or replace function current_project_ids() returns uuid[]
language plpgsql stable as $$
declare
    raw text := current_setting('nodera.project_ids', true);
begin
    if raw is null or raw = '' then
        return array[]::uuid[];
    end if;
    return string_to_array(raw, ',')::uuid[];
end;
$$;

-- Directly project-scoped tables.
alter table project              enable row level security;
alter table project_membership   enable row level security;
alter table capability_grant     enable row level security;
alter table ticket               enable row level security;
alter table ticket_sequence      enable row level security;
alter table label                enable row level security;
alter table audit_event          enable row level security;

create policy project_visible on project
    for all to nodera_app
    using (id = any (current_project_ids()));

create policy project_membership_visible on project_membership
    for all to nodera_app
    using (project_id = any (current_project_ids()));

create policy capability_grant_visible on capability_grant
    for all to nodera_app
    using (project_id = any (current_project_ids()));

create policy ticket_visible on ticket
    for all to nodera_app
    using (project_id = any (current_project_ids()));

create policy ticket_sequence_visible on ticket_sequence
    for all to nodera_app
    using (project_id = any (current_project_ids()));

create policy label_visible on label
    for all to nodera_app
    using (project_id = any (current_project_ids()));

-- Deployment-level audit rows carry a null project_id and stay visible to a caller with
-- audit.read; project-scoped rows follow the project boundary like everything else.
create policy audit_event_visible on audit_event
    for all to nodera_app
    using (project_id is null or project_id = any (current_project_ids()));

-- Tables scoped transitively through their ticket. Written as an EXISTS against ticket,
-- which is itself RLS-protected, so the boundary is expressed once and inherited.
alter table acceptance_criterion enable row level security;
alter table ticket_dependency    enable row level security;
alter table ticket_label         enable row level security;
alter table comment              enable row level security;
alter table comment_mention      enable row level security;
alter table review               enable row level security;
alter table review_finding       enable row level security;

create policy acceptance_criterion_visible on acceptance_criterion
    for all to nodera_app
    using (exists (select 1 from ticket t where t.id = acceptance_criterion.ticket_id));

create policy ticket_dependency_visible on ticket_dependency
    for all to nodera_app
    using (exists (select 1 from ticket t where t.id = ticket_dependency.ticket_id));

create policy ticket_label_visible on ticket_label
    for all to nodera_app
    using (exists (select 1 from ticket t where t.id = ticket_label.ticket_id));

create policy comment_visible on comment
    for all to nodera_app
    using (exists (select 1 from ticket t where t.id = comment.ticket_id));

create policy comment_mention_visible on comment_mention
    for all to nodera_app
    using (exists (select 1 from comment c where c.id = comment_mention.comment_id));

create policy review_visible on review
    for all to nodera_app
    using (exists (select 1 from ticket t where t.id = review.ticket_id));

create policy review_finding_visible on review_finding
    for all to nodera_app
    using (exists (select 1 from review r where r.id = review_finding.review_id));

-- actor, human_actor, agent_actor, credential and idempotency_record are intentionally
-- NOT project-scoped: an actor exists across projects. Visibility of an actor's details
-- is an application-layer concern (a member of project A may resolve a handle they were
-- mentioned by), and credential rows are never selected by any path other than
-- authentication, which runs before a project context exists.
