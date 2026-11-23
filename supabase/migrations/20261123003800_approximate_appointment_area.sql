-- Approximate 400 m area until confirmation (#38).
-- Before an appointment is confirmed the professional may only read a circle: a centre displaced at random
-- (uniform over a 300 m disc, so the home always lies inside the 400 m circle), its radius and the concejo.
-- The exact address and pin (appointments.address/latitude/longitude, #20) stop being readable as table
-- columns for every app account; they are read through appointment_places(), which gives them to the client
-- always and to the professional only once the appointment has been confirmed (location_shared_at).
-- The centre is computed once on insert and kept, so repeating a read never helps to triangulate the home.

alter table public.appointments
    add column area_lat double precision check (area_lat between -90 and 90),
    add column area_lng double precision check (area_lng between -180 and 180),
    add column area_radius_m integer not null default 400 check (area_radius_m = 400),
    add column area_ine_code text references public.concejos(ine_code),
    add column location_shared_at timestamptz;

-- A point uniformly distributed over the disc of 300 m around (p_lat, p_lng): the distance is R·√u
-- (area-uniform, not bunched at the centre) and the bearing uniform; st_project measures on the spheroid.
create function private.approximate_center(p_lat double precision, p_lng double precision,
    out lat double precision, out lng double precision)
language plpgsql volatile set search_path = ''
as $$
declare
    projected extensions.geometry := extensions.st_project(
        extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography,
        300 * sqrt(random()), 2 * pi() * random())::extensions.geometry;
begin
    lat := extensions.st_y(projected);
    lng := extensions.st_x(projected);
end;
$$;
revoke all on function private.approximate_center(double precision, double precision) from public, anon, authenticated;

-- Keeps the area stable: computed on insert, recomputed only if the exact point itself changes (an
-- accepted address change), never overwritten otherwise. Sharing is recorded the first time the stage
-- becomes 'confirmed' (free acceptance #21 or paid confirmation #22) and never undone in later stages.
-- Stages before confirmation that later tickets may add (e.g. awaiting payment) do not reveal anything.
create function private.appointment_area() returns trigger
language plpgsql security definer set search_path = ''
as $$
declare
    center record;
    point extensions.geography := extensions.st_setsrid(
        extensions.st_makepoint(new.longitude, new.latitude), 4326)::extensions.geography;
begin
    if tg_op = 'UPDATE' and old.area_lat is not null
       and (new.latitude, new.longitude) = (old.latitude, old.longitude) then
        new.area_lat := old.area_lat;
        new.area_lng := old.area_lng;
        new.area_ine_code := old.area_ine_code;
    else
        center := private.approximate_center(new.latitude, new.longitude);
        new.area_lat := center.lat;
        new.area_lng := center.lng;
        -- Prefer the covered concejo that admitted the request; otherwise the nearest one (≤ 250 m).
        select c.ine_code into new.area_ine_code from public.concejos c
          left join public.approved_profiles p on p.profile_id = new.profile_id
            and p.coverage @> jsonb_build_array(jsonb_build_object('code', c.ine_code))
          where extensions.st_dwithin(c.boundary, point, 250)
          order by (p.profile_id is null), extensions.st_distance(c.boundary, point), c.ine_code
          limit 1;
    end if;
    new.area_radius_m := 400;
    if tg_op = 'INSERT' then
        new.location_shared_at := case when new.stage = 'confirmed' then now() end;
    else
        new.location_shared_at := coalesce(old.location_shared_at,
            case when new.stage = 'confirmed' then now() end);
    end if;
    return new;
end;
$$;
revoke all on function private.appointment_area() from public, anon, authenticated;
create trigger appointments_area before insert or update on public.appointments
    for each row execute function private.appointment_area();

-- Existing rows (if any) get their area now; already confirmed ones count as shared.
update public.appointments set area_lat = null;
alter table public.appointments alter column area_lat set not null, alter column area_lng set not null;

-- Column privileges: every column but the exact place stays readable under the participants_read policy.
revoke select on public.appointments from authenticated;
grant select (id, conversation_id, profile_id, client_id, professional_id, request_id, problem,
    requested_start, requested_end, version, stage, created_at,
    area_lat, area_lng, area_radius_m, area_ine_code, location_shared_at) on public.appointments to authenticated;

-- The only read path for the exact place. One row per appointment of the conversation that the caller
-- takes part in; exact = false means address/latitude/longitude are null and only the area is given.
create function public.appointment_places(p_conversation_id uuid)
returns table (appointment_id uuid, exact boolean, address text, latitude double precision,
    longitude double precision, area_lat double precision, area_lng double precision, area_radius_m integer,
    ine_code text, concejo_name text)
language plpgsql stable security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
begin
    perform public.current_account();
    return query
        select a.id, s.exact,
               case when s.exact then a.address end,
               case when s.exact then a.latitude end,
               case when s.exact then a.longitude end,
               a.area_lat, a.area_lng, a.area_radius_m, a.area_ine_code, c.name
        from public.appointments a
        cross join lateral (select a.client_id = actor
            or (a.professional_id = actor and a.location_shared_at is not null) as exact) s
        left join public.concejos c on c.ine_code = a.area_ine_code
        where a.conversation_id = p_conversation_id and actor in (a.client_id, a.professional_id)
        order by a.created_at desc;
end;
$$;
revoke all on function public.appointment_places(uuid) from public, anon;
grant execute on function public.appointment_places(uuid) to authenticated;
