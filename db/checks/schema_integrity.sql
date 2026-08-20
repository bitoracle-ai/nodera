-- schema_integrity.sql — the structural rules a constraint cannot express.
--
-- Run in CI against a freshly migrated database. Each block raises on violation, so the
-- psql run fails with -v ON_ERROR_STOP=1.

\echo 'Checking: actor subtypes are exhaustive and exclusive'
do $$
declare
    orphans int;
    mismatched int;
    doubled int;
begin
    -- Every actor has exactly one subtype row.
    select count(*) into orphans
    from actor a
    where not exists (select 1 from human_actor h where h.actor_id = a.id)
      and not exists (select 1 from agent_actor g where g.actor_id = a.id);
    if orphans > 0 then
        raise exception 'INTEGRITY: % actor row(s) have no subtype row', orphans;
    end if;

    -- The subtype row matches actor.kind.
    select count(*) into mismatched
    from actor a
    where (a.kind = 'human' and not exists (select 1 from human_actor h where h.actor_id = a.id))
       or (a.kind = 'agent' and not exists (select 1 from agent_actor g where g.actor_id = a.id));
    if mismatched > 0 then
        raise exception 'INTEGRITY: % actor row(s) disagree with their subtype table', mismatched;
    end if;

    -- No actor is both.
    select count(*) into doubled
    from human_actor h join agent_actor g on g.actor_id = h.actor_id;
    if doubled > 0 then
        raise exception 'INTEGRITY: % actor(s) are both human and agent', doubled;
    end if;
end
$$;

\echo 'Checking: every agent ownership chain terminates at a human'
do $$
declare
    broken int;
begin
    with recursive chain(agent_id, cursor_id, hops) as (
        select g.actor_id, g.owner_actor_id, 1 from agent_actor g
        union all
        select c.agent_id, g.owner_actor_id, c.hops + 1
        from chain c
        join agent_actor g on g.actor_id = c.cursor_id
        where c.hops < 16
    )
    select count(distinct c.agent_id) into broken
    from chain c
    where not exists (
        select 1 from chain c2
        join actor a on a.id = c2.cursor_id
        where c2.agent_id = c.agent_id and a.kind = 'human'
    );
    if broken > 0 then
        raise exception 'INTEGRITY: % agent(s) have an ownership chain that never reaches a human', broken;
    end if;
end
$$;

\echo 'Checking: the application role cannot rewrite the audit trail'
do $$
declare
    bad text;
begin
    select string_agg(privilege_type, ', ') into bad
    from information_schema.table_privileges
    where grantee = 'nodera_app'
      and table_name = 'audit_event'
      and privilege_type in ('UPDATE', 'DELETE', 'TRUNCATE');
    if bad is not null then
        raise exception 'INTEGRITY: nodera_app holds % on audit_event — it must hold INSERT and SELECT only', bad;
    end if;
end
$$;

\echo 'Checking: row-level security is enabled on every project-scoped table'
do $$
declare
    missing text;
begin
    select string_agg(c.relname, ', ') into missing
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind = 'r'
      and c.relname in (
          'project', 'project_membership', 'capability_grant', 'ticket', 'ticket_sequence',
          'label', 'audit_event', 'acceptance_criterion', 'ticket_dependency', 'ticket_label',
          'comment', 'comment_mention', 'review', 'review_finding'
      )
      and not c.relrowsecurity;
    if missing is not null then
        raise exception 'INTEGRITY: row-level security is not enabled on: %', missing;
    end if;
end
$$;

\echo 'Schema integrity: OK'
