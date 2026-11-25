-- Ventana de llegada (#82, ADR 0002).
-- The server alone knows the arrival window of each confirmed appointment and decides what the client may see of the
-- professional's position:
--   * The window of a confirmed appointment opens 60 min before its agreed start, or 30 min before if at that moment
--     the professional has another appointment in stage 'started'. It is evaluated at every instant: if another
--     appointment starts while the window is open, the window is open again only in the last 30 min. It closes when
--     the appointment stops being confirmed («Comenzar trabajo», cancellation) or 60 min after the start.
--   * «Informar posición» (professional): only the last position of each appointment, one row overwritten with the
--     server time, accepted only while the window is open.
--   * «Dejar de compartir» (professional): deletes that position at once.
--   * «Leer llegada» (client only): closed / open not sharing / open sharing, with a 500 m circle displaced at random
--     beyond 2 km of the home of the appointment and the exact position at 2 km or less.
--   * private.run_arrival_clock(), run by Supabase Cron every minute, deletes the position of every closed window.
-- The positions table is private: no account (nor the administrator through the app) reads it; only these RPCs.

-- The agreed start is that of the accepted proposal (the terms in force; an accepted change supersedes it).
create function private.arrival_window(p_appointment_id uuid, p_at timestamptz default now(),
    out state text, out opens_at timestamptz, out closes_at timestamptz)
language plpgsql stable security definer set search_path = ''
as $$
declare
    appt public.appointments;
    start_at timestamptz;
    other_started boolean;
begin
    select * into appt from public.appointments a where a.id = p_appointment_id;
    select p.start_at into start_at from public.appointment_proposals p
      where p.appointment_id = p_appointment_id and p.status = 'accepted';
    if appt.id is null or appt.stage <> 'confirmed' or start_at is null
       or p_at >= start_at + interval '60 minutes' then
        state := 'closed';
        return;
    end if;
    closes_at := start_at + interval '60 minutes';
    other_started := exists (select 1 from public.appointments o
        where o.professional_id = appt.professional_id and o.id <> appt.id and o.stage = 'started');
    opens_at := start_at - case when other_started then interval '30 minutes' else interval '60 minutes' end;
    state := case when p_at >= opens_at then 'open' else 'not_yet_open' end;
end;
$$;
revoke all on function private.arrival_window(uuid, timestamptz) from public, anon, authenticated;

-- The last position of each appointment with an open window. The circle shown beyond 2 km is drawn once per
-- position, so repeating the read never helps to locate the professional.
create table private.arrival_positions (
    appointment_id uuid primary key references public.appointments(id) on delete cascade,
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    accuracy_m double precision not null check (accuracy_m >= 0),
    area_lat double precision not null,
    area_lng double precision not null,
    reported_at timestamptz not null
);
revoke all on private.arrival_positions from public, anon, authenticated;

-- A point uniformly distributed over the disc of 375 m around the position: the 500 m circle always contains it
-- (same construction and ratio as the approximate area of #38).
create function private.arrival_area_center(p_lat double precision, p_lng double precision,
    out lat double precision, out lng double precision)
language plpgsql volatile set search_path = ''
as $$
declare
    projected extensions.geometry := extensions.st_project(
        extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography,
        375 * sqrt(random()), 2 * pi() * random())::extensions.geometry;
begin
    lat := extensions.st_y(projected);
    lng := extensions.st_x(projected);
end;
$$;
revoke all on function private.arrival_area_center(double precision, double precision) from public, anon, authenticated;

-- What the professional's app needs to know when to start and stop sending.
create function private.arrival_window_answer(p_appointment_id uuid, p_accepted boolean)
returns jsonb language sql stable security definer set search_path = ''
as $$
    select jsonb_build_object('window', w.state, 'accepted', p_accepted, 'opens_at', w.opens_at,
        'closes_at', w.closes_at,
        'reported_at', (select p.reported_at from private.arrival_positions p where p.appointment_id = p_appointment_id))
      from private.arrival_window(p_appointment_id) w
$$;
revoke all on function private.arrival_window_answer(uuid, boolean) from public, anon, authenticated;

create function public.report_arrival_position(p_appointment_id uuid, p_latitude double precision,
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
    if p_latitude is null or p_longitude is null or p_accuracy_m is null or p_latitude not between -90 and 90
       or p_longitude not between -180 and 180 or p_accuracy_m < 0 or p_accuracy_m = 'NaN' then
        raise exception using errcode = '22023', message = 'invalid_position';
    end if;
    if (select w.state from private.arrival_window(appt.id) w) <> 'open' then
        return private.arrival_window_answer(appt.id, false);
    end if;
    center := private.arrival_area_center(p_latitude, p_longitude);
    insert into private.arrival_positions(appointment_id, latitude, longitude, accuracy_m, area_lat, area_lng, reported_at)
      values (appt.id, p_latitude, p_longitude, p_accuracy_m, center.lat, center.lng, now())
    on conflict (appointment_id) do update set latitude = excluded.latitude, longitude = excluded.longitude,
        accuracy_m = excluded.accuracy_m, area_lat = excluded.area_lat, area_lng = excluded.area_lng,
        reported_at = excluded.reported_at;
    -- Extension point: the proximity notice (#83) is decided here, with the position just stored.
    return private.arrival_window_answer(appt.id, true);
end;
$$;
revoke all on function public.report_arrival_position(uuid, double precision, double precision, double precision)
    from public, anon;
grant execute on function public.report_arrival_position(uuid, double precision, double precision, double precision)
    to authenticated;

-- «Dejar de compartir»: only the professional of the appointment; deletes the position at once, whatever the window.
create function public.stop_sharing_arrival(p_appointment_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
begin
    perform public.current_account();
    if not exists (select 1 from public.appointments a where a.id = p_appointment_id and a.professional_id = actor) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    delete from private.arrival_positions p where p.appointment_id = p_appointment_id;
    return private.arrival_window_answer(p_appointment_id, false);
end;
$$;
revoke all on function public.stop_sharing_arrival(uuid) from public, anon;
grant execute on function public.stop_sharing_arrival(uuid) to authenticated;

-- «Leer llegada»: only the client of the appointment. The distance is measured to the home of the appointment; beyond
-- 2 km only the stored circle leaves the server. A position older than 2 min counts as not sharing.
create function public.appointment_arrival(p_appointment_id uuid)
returns jsonb language plpgsql stable security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    win record;
    pos private.arrival_positions;
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
    if extensions.st_distance(
           extensions.st_setsrid(extensions.st_makepoint(pos.longitude, pos.latitude), 4326)::extensions.geography,
           extensions.st_setsrid(extensions.st_makepoint(appt.longitude, appt.latitude), 4326)::extensions.geography)
       > 2000 then
        return jsonb_build_object('state', 'sharing', 'closes_at', win.closes_at, 'precision', 'area',
            'latitude', pos.area_lat, 'longitude', pos.area_lng, 'radius_m', 500, 'updated_at', pos.reported_at);
    end if;
    return jsonb_build_object('state', 'sharing', 'closes_at', win.closes_at, 'precision', 'exact',
        'latitude', pos.latitude, 'longitude', pos.longitude, 'accuracy_m', pos.accuracy_m,
        'updated_at', pos.reported_at);
end;
$$;
revoke all on function public.appointment_arrival(uuid) from public, anon;
grant execute on function public.appointment_arrival(uuid) to authenticated;

-- Arrival clock, run by Supabase Cron every minute next to the completion clock (manual step, docs/citas-contrato.md).
-- Its own function so that it does not depend on how other tickets change private.run_completion_clock().
create function private.run_arrival_clock()
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    deleted integer;
begin
    delete from private.arrival_positions p
      where (select w.state from private.arrival_window(p.appointment_id) w) <> 'open';
    get diagnostics deleted = row_count;
    return jsonb_build_object('deleted', deleted);
end;
$$;
revoke all on function private.run_arrival_clock() from public, anon, authenticated;
