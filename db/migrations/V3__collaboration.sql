-- V3 — Comments, mentions, reviews and review findings.
--
-- See docs/DOMAIN_MODEL.md §§ 6–7. The invariant that shapes this file: an agent
-- comment is stored, threaded and notified exactly like a human comment (CM2). There is
-- one comment table, one author column, and the reader is told who wrote it from
-- actor.kind rather than being left to infer it.

create type review_verdict  as enum ('approved', 'changes_required');
create type finding_severity as enum ('blocking', 'non_blocking');

-- ---------------------------------------------------------------------------
-- comment
-- ---------------------------------------------------------------------------

create table comment (
    id                   uuid        primary key default gen_random_uuid(),
    ticket_id            uuid        not null references ticket (id) on delete cascade,
    author_actor_id      uuid        not null references actor (id) on delete restrict,
    body                 text        not null,
    in_reply_to_comment_id uuid      references comment (id) on delete restrict,
    created_at           timestamptz not null default now(),
    edited_at            timestamptz,
    -- Invariant CM1: deletion is a tombstone. The row and its thread position survive;
    -- body is blanked by the application when this is set.
    deleted_at           timestamptz,
    deleted_by_actor_id  uuid        references actor (id) on delete restrict,

    constraint body_present_unless_deleted check (
        deleted_at is not null or length(trim(body)) > 0
    ),
    constraint deletion_carries_actor check (
        (deleted_at is null and deleted_by_actor_id is null)
        or (deleted_at is not null and deleted_by_actor_id is not null)
    )
);

create index comment_ticket_idx  on comment (ticket_id, created_at);
create index comment_author_idx  on comment (author_actor_id);
create index comment_reply_idx   on comment (in_reply_to_comment_id) where in_reply_to_comment_id is not null;
create index comment_deleter_idx on comment (deleted_by_actor_id) where deleted_by_actor_id is not null;

comment on column comment.author_actor_id is
    'Invariant CM1: never rewritten. An edit stamps edited_at and preserves authorship.';

-- A reply must stay inside its own ticket's thread.
create or replace function comment_reply_same_ticket() returns trigger
language plpgsql as $$
declare
    parent_ticket uuid;
begin
    if new.in_reply_to_comment_id is null then
        return new;
    end if;
    select ticket_id into parent_ticket from comment where id = new.in_reply_to_comment_id;
    if parent_ticket is distinct from new.ticket_id then
        raise exception 'comment % replies to a comment on a different ticket', new.id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger comment_reply_scoped
    before insert or update of in_reply_to_comment_id on comment
    for each row execute function comment_reply_same_ticket();

-- ---------------------------------------------------------------------------
-- comment_mention — extracted server-side on write
-- ---------------------------------------------------------------------------
-- Extracted rather than parsed on read: a mention drives notification and permission
-- checks, and re-parsing Markdown at read time would make "was this actor notified?"
-- depend on the parser version rather than on what happened.

create table comment_mention (
    comment_id uuid not null references comment (id) on delete cascade,
    actor_id   uuid not null references actor (id) on delete restrict,
    primary key (comment_id, actor_id)
);

create index comment_mention_actor_idx on comment_mention (actor_id);

-- ---------------------------------------------------------------------------
-- review
-- ---------------------------------------------------------------------------
-- Invariant R2: rounds are preserved, append-only, including a verdict that contradicts
-- an earlier one. The contradiction is the signal — collapsing to a single current
-- verdict destroys the information that makes the record worth keeping.

create table review (
    id                 uuid            primary key default gen_random_uuid(),
    ticket_id          uuid            not null references ticket (id) on delete cascade,
    reviewer_actor_id  uuid            not null references actor (id) on delete restrict,
    round              integer         not null check (round > 0),
    verdict            review_verdict  not null,
    summary            text            not null default '',
    created_at         timestamptz     not null default now(),
    unique (ticket_id, round)
);

create index review_ticket_idx   on review (ticket_id, round);
create index review_reviewer_idx on review (reviewer_actor_id);

-- Invariant R1: the reviewer is neither the author nor the current assignee. An agent
-- may review a human's work and the reverse; what is refused is reviewing one's own.
create or replace function review_reviewer_is_independent() returns trigger
language plpgsql as $$
declare
    t_reporter uuid;
    t_assignee uuid;
begin
    select reporter_actor_id, assignee_actor_id
      into t_reporter, t_assignee
      from ticket where id = new.ticket_id;

    if new.reviewer_actor_id = t_assignee then
        raise exception 'reviewer % is the assignee of ticket % (invariant R1)',
            new.reviewer_actor_id, new.ticket_id using errcode = 'check_violation';
    end if;
    if new.reviewer_actor_id = t_reporter and t_assignee is null then
        raise exception 'reviewer % is the reporter of unassigned ticket % (invariant R1)',
            new.reviewer_actor_id, new.ticket_id using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger review_independent
    before insert on review
    for each row execute function review_reviewer_is_independent();

-- Reviews are never edited or withdrawn. A changed opinion is a new round.
create or replace function review_is_append_only() returns trigger
language plpgsql as $$
begin
    raise exception 'review rows are append-only (invariant R2); submit a new round instead'
        using errcode = 'check_violation';
end;
$$;

create trigger review_no_update before update on review
    for each row execute function review_is_append_only();
create trigger review_no_delete before delete on review
    for each row execute function review_is_append_only();

-- ---------------------------------------------------------------------------
-- review_finding
-- ---------------------------------------------------------------------------
-- The finding row IS mutable in exactly one direction: unresolved -> resolved. That is
-- the fix cycle, and it is what the closure gate (invariant T4) reads.

create table review_finding (
    id                    uuid             primary key default gen_random_uuid(),
    review_id             uuid             not null references review (id) on delete cascade,
    severity              finding_severity not null,
    title                 text             not null check (length(trim(title)) > 0),
    detail                text             not null default '',
    resolved_at           timestamptz,
    resolved_by_actor_id  uuid             references actor (id) on delete restrict,
    resolution_note       text,
    created_at            timestamptz      not null default now(),

    constraint resolution_carries_provenance check (
        (resolved_at is null and resolved_by_actor_id is null)
        or (resolved_at is not null and resolved_by_actor_id is not null)
    )
);

create index review_finding_review_idx   on review_finding (review_id);
create index review_finding_resolver_idx on review_finding (resolved_by_actor_id);
-- The closure gate's hot query: unresolved blocking findings for a ticket.
create index review_finding_open_blocking_idx on review_finding (review_id)
    where resolved_at is null and severity = 'blocking';
