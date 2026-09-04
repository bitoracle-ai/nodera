-- V6 — A ticket and the label on it belong to the same project.
--
-- `ticket_label` was the last two-ended association left without a same-project guard. V4's policy
-- scopes ticket_id only, and referential integrity checks bypass row-level security by design, so a
-- project B label could be attached to a project A ticket. `ticket_dependency` has the same shape
-- and has had its trigger since V2; this is the counterpart it never got. Found by DB-01's negative
-- tests, corrected here rather than in V2 because V2 has been applied. Invariant P1.

do $$
declare
    straddling int;
begin
    select count(*) into straddling
    from ticket_label tl
    join ticket t on t.id = tl.ticket_id
    join label l on l.id = tl.label_id
    where t.project_id <> l.project_id;
    if straddling > 0 then
        raise exception 'V6: % ticket_label row(s) cross a project boundary; resolve them first', straddling
            using errcode = 'check_violation';
    end if;
end
$$;

create or replace function ticket_label_same_project() returns trigger
language plpgsql as $$
declare
    ticket_project uuid;
    label_project  uuid;
begin
    select project_id into ticket_project from ticket where id = new.ticket_id;
    select project_id into label_project from label where id = new.label_id;
    -- A label the caller cannot see reads as null, which is distinct from the ticket's project and
    -- is refused here. When neither end is visible both read null, `is distinct from` is false, and
    -- the row is refused by the policy's with-check instead — never accepted.
    if ticket_project is distinct from label_project then
        raise exception 'cross-project ticket label (ticket %, label %)', new.ticket_id, new.label_id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger ticket_label_project_scoped
    before insert or update on ticket_label
    for each row execute function ticket_label_same_project();
