-- Aviso «Tu profesional está cerca» (#83, ADR 0002).
-- When «informar posición» accepts a position at 100 m or less of the home of the appointment, with the arrival window
-- open, the server records the appointment event 'professional_near' for the client: once per appointment, never
-- again even if the professional leaves and comes back. If the professional was already near when the window opened,
-- the first accepted position triggers it. Positions rejected outside the window never do.
-- The event flows to notices and push through the existing triggers (docs/avisos-contrato.md): it carries only the
-- appointment and its conversation, never coordinates or names. Its actor is the professional, so the system-actor
-- check is unchanged.

-- 'professional_near' is added to whatever kinds the earlier migrations allow, so parallel producers keep theirs.
do $$
declare
    definition text := (select pg_get_constraintdef(c.oid) from pg_constraint c
        where c.conrelid = 'public.appointment_events'::regclass and c.conname = 'appointment_events_kind_check');
begin
    if definition not like '%ARRAY[%' then
        raise exception 'unexpected appointment_events_kind_check: %', definition;
    end if;
    if definition not like '%''professional_near''%' then
        execute 'alter table public.appointment_events drop constraint appointment_events_kind_check, add constraint '
            || 'appointment_events_kind_check ' || replace(definition, 'ARRAY[', 'ARRAY[''professional_near''::text, ');
    end if;
end;
$$;
-- Once per appointment, also under concurrent reports.
create unique index appointment_events_one_professional_near on public.appointment_events(appointment_id)
    where kind = 'professional_near';

-- Same as #82 except for the proximity notice after the upsert.
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
    -- Proximity notice (#83): at 100 m or less of the home of the appointment, once per appointment.
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
