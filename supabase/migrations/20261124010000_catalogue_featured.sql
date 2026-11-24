-- «Perfil destacado» in the main catalogue list (David 2026-11-24). Each row carries `featured` from the
-- single rule private.is_featured (active simulated subscription + approved version with coverage) and
-- featured profiles come first, then the rest in the usual order (name, profile_id). The catalogue has
-- no concejo, so the zone rule cannot apply here; the owner asked for the list anyway. No fixed
-- position, exclusivity or number of clients is promised.
-- The return type changes, so the function is dropped and recreated with the same public columns.
drop function public.catalogue(text, uuid);

create function public.catalogue(p_trade text default null, p_id uuid default null)
returns table (
    profile_id uuid, name text, description text, trades text[], services text[], coverage jsonb,
    languages text[], phone text, images text[], prices jsonb, rating numeric, review_count integer,
    approved_at timestamptz, business_address text, cover_path text,
    business_lat double precision, business_lng double precision, featured boolean
)
language sql stable security invoker set search_path = ''
as $$
    select p.profile_id, p.name, p.description, p.trades, p.services, p.coverage, p.languages, p.phone,
        p.images, p.prices, p.rating, p.review_count, p.approved_at, p.business_address, p.cover_path,
        p.business_lat, p.business_lng, f.featured
    from public.approved_profiles p
    cross join lateral (select public.is_featured(p.profile_id) as featured) f
    where (p_trade is null or p_trade = any(p.trades))
      and (p_id is null or p.profile_id = p_id)
    order by f.featured desc, p.name, p.profile_id;
$$;
revoke all on function public.catalogue(text, uuid) from public;
grant execute on function public.catalogue(text, uuid) to anon, authenticated;
