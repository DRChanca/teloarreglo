-- «Destacado» visible to clients (#33 follow-up, David 2026-11-23). An active simulated subscription on a
-- profile with an approved public version is featured: the nearby search ranks it first in the concejos of
-- its approved coverage (search_candidates already filters by that coverage) and the public profile shows
-- the badge. Same rule as my_subscription().featured_zones: no approved version, no Destacado anywhere.
-- Nothing is charged and no position, exclusivity or number of clients is promised.

create function private.is_featured(p_profile_id uuid)
returns boolean language sql stable security definer set search_path = ''
as $$
    select exists (select 1 from private.subscription_simulations s
        join public.approved_profiles a on a.profile_id = s.profile_id
        where s.profile_id = p_profile_id and s.active and jsonb_array_length(a.coverage) > 0);
$$;
revoke all on function private.is_featured(uuid) from public, anon, authenticated;

-- Public badge for one profile: true only for an approved, subscribed profile. Reveals nothing else.
create function public.is_featured(p_profile_id uuid)
returns boolean language sql stable security definer set search_path = ''
as $$ select coalesce(private.is_featured(p_profile_id), false); $$;
revoke all on function public.is_featured(uuid) from public, anon, authenticated;
grant execute on function public.is_featured(uuid) to anon, authenticated;

-- Server-only, like search_candidates: the featured subset of the candidates of a concejo and trade.
create function public.featured_candidates(p_ine_code text, p_trade text)
returns table (profile_id uuid, lat double precision, lng double precision, h3_cell text)
language sql stable security definer set search_path = ''
as $$
    select c.profile_id, c.lat, c.lng, c.h3_cell
    from public.search_candidates(p_ine_code, p_trade, null) c
    where private.is_featured(c.profile_id)
    order by c.profile_id;
$$;
revoke all on function public.featured_candidates(text, text) from public, anon, authenticated;
grant execute on function public.featured_candidates(text, text) to service_role;
