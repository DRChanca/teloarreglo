-- Persistent in-app notices for every confirmed event (#32).
-- private.notices (#29) becomes the single per-account store. It is fed by triggers on the
-- tables that already record confirmed operations, so producers never call a notice API:
--   * public.chat_messages     -> kind 'message' for the other participant (never the body);
--   * public.appointment_events -> kind 'appointment' with the event kind, whatever it is, so new
--     producers (#26 incidents, #27 reminders and clock) flow through without changes here;
--   * administrative decisions keep inserting their own notices (profile_review, #29).
-- A notice is only an announcement: it never carries message bodies, addresses or coordinates,
-- and push (delivered separately) is never the only copy.

alter table private.notices add column read_at timestamptz;

create function private.notice_chat_message() returns trigger
language plpgsql security definer set search_path = ''
as $$
begin
    insert into private.notices(recipient_id, kind, source_id, payload, created_at)
    select case when new.author_id = c.client_id then c.professional_id else c.client_id end,
           'message', new.id, jsonb_build_object('conversation_id', c.id), new.created_at
      from public.conversations c where c.id = new.conversation_id
    on conflict do nothing;
    return null;
end;
$$;
revoke all on function private.notice_chat_message() from public, anon, authenticated;
create trigger chat_messages_notice after insert on public.chat_messages
    for each row execute function private.notice_chat_message();

create function private.notice_appointment_event() returns trigger
language plpgsql security definer set search_path = ''
as $$
begin
    insert into private.notices(recipient_id, kind, source_id, payload, created_at)
    select new.recipient_id, 'appointment', new.id, jsonb_build_object(
               'appointment_id', a.id, 'conversation_id', a.conversation_id, 'event', new.kind),
           new.created_at
      from public.appointments a where a.id = new.appointment_id
    on conflict do nothing;
    return null;
end;
$$;
revoke all on function private.notice_appointment_event() from public, anon, authenticated;
create trigger appointment_events_notice after insert on public.appointment_events
    for each row execute function private.notice_appointment_event();

-- Earlier appointment events stay consultable; they are history, so they start as read.
insert into private.notices(recipient_id, kind, source_id, payload, created_at, read_at)
select e.recipient_id, 'appointment', e.id, jsonb_build_object(
           'appointment_id', a.id, 'conversation_id', a.conversation_id, 'event', e.kind), e.created_at, now()
  from public.appointment_events e join public.appointments a on a.id = e.appointment_id
on conflict do nothing;

-- Messages in a conversation are grouped into its newest notice: `count` unread messages, read once the
-- conversation is opened (#39 read mark) or the notice is marked read. Other notices stand alone.
create or replace function public.my_notices()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare actor uuid := auth.uid();
begin
    perform public.current_account();
    return coalesce((select jsonb_agg(jsonb_build_object('id', g.id, 'kind', g.kind, 'payload', g.payload,
            'created_at', g.created_at, 'read', g.unread = 0, 'count', greatest(g.unread, 1))
            order by g.created_at desc, g.id)
        from (select g.* from (
            select n.id, n.kind, n.payload, n.created_at,
                   count(*) filter (where not n.is_read) over (partition by n.thread) as unread,
                   row_number() over (partition by n.thread order by n.created_at desc, n.id desc) as position
              from (
                select x.*, case when x.kind = 'message' then x.payload->>'conversation_id' else x.id::text end as thread,
                       x.read_at is not null or (x.kind = 'message' and exists (
                           select 1 from public.conversation_reads r
                            where r.user_id = actor and r.conversation_id = (x.payload->>'conversation_id')::uuid
                              and r.last_read_at >= x.created_at)) as is_read
                  from private.notices x where x.recipient_id = actor
              ) n
        ) g where g.position = 1 order by g.created_at desc, g.id limit 100) g), '[]'::jsonb);
end;
$$;
revoke all on function public.my_notices() from public, anon;
grant execute on function public.my_notices() to authenticated;

-- Marks the caller's own notices read; other ids are ignored. A grouped message notice also marks the earlier
-- messages of its conversation, so the group stays read.
create function public.mark_notices_read(p_ids uuid[])
returns void language plpgsql security definer set search_path = ''
as $$
declare actor uuid := auth.uid();
begin
    perform public.current_account();
    update private.notices n set read_at = now()
     where n.recipient_id = actor and n.read_at is null and (n.id = any(p_ids) or (n.kind = 'message' and exists (
           select 1 from private.notices g where g.id = any(p_ids) and g.recipient_id = actor and g.kind = 'message'
              and g.payload->>'conversation_id' = n.payload->>'conversation_id' and g.created_at >= n.created_at)));
end;
$$;
revoke all on function public.mark_notices_read(uuid[]) from public, anon;
grant execute on function public.mark_notices_read(uuid[]) to authenticated;
