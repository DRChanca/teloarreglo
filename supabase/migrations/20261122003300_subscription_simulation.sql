-- Optional 5 €/month subscription, simulated for the demo (#33).
-- Nothing is charged: there is no place for bank, card, invoice or renewal data, and the
-- simulation never touches the public profile, its review status, reviews or search data.
-- "Destacado" zones are derived from the last APPROVED coverage only, so promotion never
-- reaches an unreviewed profile or a concejo the professional does not serve.

create table private.subscription_simulations (
    profile_id uuid primary key references private.professional_profiles(id),
    active boolean not null,
    updated_at timestamptz not null default clock_timestamp()
);
alter table private.subscription_simulations enable row level security;
revoke all on private.subscription_simulations from public, anon, authenticated;

create function private.subscription_result(p_profile uuid)
returns jsonb language sql stable set search_path = '' as $$
    select jsonb_build_object(
        'profile_id', p_profile,
        'price_cents', 500, 'currency', 'EUR', 'period', 'month',
        'simulated', coalesce((select s.active from private.subscription_simulations s where s.profile_id = p_profile), false),
        'charged', false,
        'featured_zones', coalesce((select a.coverage from public.approved_profiles a where a.profile_id = p_profile), '[]'::jsonb));
$$;
revoke all on function private.subscription_result(uuid) from public, anon, authenticated;

-- null when the account has no professional profile (clients do not see the scene).
create function public.my_subscription()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare profile uuid;
begin
    if auth.uid() is null then raise exception using errcode = '42501', message = 'permission_denied'; end if;
    select p.id into profile from private.professional_profiles p where p.owner_id = auth.uid();
    if profile is null then return null; end if;
    return private.subscription_result(profile);
end;
$$;
revoke all on function public.my_subscription() from public, anon, authenticated;
grant execute on function public.my_subscription() to authenticated;

-- Idempotent: setting the same choice twice is the same state. Never charges.
create function public.simulate_subscription(p_active boolean)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare profile uuid;
begin
    if auth.uid() is null then raise exception using errcode = '42501', message = 'permission_denied'; end if;
    select p.id into profile from private.professional_profiles p where p.owner_id = auth.uid();
    if profile is null then raise exception using errcode = '42501', message = 'professional_profile_required'; end if;
    if p_active is null then raise exception using errcode = '22023', message = 'invalid_choice'; end if;
    insert into private.subscription_simulations(profile_id, active) values (profile, p_active)
        on conflict (profile_id) do update set active = excluded.active, updated_at = clock_timestamp()
        where private.subscription_simulations.active is distinct from excluded.active;
    return private.subscription_result(profile);
end;
$$;
revoke all on function public.simulate_subscription(boolean) from public, anon, authenticated;
grant execute on function public.simulate_subscription(boolean) to authenticated;
