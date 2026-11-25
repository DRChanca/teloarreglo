-- Ventana de llegada: review fixes (#72, #85).
--   * The approximate circle keeps its centre while the professional stays near it. Drawing a new centre on every
--     accepted report (about every 30 s) would let a client average many centres of a professional who does not move
--     and deduce the position. The centre is drawn again only when the new position is more than 375 m from it, the
--     margin that keeps the position inside the 500 m circle (same ratio as the approximate area of #38).
--   * «Estado de la ventana» (professional): the window of the appointment without sending any position, so that the
--     app sends a position only while the window is open (#85: outside it nothing is ever sent).
--   * The accuracy of a fix is optional: a phone may give a position without it. When present it is still checked.

alter table private.arrival_positions alter column accuracy_m drop not null;

-- The stored centre if the position is still within 375 m of it; otherwise a new one drawn around the position.
create function private.arrival_kept_center(p_appointment_id uuid, p_lat double precision, p_lng double precision,
    out lat double precision, out lng double precision)
language plpgsql volatile set search_path = ''
as $$
declare
    stored private.arrival_positions;
begin
    select * into stored from private.arrival_positions p where p.appointment_id = p_appointment_id;
    if stored.appointment_id is not null and extensions.st_dwithin(
           extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography,
           extensions.st_setsrid(extensions.st_makepoint(stored.area_lng, stored.area_lat), 4326)::extensions.geography,
           375) then
        lat := stored.area_lat;
        lng := stored.area_lng;
        return;
    end if;
    select c.lat, c.lng into lat, lng from private.arrival_area_center(p_lat, p_lng) c;
end;
$$;
revoke all on function private.arrival_kept_center(uuid, double precision, double precision)
    from public, anon, authenticated;

-- Same as #83 except for the kept centre and the optional accuracy.
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
    if (select w.state from private.arrival_window(appt.id) w) <> 'open' then
        return private.arrival_window_answer(appt.id, false);
    end if;
    center := private.arrival_kept_center(appt.id, p_latitude, p_longitude);
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

-- «Estado de la ventana»: only the professional of the appointment; never receives nor stores a position.
create function public.arrival_sharing_window(p_appointment_id uuid)
returns jsonb language plpgsql stable security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
begin
    perform public.current_account();
    if not exists (select 1 from public.appointments a where a.id = p_appointment_id and a.professional_id = actor) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    return (select jsonb_build_object('window', w.state, 'opens_at', w.opens_at, 'closes_at', w.closes_at)
      from private.arrival_window(p_appointment_id) w);
end;
$$;
revoke all on function public.arrival_sharing_window(uuid) from public, anon;
grant execute on function public.arrival_sharing_window(uuid) to authenticated;
