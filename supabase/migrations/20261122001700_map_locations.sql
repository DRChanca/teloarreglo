-- Map and manual location (#17).
-- 1) Optional public commercial point for the map and external navigation. It belongs to the
--    approved commercial address: never a private home, never the search location
--    (private.professional_locations stays server-only). Existing profiles keep null until a
--    separate reviewed content update; nothing here loads or changes prepared data.
-- 2) A representative point per concejo for the manual choice when GPS is not used.
alter table public.approved_profiles
    add column business_lat double precision,
    add column business_lng double precision,
    add constraint approved_profiles_business_point_check check (
        (business_lat is null and business_lng is null)
        or (business_lat is not null and business_lng is not null and business_address is not null
            and business_lat between -90 and 90 and business_lng between -180 and 180)
    );
comment on column public.approved_profiles.business_lat is
    'Latitude of the approved public commercial address; null when there is none. Never a private location.';
comment on column public.approved_profiles.business_lng is
    'Longitude of the approved public commercial address; null when there is none. Never a private location.';

-- A new approved address must not keep the point of the previous one.
create function private.drop_stale_business_point()
returns trigger
language plpgsql set search_path = ''
as $$
begin
    if new.business_address is distinct from old.business_address
       and new.business_lat is not distinct from old.business_lat
       and new.business_lng is not distinct from old.business_lng then
        new.business_lat := null;
        new.business_lng := null;
    end if;
    return new;
end;
$$;
revoke all on function private.drop_stale_business_point() from public;
create trigger approved_profiles_drop_stale_business_point
    before update of business_address on public.approved_profiles
    for each row execute function private.drop_stale_business_point();

-- Concejo names with a point guaranteed inside each one (public administrative data).
-- Definer: clients still cannot download the boundary itself.
create function public.concejo_anchors()
returns table (ine_code text, name text, lat double precision, lng double precision)
language sql stable security definer set search_path = ''
as $$
    select c.ine_code, c.name,
           extensions.st_y(p.point), extensions.st_x(p.point)
    from public.concejos c
    cross join lateral (select extensions.st_pointonsurface(c.boundary::extensions.geometry) as point) p
    order by c.name, c.ine_code;
$$;
revoke all on function public.concejo_anchors() from public;
grant execute on function public.concejo_anchors() to anon, authenticated;
