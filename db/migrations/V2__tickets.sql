-- V2 — Tickets, acceptance criteria, dependencies, labels.
--
-- See docs/DOMAIN_MODEL.md § 5. The two invariants that shape this file:
--   T1  one accountable assignee, not a list;
--   T2  the assignee may be an agent, and NOTHING here treats that as a special case —
--       there is no is_bot column and no assigned_agent_id column, deliberately.

create type ticket_priority   as enum ('p1', 'p2', 'p3', 'p4');
create type ticket_status     as enum ('open', 'in_progress', 'in_review', 'blocked', 'closed');
create type ticket_resolution as enum ('done', 'wont_do', 'duplicate', 'superseded');

-- ---------------------------------------------------------------------------
-- ticket
-- ---------------------------------------------------------------------------

create table ticket (
    id                 uuid            primary key default gen_random_uuid(),
    project_id         uuid            not null references project (id) on delete cascade,

    -- key = prefix || '-' || number, e.g. 'core-12'. Unique per project (invariant P2),
    -- immutable and never reused (invariant T3).
    key                citext          not null,
    prefix             citext          not null check (prefix ~ '^[a-z][a-z0-9_]{0,15}$'),
    number             integer         not null check (number > 0),

    title              text            not null check (length(trim(title)) between 1 and 300),
    body               text            not null default '',

    priority           ticket_priority not null default 'p3',
    status             ticket_status   not null default 'open',
    resolution         ticket_resolution,
    effort             text,

    reporter_actor_id  uuid            not null references actor (id) on delete restrict,
    -- Invariant T1: singular. Nullable = unassigned. Human or agent, same column.
    assignee_actor_id  uuid            references actor (id) on delete restrict,

    created_at         timestamptz     not null default now(),
    updated_at         timestamptz     not null default now(),
    closed_at          timestamptz,

    unique (project_id, key),
    unique (project_id, prefix, number),

    -- A resolution exists exactly when the ticket is closed.
    constraint resolution_iff_closed check (
        (status = 'closed' and resolution is not null and closed_at is not null)
        or (status <> 'closed' and resolution is null and closed_at is null)
    ),
    constraint key_matches_parts check (key = prefix || '-' || number::text)
);

create index ticket_project_status_idx   on ticket (project_id, status);
create index ticket_project_priority_idx on ticket (project_id, priority, number);
create index ticket_assignee_idx         on ticket (assignee_actor_id) where assignee_actor_id is not null;
create index ticket_reporter_idx         on ticket (reporter_actor_id);
create index ticket_updated_idx          on ticket (project_id, updated_at desc);

comment on column ticket.assignee_actor_id is
    'Invariant T2: may reference an agent actor. No code path may branch on the assignee''s kind '
    'to decide what is permitted — that branch is the end of the first-class-actor premise.';

-- Full-text search over title and body, maintained by the database rather than by
-- application code that can forget to update it.
alter table ticket add column search_vector tsvector
    generated always as (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(body, '')), 'B')
    ) stored;

create index ticket_search_idx on ticket using gin (search_vector);

-- ---------------------------------------------------------------------------
-- ticket_sequence — per-project, per-prefix counter
-- ---------------------------------------------------------------------------
-- A row per (project, prefix) locked with SELECT … FOR UPDATE when allocating. A
-- Postgres sequence is deliberately NOT used: sequences are global objects, are not
-- transactional, and would leave gaps in a key space humans read aloud.

create table ticket_sequence (
    project_id   uuid    not null references project (id) on delete cascade,
    prefix       citext  not null,
    next_number  integer not null default 1 check (next_number > 0),
    primary key (project_id, prefix)
);

-- ---------------------------------------------------------------------------
-- acceptance_criterion
-- ---------------------------------------------------------------------------
-- Rows, not Markdown checkboxes: the closure gate (invariant T4) evaluates these, and
-- "who ticked this, and when" is exactly the claim that needs an accountable record.

create table acceptance_criterion (
    id              uuid        primary key default gen_random_uuid(),
    ticket_id       uuid        not null references ticket (id) on delete cascade,
    ordinal         integer     not null check (ordinal > 0),
    text            text        not null check (length(trim(text)) > 0),
    met             boolean     not null default false,
    met_at          timestamptz,
    met_by_actor_id uuid        references actor (id) on delete restrict,
    created_at      timestamptz not null default now(),
    unique (ticket_id, ordinal),
    constraint met_carries_provenance check (
        (met and met_at is not null and met_by_actor_id is not null)
        or (not met and met_at is null and met_by_actor_id is null)
    )
);

create index acceptance_criterion_ticket_idx on acceptance_criterion (ticket_id);
create index acceptance_criterion_actor_idx  on acceptance_criterion (met_by_actor_id);

-- ---------------------------------------------------------------------------
-- ticket_dependency
-- ---------------------------------------------------------------------------

create table ticket_dependency (
    ticket_id            uuid        not null references ticket (id) on delete cascade,
    depends_on_ticket_id uuid        not null references ticket (id) on delete cascade,
    created_at           timestamptz not null default now(),
    primary key (ticket_id, depends_on_ticket_id),
    constraint no_self_dependency check (ticket_id <> depends_on_ticket_id)
);

create index ticket_dependency_reverse_idx on ticket_dependency (depends_on_ticket_id);

-- Invariant T5: the dependency graph is acyclic. Checked here rather than in the
-- application because the working order is undefined the moment a cycle exists, and
-- two concurrent inserts can each be individually acyclic while their combination is not.
create or replace function ticket_dependency_is_acyclic() returns trigger
language plpgsql as $$
declare
    path_found boolean;
begin
    with recursive reachable(id, path) as (
        -- The seed path holds ONLY the edge's target. Seeding it with new.ticket_id as well
        -- would put the node we are searching for into the visited set, and the recursion's
        -- `not ... = any (r.path)` guard would then exclude the very edge that closes the
        -- cycle — making this check silently unable to ever fire.
        select new.depends_on_ticket_id, array[new.depends_on_ticket_id]
        union all
        select d.depends_on_ticket_id, r.path || d.depends_on_ticket_id
        from ticket_dependency d
        join reachable r on d.ticket_id = r.id
        where not d.depends_on_ticket_id = any (r.path)
          and array_length(r.path, 1) < 64
    )
    select exists (select 1 from reachable where id = new.ticket_id) into path_found;

    if path_found then
        raise exception 'ticket dependency cycle: % -> %', new.ticket_id, new.depends_on_ticket_id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger ticket_dependency_acyclic
    before insert or update on ticket_dependency
    for each row execute function ticket_dependency_is_acyclic();

-- Both ends of a dependency must live in the same project — a cross-project edge would
-- make the working order depend on a backlog the reader cannot see.
create or replace function ticket_dependency_same_project() returns trigger
language plpgsql as $$
declare
    a uuid;
    b uuid;
begin
    select project_id into a from ticket where id = new.ticket_id;
    select project_id into b from ticket where id = new.depends_on_ticket_id;
    if a is distinct from b then
        raise exception 'cross-project ticket dependency (% -> %)', new.ticket_id, new.depends_on_ticket_id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger ticket_dependency_project_scoped
    before insert or update on ticket_dependency
    for each row execute function ticket_dependency_same_project();

-- ---------------------------------------------------------------------------
-- label
-- ---------------------------------------------------------------------------

create table label (
    id         uuid        primary key default gen_random_uuid(),
    project_id uuid        not null references project (id) on delete cascade,
    name       citext      not null,
    colour     text        not null default '#6b7280' check (colour ~ '^#[0-9a-f]{6}$'),
    created_at timestamptz not null default now(),
    unique (project_id, name)
);

create table ticket_label (
    ticket_id uuid not null references ticket (id) on delete cascade,
    label_id  uuid not null references label (id) on delete cascade,
    primary key (ticket_id, label_id)
);

create index ticket_label_label_idx on ticket_label (label_id);
