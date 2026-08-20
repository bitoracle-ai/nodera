-- Development seed. NOT for any deployed environment.
--
-- Creates the smallest arrangement that demonstrates the premise: one project, one human,
-- one agent owned by that human, and an agent whose grants are deliberately NARROWER than
-- its owner's — which is the thing every bot-account integration cannot express.
--
-- Idempotent: safe to re-run. `make seed` runs it on every `make dev`.

begin;

-- ---------------------------------------------------------------------------
-- Actors
-- ---------------------------------------------------------------------------

insert into actor (id, kind, handle, display_name)
values ('11111111-1111-4111-8111-111111111111', 'human', 'anna', 'Anna Weber')
on conflict (handle) do nothing;

insert into human_actor (actor_id, email)
values ('11111111-1111-4111-8111-111111111111', 'anna@nodera.local')
on conflict (actor_id) do nothing;

insert into actor (id, kind, handle, display_name)
values ('22222222-2222-4222-8222-222222222222', 'agent', 'triage-agent', 'Triage Agent')
on conflict (handle) do nothing;

insert into agent_actor (actor_id, owner_actor_id, runtime_hint, contact_url)
values (
    '22222222-2222-4222-8222-222222222222',
    '11111111-1111-4111-8111-111111111111',
    -- Descriptive only. Invariant A5: no code path may branch on this value.
    'generic-mcp-client',
    'https://nodera.local/agents/triage'
)
on conflict (actor_id) do nothing;

-- ---------------------------------------------------------------------------
-- Project
-- ---------------------------------------------------------------------------

insert into project (id, key, name, description)
values (
    '33333333-3333-4333-8333-333333333333',
    'demo',
    'Demo project',
    'Seeded for local development. Delete it freely.'
)
on conflict (key) do nothing;

-- Anna owns the project.
insert into project_membership (project_id, actor_id, role, granted_by_actor_id)
values (
    '33333333-3333-4333-8333-333333333333',
    '11111111-1111-4111-8111-111111111111',
    'owner',
    '11111111-1111-4111-8111-111111111111'
)
on conflict (project_id, actor_id) do nothing;

-- The agent is a contributor, NOT an owner. This is the seed's actual point: the agent
-- Anna runs holds strictly fewer capabilities than Anna does, and the difference is data
-- rather than a code path.
insert into project_membership (project_id, actor_id, role, granted_by_actor_id)
values (
    '33333333-3333-4333-8333-333333333333',
    '22222222-2222-4222-8222-222222222222',
    'contributor',
    '11111111-1111-4111-8111-111111111111'
)
on conflict (project_id, actor_id) do nothing;

-- ...and narrowed further: it may comment and update, but explicitly NOT transition.
-- An explicit denial overrides the role default, and no MCP tool call can route around it.
insert into capability_grant (project_id, actor_id, capability, granted, granted_by_actor_id)
values (
    '33333333-3333-4333-8333-333333333333',
    '22222222-2222-4222-8222-222222222222',
    'ticket.transition',
    false,
    '11111111-1111-4111-8111-111111111111'
)
on conflict (project_id, actor_id, capability) do nothing;

-- ---------------------------------------------------------------------------
-- One ticket, so the views have something to render
-- ---------------------------------------------------------------------------

insert into ticket_sequence (project_id, prefix, next_number)
values ('33333333-3333-4333-8333-333333333333', 'demo', 2)
on conflict (project_id, prefix) do nothing;

insert into ticket (
    id, project_id, key, prefix, number, title, body, priority, status, effort,
    reporter_actor_id, assignee_actor_id
)
values (
    '44444444-4444-4444-8444-444444444444',
    '33333333-3333-4333-8333-333333333333',
    'demo-1', 'demo', 1,
    'Verify that an agent can hold a ticket',
    E'## Motivation\n\nAssignment must carry the same semantics for both actor kinds.\n',
    'p3', 'open', '~1 h',
    '11111111-1111-4111-8111-111111111111',
    -- Assigned to the AGENT. Same column a human would occupy (invariant T1/T2).
    '22222222-2222-4222-8222-222222222222'
)
on conflict (project_id, key) do nothing;

insert into acceptance_criterion (ticket_id, ordinal, text)
values
    ('44444444-4444-4444-8444-444444444444', 1, 'The assignee column holds an agent actor id.'),
    ('44444444-4444-4444-8444-444444444444', 2, 'No query filters on actor kind to render this ticket.')
on conflict (ticket_id, ordinal) do nothing;

commit;

\echo 'Seeded: project demo, human @anna, agent @triage-agent (contributor, transition denied).'
