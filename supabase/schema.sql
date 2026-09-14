-- Spendwise remote schema.
--
-- Run this once in the Supabase SQL editor. It is written to be re-runnable: every statement is
-- guarded, so applying it twice changes nothing.
--
-- Accounts themselves are NOT defined here. Supabase Auth owns auth.users and stores a bcrypt
-- hash of each password; the app never sees, sends or stores a password in a table of its own.
-- The view at the bottom is how you count registered users.

-- Client-generated UUIDs are the primary keys throughout. Room numbers its rows with a local
-- autoincrementing Long, which two devices would both start at 1, so the local id cannot be the
-- thing that identifies a row to the server.

create table if not exists public.categories (
  id          uuid primary key,
  user_id     uuid not null references auth.users (id) on delete cascade,
  name        text not null,
  sort_order  int  not null default 0,
  updated_at  timestamptz not null default now(),
  deleted_at  timestamptz,
  unique (user_id, name)
);

create table if not exists public.people (
  id          uuid primary key,
  user_id     uuid not null references auth.users (id) on delete cascade,
  name        text not null,
  note        text not null default '',
  updated_at  timestamptz not null default now(),
  deleted_at  timestamptz,
  unique (user_id, name)
);

create table if not exists public.expenses (
  id           uuid primary key,
  user_id      uuid not null references auth.users (id) on delete cascade,
  amount_minor bigint not null,
  currency     text   not null default 'INR',
  category     text   not null,
  note         text   not null default '',
  merchant     text   not null default '',
  paid_at      timestamptz not null,
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz
);

-- Screenshots and attachments are deliberately absent: they stay on the device.

create table if not exists public.expense_splits (
  id           uuid primary key,
  user_id      uuid not null references auth.users (id) on delete cascade,
  expense_id   uuid not null references public.expenses (id) on delete cascade,
  -- Null is the owner's own share, which is never owed to anybody.
  person_id    uuid references public.people (id) on delete cascade,
  amount_minor bigint not null,
  settled_at   timestamptz,
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz
);

create index if not exists expenses_user_updated  on public.expenses (user_id, updated_at);
create index if not exists splits_user_updated    on public.expense_splits (user_id, updated_at);
create index if not exists people_user_updated    on public.people (user_id, updated_at);
create index if not exists categories_user_updated on public.categories (user_id, updated_at);

-- Row Level Security.
--
-- The anon key ships inside the APK and is meant to be public, so RLS is the only thing standing
-- between one user's data and another's. Without these policies the key would read the whole
-- table. Each policy is the same shape: a row is yours if its user_id is your auth uid.

alter table public.categories     enable row level security;
alter table public.people         enable row level security;
alter table public.expenses       enable row level security;
alter table public.expense_splits enable row level security;

do $$
declare t text;
begin
  foreach t in array array['categories', 'people', 'expenses', 'expense_splits'] loop
    execute format('drop policy if exists own_rows on public.%I', t);
    execute format(
      'create policy own_rows on public.%I for all
         using (auth.uid() = user_id) with check (auth.uid() = user_id)', t);
  end loop;
end $$;

-- Deletions travel as tombstones (deleted_at) rather than as removals, because a row deleted on
-- one device has to be able to reach another device that is currently offline. A hard delete
-- would simply reappear on the next push from that device.

-- updated_at is what the incremental pull is keyed on, so it must not be left to the client.
create or replace function public.touch_updated_at() returns trigger as $$
begin
  new.updated_at = now();
  return new;
end $$ language plpgsql;

do $$
declare t text;
begin
  foreach t in array array['categories', 'people', 'expenses', 'expense_splits'] loop
    execute format('drop trigger if exists touch_updated_at on public.%I', t);
    execute format(
      'create trigger touch_updated_at before insert or update on public.%I
         for each row execute function public.touch_updated_at()', t);
  end loop;
end $$;

-- How many people have registered, and when they last signed in.
--
-- A view over auth.users runs with its owner's rights, so row level security does not apply to it
-- and the grants are the only thing deciding who may read it. Left as created, anon could read it
-- -- and anon is the key inside the APK, which anybody can unpack. The count is not personal data,
-- but it is nobody's business but yours, so both app-facing roles are revoked and only the service
-- role, which never leaves a server, is left able to select from it.
create or replace view public.user_counts as
  select count(*) as registered,
         count(*) filter (where last_sign_in_at > now() - interval '30 days') as active_30d
  from auth.users;

revoke all on public.user_counts from anon, authenticated;
grant select on public.user_counts to service_role;
