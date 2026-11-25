-- «Aviso para llamar» (#77, spec #71): one hour before a confirmed appointment starts, its professional gets a notice
-- to call the client. Supabase Cron calls private.run_call_notices() every minute (manual step per project, see
-- docs/avisos-contrato.md), so it arrives with both apps closed. It is independent of the completion clock (#27)
-- and of «Ventana de llegada» (#72): neither is changed here.
--   * Current start: the appointment's agenda reservation, which an accepted change of time moves (#23). The routine
--     always reads it, so a start changed before the notice is sent counts with its new time.
--   * Due: confirmed and starting within the next 60 minutes. One confirmed with less margin is due on the next run.
--     Started, done, completed, cancelled and pending appointments never get it.
--   * Once per appointment: the notice itself is the mark (unique kind, source, recipient), so a repeated or
--     overlapping run never duplicates it.
--   * Push: the notice is unread when inserted, so the existing fan-out (#32) queues its deliveries. The payload holds
--     identifiers only, never the client's phone (read at tap time from the protected function, #79/#81).

create function private.run_call_notices(p_limit integer default 200)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare created integer;
begin
    with due as (
        select a.id, a.professional_id, a.conversation_id from public.appointments a
          join public.agenda_reservations r on r.appointment_id = a.id
         where a.stage = 'confirmed' and lower(r.during) > now() and lower(r.during) <= now() + interval '60 minutes'
           and not exists (select 1 from private.notices n where n.kind = 'call_client' and n.source_id = a.id
                              and n.recipient_id = a.professional_id)
         order by lower(r.during) limit greatest(1, coalesce(p_limit, 200))
    ), added as (
        insert into private.notices(recipient_id, kind, source_id, payload)
        select due.professional_id, 'call_client', due.id,
               jsonb_build_object('appointment_id', due.id, 'conversation_id', due.conversation_id)
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
