-- AGENT API DELIVERIES AND PROPOSALS.
--
-- What the assistant hands a player: a finished plan (kind 'delivery', id
-- d...) or a proposal to add to the player's open plan (kind 'proposal',
-- id p..., made from base_plan_id). The game and the site read one row by id
-- (GET /api/deliveries/<id>); the id is random and long enough not to be
-- guessed. Create-only: no route updates or deletes a row. A row is not
-- served after expires_at (30 days) and may be deleted by a later sweep.
-- player_name and player_uuid are for audit and never served.
-- Same access model as every other table: RLS on, no policies, service-role
-- only. Additive and idempotent. Mirrored at the end of supabase/schema.sql.

create table if not exists agent_deliveries (
  id text primary key check (id ~ '^[dp][a-hjkmnp-z2-9]{15}$'),
  kind text not null check (kind in ('delivery', 'proposal')),
  name text not null check (char_length(name) between 1 and 80),
  payload jsonb not null,
  base_plan_id text,
  player_name text,
  player_uuid text,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '30 days',
  check ((kind = 'delivery') = (left(id, 1) = 'd')),
  check (kind = 'delivery' or base_plan_id is not null)
);

create index if not exists agent_deliveries_kind_created_idx on agent_deliveries (kind, created_at desc);
create index if not exists agent_deliveries_expires_idx on agent_deliveries (expires_at);

alter table agent_deliveries enable row level security;
