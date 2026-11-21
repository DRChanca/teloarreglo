-- Only trusted review/preparation SQL publishes snapshots. Client edits are drafts.
create table private.professional_profiles (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid unique references auth.users(id),
    private_address text,
    draft jsonb not null default '{}' check (jsonb_typeof(draft) = 'object'),
    review_status text not null default 'pending' check (review_status in ('pending', 'rejected', 'approved'))
);
alter table private.professional_profiles enable row level security;
revoke all on private.professional_profiles from public, anon, authenticated;

-- This table contains ONLY the last approved public version, never owner IDs,
-- home addresses, client addresses, coordinates or pending/rejected changes.
create table public.approved_profiles (
    profile_id uuid primary key references private.professional_profiles(id),
    name text not null check (length(name) between 1 and 120),
    description text not null,
    trades text[] not null check (cardinality(trades) > 0 and trades <@
        array['plumbing','electrical','carpentry','painting','masonry','general']),
    services text[] not null,
    coverage jsonb not null check (jsonb_typeof(coverage) = 'array'),
    languages text[] not null,
    phone text not null,
    images text[] not null default '{}',
    prices jsonb not null default '[]' check (jsonb_typeof(prices) = 'array'),
    rating numeric(3,2) check (rating between 1 and 5),
    review_count integer not null default 0 check (review_count >= 0),
    approved_at timestamptz not null default now(),
    check ((rating is null and review_count = 0) or (rating is not null and review_count > 0))
);
alter table public.approved_profiles enable row level security;
revoke all on public.approved_profiles from public, anon, authenticated;
grant select on public.approved_profiles to anon, authenticated;
create policy public_approved_read on public.approved_profiles for select to anon, authenticated using (true);

create function public.catalogue(p_trade text default null, p_id uuid default null)
returns setof public.approved_profiles
language sql stable security invoker set search_path = ''
as $$
    select p.* from public.approved_profiles p
    where (p_trade is null or p_trade = any(p.trades))
      and (p_id is null or p.profile_id = p_id)
    order by p.name, p.profile_id;
$$;
revoke all on function public.catalogue(text, uuid) from public;
grant execute on function public.catalogue(text, uuid) to anon, authenticated;

create or replace function public.current_account()
returns jsonb
language plpgsql stable security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    account_email text;
begin
    if actor is null then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    select u.email into account_email from auth.users u
    where u.id = actor and u.email_confirmed_at is not null and u.deleted_at is null
      and (u.banned_until is null or u.banned_until <= now());
    if not found then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    return jsonb_build_object(
        'user_id', actor, 'email', account_email, 'can_hire', true,
        'professional_profile_id', (select p.id from private.professional_profiles p where p.owner_id = actor),
        'is_administrator', exists (select 1 from private.administrators a where a.user_id = actor)
    );
end;
$$;

create function public.save_professional_draft(p_profile_id uuid, p_draft jsonb)
returns void
language plpgsql security definer set search_path = ''
as $$
begin
    perform public.current_account();
    if not exists (select 1 from private.professional_profiles p where p.id = p_profile_id and p.owner_id = auth.uid()) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_draft is null or jsonb_typeof(p_draft) <> 'object' or octet_length(p_draft::text) > 65536 then
        raise exception using errcode = '22023', message = 'invalid_draft';
    end if;
    update private.professional_profiles set draft = p_draft, review_status = 'pending'
    where id = p_profile_id and owner_id = auth.uid();
end;
$$;
revoke all on function public.save_professional_draft(uuid, jsonb) from public, anon;
grant execute on function public.save_professional_draft(uuid, jsonb) to authenticated;
