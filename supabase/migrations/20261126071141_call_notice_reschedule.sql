-- «Aviso para llamar» rescheduled (#77, spec #71 story 20): a change of time after the notice was sent sends it again
-- one hour before the new start. The payload now records the start it was sent for (a timestamp, no personal data).
--   * Due: confirmed, starting within the next 60 minutes, and with no call notice for that same start.
--   * A new start replaces the previous notice (unique kind, source, recipient): first the stale notice of a due
--     appointment is deleted (its deliveries cascade), then the new one is inserted unread, so the push fan-out (#32)
--     sends it once. A repeated run with the same start finds the notice for that start and does nothing.
--   * A notice sent before this migration has no recorded start; it is resent once if its appointment is still due.

create or replace function private.run_call_notices(p_limit integer default 200)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare created integer;
begin
    delete from private.notices n
     using public.appointments a join public.agenda_reservations r on r.appointment_id = a.id
     where n.kind = 'call_client' and n.source_id = a.id and n.recipient_id = a.professional_id
       and a.stage = 'confirmed' and lower(r.during) > now() and lower(r.during) <= now() + interval '60 minutes'
       and (n.payload->>'starts_at')::timestamptz is distinct from lower(r.during);

    with due as (
        select a.id, a.professional_id, a.conversation_id, lower(r.during) as starts_at from public.appointments a
          join public.agenda_reservations r on r.appointment_id = a.id
         where a.stage = 'confirmed' and lower(r.during) > now() and lower(r.during) <= now() + interval '60 minutes'
           and not exists (select 1 from private.notices n where n.kind = 'call_client' and n.source_id = a.id
                              and n.recipient_id = a.professional_id)
         order by lower(r.during) limit greatest(1, coalesce(p_limit, 200))
    ), added as (
        insert into private.notices(recipient_id, kind, source_id, payload)
        select due.professional_id, 'call_client', due.id,
               jsonb_build_object('appointment_id', due.id, 'conversation_id', due.conversation_id,
                                  'starts_at', due.starts_at)
          from due
        on conflict do nothing
        returning 1
    )
    select count(*)::integer into created from added;
    return jsonb_build_object('created', created);
end;
$$;
revoke all on function private.run_call_notices(integer) from public, anon, authenticated;
grant execute on function private.run_call_notices(integer) to service_role;
