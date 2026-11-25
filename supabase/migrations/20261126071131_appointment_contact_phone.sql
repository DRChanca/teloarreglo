-- Contact phone of the client in the place of an appointment (#79, spec #71).
-- appointment_places() (#38) also returns contact_phone: the client's current number, read from
-- public.contact_phones at call time (never copied). The client always gets it; the professional only while
-- the appointment is confirmed or started, so not while pending or with a proposal, nor once done, completed
-- or cancelled. The exact place rules (client always; professional once location_shared_at is set) do not change.
-- The return type changes, so the function is dropped and created again with the same grants.

drop function public.appointment_places(uuid);

create function public.appointment_places(p_conversation_id uuid)
returns table (appointment_id uuid, exact boolean, address text, latitude double precision,
    longitude double precision, area_lat double precision, area_lng double precision, area_radius_m integer,
    ine_code text, concejo_name text, contact_phone text)
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
               a.area_lat, a.area_lng, a.area_radius_m, a.area_ine_code, c.name,
               case when s.phone then p.phone end
        from public.appointments a
        cross join lateral (select
            a.client_id = actor
                or (a.professional_id = actor and a.location_shared_at is not null) as exact,
            a.client_id = actor
                or (a.professional_id = actor and a.stage in ('confirmed', 'started')) as phone) s
        left join public.concejos c on c.ine_code = a.area_ine_code
        left join public.contact_phones p on p.user_id = a.client_id
        where a.conversation_id = p_conversation_id and actor in (a.client_id, a.professional_id)
        order by a.created_at desc;
end;
$$;
revoke all on function public.appointment_places(uuid) from public, anon;
grant execute on function public.appointment_places(uuid) to authenticated;
