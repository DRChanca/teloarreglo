-- Search by job location: official concejo boundaries, private professional
-- locations and the server-only candidate lookup used by the search Edge Function.
-- Ticket: Encontrar profesionales por GPS, cobertura y proximidad (#16).
create extension if not exists postgis with schema extensions;

-- Official IGN boundaries (CC BY 4.0). Public administrative data, read-only for clients.
-- Loaded by reviewed SQL from supabase/geo/concejos_asturias.geojson, never by the app.
create table public.concejos (
    ine_code text primary key check (ine_code ~ '^33[0-9]{3}$'),
    name text not null check (length(name) between 1 and 80),
    boundary extensions.geography(MultiPolygon, 4326) not null
);
create index concejos_boundary_idx on public.concejos using gist (boundary);
alter table public.concejos enable row level security;
revoke all on public.concejos from public, anon, authenticated;
grant select (ine_code, name) on public.concejos to anon, authenticated;
create policy concejos_read on public.concejos for select to anon, authenticated using (true);

-- Registered professional location. Exact point and H3 cell never leave the server:
-- no client role has any privilege and no public function returns them.
create table private.professional_locations (
    profile_id uuid primary key references private.professional_profiles(id) on delete cascade,
    location extensions.geography(Point, 4326) not null,
    -- H3 resolution 6 cell (h3-js) of the location, lowercase hex.
    h3_cell text not null check (h3_cell ~ '^[0-9a-f]{15}$'),
    updated_at timestamptz not null default now()
);
create index professional_locations_cell_idx on private.professional_locations (h3_cell);
alter table private.professional_locations enable row level security;
revoke all on private.professional_locations from public, anon, authenticated;

-- Concejos at or near a point. distance_m = 0 means the point is inside.
-- More than one row means a border or an uncertain position: the app asks to confirm.
create function public.resolve_concejo(p_lat double precision, p_lng double precision,
                                       p_tolerance_m double precision default 250)
returns table (ine_code text, name text, distance_m double precision)
-- Definer: clients may read names/codes but not the boundary column itself.
language plpgsql stable security definer set search_path = ''
as $$
declare
    point extensions.geography;
begin
    if p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180
       or p_tolerance_m is null or p_tolerance_m not between 0 and 5000 then
        raise exception using errcode = '22023', message = 'invalid_location';
    end if;
    point := extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography;
    return query
        select c.ine_code, c.name, round(extensions.st_distance(c.boundary, point)::numeric, 1)::double precision
        from public.concejos c
        where extensions.st_dwithin(c.boundary, point, p_tolerance_m)
        order by 3, c.ine_code;
end;
$$;
revoke all on function public.resolve_concejo(double precision, double precision, double precision) from public;
grant execute on function public.resolve_concejo(double precision, double precision, double precision) to anon, authenticated;

-- Server-only: approved profiles of a trade that cover a concejo, with their private
-- location. p_cells limits the lookup to H3 cells; null returns every covering profile
-- (used for the count and when the ring budget is exhausted).
create function public.search_candidates(p_ine_code text, p_trade text, p_cells text[] default null)
returns table (profile_id uuid, lat double precision, lng double precision, h3_cell text)
language sql stable security definer set search_path = ''
as $$
    select p.profile_id,
           extensions.st_y(l.location::extensions.geometry),
           extensions.st_x(l.location::extensions.geometry),
           l.h3_cell
    from public.approved_profiles p
    join private.professional_locations l on l.profile_id = p.profile_id
    where p.coverage @> jsonb_build_array(jsonb_build_object('code', p_ine_code))
      and (p_trade is null or p_trade = any(p.trades))
      and (p_cells is null or l.h3_cell = any(p_cells))
    order by p.profile_id;
$$;
revoke all on function public.search_candidates(text, text, text[]) from public, anon, authenticated;
grant execute on function public.search_candidates(text, text, text[]) to service_role;

-- Server-only: how many located, approved profiles of a trade cover a concejo.
-- Lets the ring search stop as soon as every candidate has been found.
create function public.count_candidates(p_ine_code text, p_trade text)
returns bigint
language sql stable security definer set search_path = ''
as $$
    select count(*)
    from public.approved_profiles p
    join private.professional_locations l on l.profile_id = p.profile_id
    where p.coverage @> jsonb_build_array(jsonb_build_object('code', p_ine_code))
      and (p_trade is null or p_trade = any(p.trades));
$$;
revoke all on function public.count_candidates(text, text) from public, anon, authenticated;
grant execute on function public.count_candidates(text, text) to service_role;
