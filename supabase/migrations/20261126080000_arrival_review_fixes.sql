-- Ventana de llegada: fixes of the verification of #72 (#82, #84, #85). Every change to the contract is additive.
--   * «Dejar de compartir» is final until the professional chooses to share again. A report already on its way when
--     the professional stopped could reach the server after the deletion and show the position again for up to 2 min.
--     stop_sharing_arrival now leaves a mark (private.arrival_stops, no position) and report_arrival_position refuses
--     every position while it exists, answering "stopped": true. The new RPC start_sharing_arrival, called when the
--     professional chooses «Compartir mi ubicación», clears it.
--   * The circle beyond 2 km no longer gets an independent random centre each time the position row is created (on
--     every new appointment, after stop and share again, after the clock deletes it). Independent centres around the
--     same place (the professional's home) could be intersected by a recurring client. The centre is now the centre
--     of the cell of a 650 m grid that contains the position; the grid of each professional is displaced by an
--     offset derived from a server secret, so the same place always gives the same centre and nothing finer than its
--     cell. The farthest point of a cell is about 462 m from its centre, so the 500 m circle always contains the
--     position. The centre in use is kept while new positions stay within 450 m of it, so a professional standing
--     near the edge of a cell does not jump between two centres.
--   * «Leer llegada» carries age_s, the age of the position by the server clock, so the client's app never depends on
--     the phone's clock (a phone more than 2 min ahead used to read every position as old).
--   * «Estado de la ventana» carries start_at, the agreed start, so the professional's app can move its alarm when an
--     accepted change brings the appointment forward, and "stopped": true while the professional's stop holds.

-- The professional's «Dejar de compartir» per appointment, until they choose to share again. Holds no position.
create table private.arrival_stops (
    appointment_id uuid primary key references public.appointments(id) on delete cascade,
    stopped_at timestamptz not null default now()
);
revoke all on private.arrival_stops from public, anon, authenticated;

-- One server secret for the grid offsets; never readable by any account.
create table private.arrival_grid_secret (
    singleton boolean primary key default true check (singleton),
    secret bytea not null
);
insert into private.arrival_grid_secret(secret)
    values (decode(replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''), 'hex'));
revoke all on private.arrival_grid_secret from public, anon, authenticated;

-- The centre of the cell of the professional's grid that contains the position. Cells are 650 m high and 650 m wide
-- at the latitude of their row (degrees converted with 111 320 m, which errs by under 0.5% on the ellipsoid), so the
-- farthest point of a cell is about 462 m from its centre. The offset of the grid, a fraction of a cell in each
-- direction, comes from sha256(secret || professional): unknown outside the server and fixed for each professional.
create function private.arrival_grid_center(p_professional_id uuid, p_lat double precision, p_lng double precision,
    out lat double precision, out lng double precision)
language plpgsql stable security definer set search_path = ''
as $$
declare
    cell constant double precision := 650;
    metres_per_degree constant double precision := 111320;
    digest bytea := sha256((select s.secret from private.arrival_grid_secret s) || uuid_send(p_professional_id));
    offset_lat double precision := (get_byte(digest, 0)::bigint * 16777216 + get_byte(digest, 1) * 65536
        + get_byte(digest, 2) * 256 + get_byte(digest, 3)) / 4294967296.0;
    offset_lng double precision := (get_byte(digest, 4)::bigint * 16777216 + get_byte(digest, 5) * 65536
        + get_byte(digest, 6) * 256 + get_byte(digest, 7)) / 4294967296.0;
    step_lat double precision := cell / metres_per_degree;
    step_lng double precision;
begin
    lat := (floor((p_lat - offset_lat * step_lat) / step_lat) + 0.5 + offset_lat) * step_lat;
    step_lng := cell / (metres_per_degree * greatest(cos(radians(lat)), 0.01));
    lng := (floor((p_lng - offset_lng * step_lng) / step_lng) + 0.5 + offset_lng) * step_lng;
    lat := least(greatest(lat, -90), 90);
    lng := case when lng >= 180 then lng - 360 when lng < -180 then lng + 360 else lng end;
end;
$$;
revoke all on function private.arrival_grid_center(uuid, double precision, double precision)
    from public, anon, authenticated;

-- The stored centre while the position stays within 450 m of it; otherwise the centre of the position's cell.
create function private.arrival_shown_center(p_appointment_id uuid, p_professional_id uuid, p_lat double precision,
    p_lng double precision, out lat double precision, out lng double precision)
language plpgsql stable security definer set search_path = ''
as $$
declare
    stored private.arrival_positions;
begin
    select * into stored from private.arrival_positions p where p.appointment_id = p_appointment_id;
    if stored.appointment_id is not null and extensions.st_dwithin(
           extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography,
           extensions.st_setsrid(extensions.st_makepoint(stored.area_lng, stored.area_lat), 4326)::extensions.geography,
           450) then
        lat := stored.area_lat;
        lng := stored.area_lng;
        return;
    end if;
    select c.lat, c.lng into lat, lng from private.arrival_grid_center(p_professional_id, p_lat, p_lng) c;
end;
$$;
revoke all on function private.arrival_shown_center(uuid, uuid, double precision, double precision)
    from public, anon, authenticated;

-- Same answer as before, plus "stopped": true while the professional's stop holds.
create or replace function private.arrival_window_answer(p_appointment_id uuid, p_accepted boolean)
returns jsonb language sql stable security definer set search_path = ''
as $$
    select jsonb_build_object('window', w.state, 'accepted', p_accepted, 'opens_at', w.opens_at,
        'closes_at', w.closes_at,
        'reported_at', (select p.reported_at from private.arrival_positions p where p.appointment_id = p_appointment_id))
        || case when exists (select 1 from private.arrival_stops s where s.appointment_id = p_appointment_id)
           then jsonb_build_object('stopped', true) else '{}'::jsonb end
      from private.arrival_window(p_appointment_id) w
$$;
revoke all on function private.arrival_window_answer(uuid, boolean) from public, anon, authenticated;

-- Same as 20261125007400 except for the stop mark and the grid centre.
create or replace function public.report_arrival_position(p_appointment_id uuid, p_latitude double precision,
    p_longitude double precision, p_accuracy_m double precision)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    center record;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id;
    if appt.id is null or appt.professional_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_latitude is null or p_longitude is null or p_latitude not between -90 and 90
       or p_longitude not between -180 and 180 or p_accuracy_m < 0 or p_accuracy_m = 'NaN' then
        raise exception using errcode = '22023', message = 'invalid_position';
    end if;
    -- After «Dejar de compartir» no position counts until the professional chooses to share again.
    if exists (select 1 from private.arrival_stops s where s.appointment_id = appt.id)
       or (select w.state from private.arrival_window(appt.id) w) <> 'open' then
        return private.arrival_window_answer(appt.id, false);
    end if;
    center := private.arrival_shown_center(appt.id, appt.professional_id, p_latitude, p_longitude);
    insert into private.arrival_positions(appointment_id, latitude, longitude, accuracy_m, area_lat, area_lng, reported_at)
      values (appt.id, p_latitude, p_longitude, p_accuracy_m, center.lat, center.lng, now())
    on conflict (appointment_id) do update set latitude = excluded.latitude, longitude = excluded.longitude,
        accuracy_m = excluded.accuracy_m, area_lat = excluded.area_lat, area_lng = excluded.area_lng,
        reported_at = excluded.reported_at;
    -- Proximity notice (#83): at 100 m or less of the address of the appointment, once per appointment.
    if extensions.st_dwithin(
           extensions.st_setsrid(extensions.st_makepoint(p_longitude, p_latitude), 4326)::extensions.geography,
           extensions.st_setsrid(extensions.st_makepoint(appt.longitude, appt.latitude), 4326)::extensions.geography,
           100) then
        insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
          values (appt.id, 'professional_near', appt.professional_id, appt.client_id)
        on conflict do nothing;
    end if;
    return private.arrival_window_answer(appt.id, true);
end;
$$;
revoke all on function public.report_arrival_position(uuid, double precision, double precision, double precision)
    from public, anon;
grant execute on function public.report_arrival_position(uuid, double precision, double precision, double precision)
    to authenticated;

-- «Dejar de compartir»: deletes the position at once and leaves the stop mark, whatever the window.
create or replace function public.stop_sharing_arrival(p_appointment_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
begin
    perform public.current_account();
    if not exists (select 1 from public.appointments a where a.id = p_appointment_id and a.professional_id = actor) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    insert into private.arrival_stops(appointment_id) values (p_appointment_id)
    on conflict (appointment_id) do update set stopped_at = now();
    delete from private.arrival_positions p where p.appointment_id = p_appointment_id;
    return private.arrival_window_answer(p_appointment_id, false);
end;
$$;
revoke all on function public.stop_sharing_arrival(uuid) from public, anon;
grant execute on function public.stop_sharing_arrival(uuid) to authenticated;

-- The window as the professional's app needs it: state, times, agreed start and the stop mark.
create function private.arrival_sharing_answer(p_appointment_id uuid)
returns jsonb language sql stable security definer set search_path = ''
as $$
    select jsonb_build_object('window', w.state, 'opens_at', w.opens_at, 'closes_at', w.closes_at,
        'start_at', case when w.state <> 'closed' then (select p.start_at from public.appointment_proposals p
            where p.appointment_id = p_appointment_id and p.status = 'accepted') end)
        || case when exists (select 1 from private.arrival_stops s where s.appointment_id = p_appointment_id)
           then jsonb_build_object('stopped', true) else '{}'::jsonb end
      from private.arrival_window(p_appointment_id) w
$$;
revoke all on function private.arrival_sharing_answer(uuid) from public, anon, authenticated;

-- «Estado de la ventana»: only the professional of the appointment; never receives nor stores a position.
create or replace function public.arrival_sharing_window(p_appointment_id uuid)
returns jsonb language plpgsql stable security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
begin
    perform public.current_account();
    if not exists (select 1 from public.appointments a where a.id = p_appointment_id and a.professional_id = actor) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    return private.arrival_sharing_answer(p_appointment_id);
end;
$$;
revoke all on function public.arrival_sharing_window(uuid) from public, anon;
grant execute on function public.arrival_sharing_window(uuid) to authenticated;

-- «Compartir mi ubicación» chosen again: clears the stop mark. Receives no position; answers like the window state.
create function public.start_sharing_arrival(p_appointment_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
begin
    perform public.current_account();
    if not exists (select 1 from public.appointments a where a.id = p_appointment_id and a.professional_id = actor) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    delete from private.arrival_stops s where s.appointment_id = p_appointment_id;
    return private.arrival_sharing_answer(p_appointment_id);
end;
$$;
revoke all on function public.start_sharing_arrival(uuid) from public, anon;
grant execute on function public.start_sharing_arrival(uuid) to authenticated;

-- Same as #82 plus age_s: seconds since the position, by the server clock.
create or replace function public.appointment_arrival(p_appointment_id uuid)
returns jsonb language plpgsql stable security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    win record;
    pos private.arrival_positions;
    age double precision;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id;
    if appt.id is null or appt.client_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    select * into win from private.arrival_window(appt.id);
    if win.state <> 'open' then
        return jsonb_build_object('state', 'closed');
    end if;
    select * into pos from private.arrival_positions p
      where p.appointment_id = appt.id and p.reported_at >= now() - interval '2 minutes';
    if pos.appointment_id is null then
        return jsonb_build_object('state', 'not_sharing', 'closes_at', win.closes_at);
    end if;
    age := greatest(extract(epoch from now() - pos.reported_at)::double precision, 0);
    if extensions.st_distance(
           extensions.st_setsrid(extensions.st_makepoint(pos.longitude, pos.latitude), 4326)::extensions.geography,
           extensions.st_setsrid(extensions.st_makepoint(appt.longitude, appt.latitude), 4326)::extensions.geography)
       > 2000 then
        return jsonb_build_object('state', 'sharing', 'closes_at', win.closes_at, 'precision', 'area',
            'latitude', pos.area_lat, 'longitude', pos.area_lng, 'radius_m', 500, 'updated_at', pos.reported_at,
            'age_s', age);
    end if;
    return jsonb_build_object('state', 'sharing', 'closes_at', win.closes_at, 'precision', 'exact',
        'latitude', pos.latitude, 'longitude', pos.longitude, 'accuracy_m', pos.accuracy_m,
        'updated_at', pos.reported_at, 'age_s', age);
end;
$$;
revoke all on function public.appointment_arrival(uuid) from public, anon;
grant execute on function public.appointment_arrival(uuid) to authenticated;

-- Nothing uses the random centres any more.
drop function private.arrival_kept_center(uuid, double precision, double precision);
drop function private.arrival_area_center(double precision, double precision);
