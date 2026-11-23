-- Private home address of each account (#37). Asked after signing in; proximity search and
-- appointment requests start from it. Only its owner reads it (RLS) and writes it (definer RPC).
-- No public view, search function, catalogue or administrative path reads this table.
-- Coordinates are plain lat/lng doubles, like approved_profiles.business_lat/lng (#17) and
-- appointments.latitude/longitude (#20); build a geography with st_makepoint(lng, lat) when needed.
create table public.home_addresses (
    user_id uuid primary key references auth.users(id) on delete cascade,
    address text not null check (length(address) between 1 and 300 and address = btrim(address)),
    lat double precision not null check (lat between -90 and 90),
    lng double precision not null check (lng between -180 and 180),
    -- Concejo containing the point (or within 250 m); null outside every loaded concejo.
    ine_code text references public.concejos(ine_code),
    -- places: Google Places autocomplete; pin: map; gps: one fix. Text typed by hand for pin/gps.
    source text not null check (source in ('places', 'pin', 'gps')),
    place_id text check (place_id is null or length(place_id) between 1 and 300),
    updated_at timestamptz not null default now(),
    constraint home_addresses_place_id_source_check check ((source = 'places') = (place_id is not null))
);
alter table public.home_addresses enable row level security;
revoke all on public.home_addresses from public, anon, authenticated;
grant select on public.home_addresses to authenticated;
create policy home_addresses_owner_read on public.home_addresses for select to authenticated
    using (user_id = (select auth.uid()));

-- The signed-in account's home with its concejo name; empty when it has none.
create function public.home_address()
returns table (address text, lat double precision, lng double precision, ine_code text, concejo_name text,
               source text, place_id text, updated_at timestamptz)
language plpgsql stable security definer set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    return query
        select h.address, h.lat, h.lng, h.ine_code, c.name, h.source, h.place_id, h.updated_at
        from public.home_addresses h left join public.concejos c on c.ine_code = h.ine_code
        where h.user_id = auth.uid();
end;
$$;
revoke all on function public.home_address() from public, anon;
grant execute on function public.home_address() to authenticated;

-- Creates or replaces the caller's home. The concejo is always derived here from the point.
create function public.set_home_address(p_address text, p_lat double precision, p_lng double precision,
                                        p_source text, p_place_id text default null)
returns table (address text, lat double precision, lng double precision, ine_code text, concejo_name text,
               source text, place_id text, updated_at timestamptz)
language plpgsql volatile security definer set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_address text := btrim(p_address);
    v_place text := case when p_source = 'places' then nullif(btrim(p_place_id), '') end;
    v_concejo text;
begin
    if v_user is null then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    if v_address is null or length(v_address) not between 1 and 300
       or p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180
       or p_source is null or p_source not in ('places', 'pin', 'gps')
       or (p_source = 'places' and (v_place is null or length(v_place) > 300)) then
        raise exception using errcode = '22023', message = 'invalid_home_address';
    end if;
    select c.ine_code into v_concejo
    from public.concejos c
    where extensions.st_dwithin(c.boundary,
        extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography, 250)
    order by extensions.st_distance(c.boundary,
        extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography), c.ine_code
    limit 1;
    insert into public.home_addresses as h (user_id, address, lat, lng, ine_code, source, place_id, updated_at)
    values (v_user, v_address, p_lat, p_lng, v_concejo, p_source, v_place, now())
    on conflict (user_id) do update set address = excluded.address, lat = excluded.lat, lng = excluded.lng,
        ine_code = excluded.ine_code, source = excluded.source, place_id = excluded.place_id, updated_at = now();
    return query select * from public.home_address();
end;
$$;
revoke all on function public.set_home_address(text, double precision, double precision, text, text) from public, anon;
grant execute on function public.set_home_address(text, double precision, double precision, text, text) to authenticated;
