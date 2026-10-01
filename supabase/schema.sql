-- Peyo remote schema.
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

-- Who paid the bill. Null is the account owner; otherwise it is the person who paid it for them,
-- and the owner's own share of it is what they owe that person. Added after the table, so a
-- project created before payers existed picks it up when this file is run again.
alter table public.expenses add column if not exists paid_by uuid;

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


-- ---------------------------------------------------------------------------------------------
-- Registered users, by email.
--
-- auth.users is owned by Supabase Auth and is not readable by the app: the anon and authenticated
-- roles have no grants on that schema, so an account's own email is not something the client can
-- look up. This table is the app-facing mirror of it -- one row per registered user, carrying the
-- address they registered with and whether they have actually completed authentication.
--
-- It is written by a trigger on auth.users rather than by the client. An email the client could
-- set would be an email the client could set to somebody else's, and a "verified" flag a client
-- could raise would mean nothing at all.
create table if not exists public.profiles (
  id             uuid primary key references auth.users (id) on delete cascade,
  email          text not null,
  -- The flag asked of a registered user: true once Supabase Auth has fully accepted them, which
  -- means the address is confirmed (or the project does not require confirmation and the account
  -- has signed in at least once). A row with this false is a signup that never came back.
  auth_completed boolean not null default false,
  -- The currency the account chose, so a reinstall restores the preference along with the rows.
  currency       text not null default 'INR',
  -- What the user asked to be called, given at signup and kept in auth metadata as full_name.
  name           text not null default '',
  created_at     timestamptz not null default now(),
  last_seen_at   timestamptz not null default now()
);

-- For projects whose profiles table predates the column.
alter table public.profiles add column if not exists name text not null default '';

alter table public.profiles enable row level security;

drop policy if exists own_profile on public.profiles;
create policy own_profile on public.profiles for all
  using (auth.uid() = id) with check (auth.uid() = id);

create index if not exists profiles_email on public.profiles (lower(email));

-- Mirrors an account into public.profiles on signup and keeps it current afterwards. Runs as the
-- function owner (security definer) because the trigger fires in the context of whoever caused
-- the write, which for a signup is the anon role.
create or replace function public.sync_profile() returns trigger
  language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, email, name, auth_completed, last_seen_at)
  values (
    new.id,
    coalesce(new.email, ''),
    coalesce(trim(new.raw_user_meta_data ->> 'full_name'), ''),
    new.email_confirmed_at is not null or new.last_sign_in_at is not null,
    coalesce(new.last_sign_in_at, now())
  )
  on conflict (id) do update set
    email          = excluded.email,
    name           = excluded.name,
    -- Only ever raised, never lowered: an account that has authenticated once stays authenticated.
    auth_completed = public.profiles.auth_completed or excluded.auth_completed,
    last_seen_at   = greatest(public.profiles.last_seen_at, excluded.last_seen_at);
  return new;
end $$;

drop trigger if exists sync_profile on auth.users;
create trigger sync_profile after insert or update on auth.users
  for each row execute function public.sync_profile();

-- Backfills the accounts that registered before this table existed.
insert into public.profiles (id, email, name, auth_completed, last_seen_at)
  select id,
         coalesce(email, ''),
         coalesce(trim(raw_user_meta_data ->> 'full_name'), ''),
         email_confirmed_at is not null or last_sign_in_at is not null,
         coalesce(last_sign_in_at, created_at)
  from auth.users
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------------------------
-- The email each data row belongs to.
--
-- user_id is the key that matters and the one RLS is written on; this is for reading the tables.
-- Filled in by a trigger from the authenticated account rather than sent by the client, for the
-- same reason as above -- a client-supplied email is a client-chosen email.
do $$
declare t text;
begin
  foreach t in array array['categories', 'people', 'expenses', 'expense_splits'] loop
    execute format('alter table public.%I add column if not exists user_email text', t);
  end loop;
end $$;

create or replace function public.stamp_user_email() returns trigger
  language plpgsql security definer set search_path = public as $$
begin
  select email into new.user_email from public.profiles where id = new.user_id;
  return new;
end $$;

do $$
declare t text;
begin
  foreach t in array array['categories', 'people', 'expenses', 'expense_splits'] loop
    execute format('drop trigger if exists stamp_user_email on public.%I', t);
    -- Before touch_updated_at alphabetically, which is how Postgres orders triggers on the same
    -- event; neither reads what the other writes, so the order is incidental either way.
    execute format(
      'create trigger stamp_user_email before insert or update on public.%I
         for each row execute function public.stamp_user_email()', t);
  end loop;
end $$;

update public.expenses       e set user_email = p.email from public.profiles p where p.id = e.user_id and e.user_email is null;
update public.people         e set user_email = p.email from public.profiles p where p.id = e.user_id and e.user_email is null;
update public.categories     e set user_email = p.email from public.profiles p where p.id = e.user_id and e.user_email is null;
update public.expense_splits e set user_email = p.email from public.profiles p where p.id = e.user_id and e.user_email is null;

-- ---------------------------------------------------------------------------------------------
-- Self-service account deletion.
--
-- There is no client-callable endpoint for a user to remove their own row from auth.users --
-- that table belongs to Supabase Auth, and deleting from it is normally an admin operation gated
-- behind the service_role key, which never belongs in an APK. This function is the narrow way
-- around that: it runs as its owner (security definer) rather than as the caller, so it can reach
-- auth.users, but the only row it will ever touch is auth.uid() -- the caller's own -- which is
-- read from the request's own JWT and cannot be supplied or spoofed by the client.
--
-- Deleting the auth.users row cascades through every foreign key declared above (profiles,
-- categories, people, expenses, expense_splits all reference it with on delete cascade), so one
-- call removes the account and everything it owns from the backend. Nothing on the device is
-- touched: attachments were never uploaded, and the local database is the user's to keep or clear
-- from Settings regardless of what the account did.
create or replace function public.delete_own_account() returns void
  language plpgsql security definer set search_path = public, auth as $$
begin
  if auth.uid() is null then
    raise exception 'Not signed in';
  end if;
  delete from auth.users where id = auth.uid();
end $$;

-- Only a signed-in user may call this, and only PostgREST's RPC route reaches it -- there is
-- nothing here for the anon role, which is the role a stolen anon key would authenticate as.
revoke all on function public.delete_own_account() from public, anon;
grant execute on function public.delete_own_account() to authenticated;

-- ---------------------------------------------------------------------------------------------
-- Mobile numbers, and expenses shared between accounts by them.
--
-- Every number here is E.164 -- "+919876543210" -- as the app writes it. The app is what turns
-- "98765 43210" or "098765-43210" into that form before a number is ever sent, so matching on
-- the server is plain equality.
--
-- One account per number: profiles.phone is unique. It is filled from the account's own metadata
-- (phone_number), which signup and the Settings screen write.
--
-- NOTE: numbers are not verified by SMS. Anybody can register with any number that is not yet
-- taken, and would then see what other users have recorded against it. Turning on phone OTP in
-- Supabase Auth is the fix; nothing below has to change for it.
alter table public.profiles add column if not exists phone text not null default '';
create unique index if not exists profiles_phone_unique on public.profiles (phone) where phone <> '';

-- A person can carry the number they are known by, which is what links them to an account.
alter table public.people add column if not exists phone text not null default '';
create index if not exists people_phone on public.people (phone) where phone <> '';

create or replace function public.sync_profile() returns trigger
  language plpgsql security definer set search_path = public as $$
declare
  wanted text := coalesce(trim(new.raw_user_meta_data ->> 'phone_number'), '');
  taken  boolean := wanted <> '' and exists (
    select 1 from public.profiles where phone = wanted and id <> new.id
  );
begin
  -- A new account may not take a number another account already holds. The app checks first with
  -- phone_is_taken(), so this only fires on a race; raising here is what refuses the signup.
  if taken and tg_op = 'INSERT' then
    raise exception 'That mobile number already has an account' using errcode = 'unique_violation';
  end if;
  insert into public.profiles (id, email, name, phone, auth_completed, last_seen_at)
  values (
    new.id,
    coalesce(new.email, ''),
    coalesce(trim(new.raw_user_meta_data ->> 'full_name'), ''),
    case when taken then '' else wanted end,
    new.email_confirmed_at is not null or new.last_sign_in_at is not null,
    coalesce(new.last_sign_in_at, now())
  )
  on conflict (id) do update set
    email          = excluded.email,
    name           = excluded.name,
    -- An existing account asking for a number somebody else holds keeps the one it had, rather
    -- than failing the update: this trigger also runs on every sign-in, and a refusal here would
    -- lock the account out entirely.
    phone          = case when taken then public.profiles.phone else excluded.phone end,
    auth_completed = public.profiles.auth_completed or excluded.auth_completed,
    last_seen_at   = greatest(public.profiles.last_seen_at, excluded.last_seen_at);
  return new;
end $$;

-- Accounts that set a number before this section existed.
update public.profiles p
   set phone = trim(u.raw_user_meta_data ->> 'phone_number')
  from auth.users u
 where u.id = p.id and p.phone = ''
   and coalesce(trim(u.raw_user_meta_data ->> 'phone_number'), '') <> ''
   and not exists (
     select 1 from public.profiles o
      where o.phone = trim(u.raw_user_meta_data ->> 'phone_number') and o.id <> p.id
   );

-- Whether a number already belongs to an account, asked before signing up or changing number so
-- the user gets an answer rather than a failed signup. Says only yes or no.
create or replace function public.phone_is_taken(p_phone text) returns boolean
  language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.profiles
     where phone = p_phone and p_phone <> '' and id is distinct from auth.uid()
  );
$$;
revoke all on function public.phone_is_taken(text) from public;
grant execute on function public.phone_is_taken(text) to anon, authenticated;

-- Everything other accounts have recorded against the caller's number, in both directions:
--   i_owe = true:  the other user paid, and a share was put on a person with my number.
--   i_owe = false: the other user recorded a person with my number as having paid for them,
--                  so their own share is what they owe me.
-- Runs as its owner because those rows belong to other users and RLS would hide them; the only
-- rows it returns are ones tied to the caller's own number, read from their own profile.
create or replace function public.shared_with_me()
returns table (
  split_id     uuid,
  expense_id   uuid,
  owner_id     uuid,
  owner_name   text,
  owner_phone  text,
  merchant     text,
  category     text,
  note         text,
  paid_at      timestamptz,
  amount_minor bigint,
  currency     text,
  i_owe        boolean,
  settled_at   timestamptz,
  removed      boolean,
  updated_at   timestamptz
)
  language sql stable security definer set search_path = public as $$
  with me as (
    select phone from public.profiles where id = auth.uid() and phone <> ''
  )
  select s.id, e.id, e.user_id,
         coalesce(nullif(o.name, ''), split_part(o.email, '@', 1)), o.phone,
         e.merchant, e.category, e.note, e.paid_at, s.amount_minor, e.currency,
         true, s.settled_at,
         s.deleted_at is not null or e.deleted_at is not null or p.deleted_at is not null,
         greatest(s.updated_at, e.updated_at, p.updated_at)
    from public.expense_splits s
    join public.expenses e on e.id = s.expense_id
    join public.people p   on p.id = s.person_id
    join me                on p.phone = me.phone
    join public.profiles o on o.id = e.user_id
   where e.paid_by is null and e.user_id <> auth.uid()
  union all
  select s.id, e.id, e.user_id,
         coalesce(nullif(o.name, ''), split_part(o.email, '@', 1)), o.phone,
         e.merchant, e.category, e.note, e.paid_at, s.amount_minor, e.currency,
         false, s.settled_at,
         s.deleted_at is not null or e.deleted_at is not null or p.deleted_at is not null,
         greatest(s.updated_at, e.updated_at, p.updated_at)
    from public.expenses e
    join public.people p          on p.id = e.paid_by
    join me                       on p.phone = me.phone
    join public.expense_splits s  on s.expense_id = e.id and s.person_id is null
    join public.profiles o        on o.id = e.user_id
   where e.user_id <> auth.uid();
$$;
revoke all on function public.shared_with_me() from public, anon;
grant execute on function public.shared_with_me() to authenticated;

-- The person a shared share names, telling its owner it has been paid. Settles exactly that one
-- share, and only when it is one shared_with_me() would show the caller. The owner's app picks the
-- change up on its next sync like any other edit, and can reopen it from there.
create or replace function public.mark_shared_settled(p_split uuid) returns void
  language plpgsql security definer set search_path = public as $$
declare
  my_phone text;
begin
  select phone into my_phone from public.profiles where id = auth.uid();
  if my_phone is null or my_phone = '' then
    raise exception 'Add your mobile number in Settings first';
  end if;
  update public.expense_splits s
     set settled_at = now()
    from public.expenses e
   where s.id = p_split and e.id = s.expense_id
     and s.settled_at is null and s.deleted_at is null and e.deleted_at is null
     and e.user_id <> auth.uid()
     and (
       (e.paid_by is null and exists (
          select 1 from public.people p where p.id = s.person_id and p.phone = my_phone))
       or
       (s.person_id is null and exists (
          select 1 from public.people p where p.id = e.paid_by and p.phone = my_phone))
     );
  if not found then
    raise exception 'That share is not one you can settle';
  end if;
end $$;
revoke all on function public.mark_shared_settled(uuid) from public, anon;
grant execute on function public.mark_shared_settled(uuid) to authenticated;
