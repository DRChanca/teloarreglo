-- Ticks on one's own messages, as in WhatsApp: one grey tick = stored on the server, two grey = delivered to the
-- other participant's device, two blue = read by them. Marks stay per participant and conversation (#39), never
-- per message: a message is delivered/read when its created_at is at or before the other participant's mark.
-- * last_delivered_at: the participant's app fetched the conversation or its inbox, or received its push.
-- * last_read_at: the participant opened the conversation. A delivery-only row has no read mark yet.
-- Read implies delivered. Both marks only move forward. Participants still read only their own rows directly;
-- the other participant's marks are exposed through conversation_receipts, to participants only.
alter table public.conversation_reads alter column last_read_at drop not null;
alter table public.conversation_reads add column last_delivered_at timestamptz;

-- Delivered up to the newest persisted message of one conversation, for the caller (push received, chat fetched).
create function public.mark_conversation_delivered(p_conversation_id uuid)
returns void language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    seen timestamptz;
begin
    perform public.current_account();
    if not exists (select 1 from public.conversations c where c.id = p_conversation_id
        and actor in (c.client_id, c.professional_id)) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    select max(m.created_at) into seen from public.chat_messages m where m.conversation_id = p_conversation_id;
    if seen is not null then
        insert into public.conversation_reads(conversation_id, user_id, last_delivered_at)
            values (p_conversation_id, actor, seen)
        on conflict (conversation_id, user_id) do update
            set last_delivered_at = greatest(public.conversation_reads.last_delivered_at, excluded.last_delivered_at);
    end if;
end;
$$;

-- Delivered up to now in every conversation of the caller (inbox fetched).
create function public.mark_conversations_delivered()
returns void language plpgsql security definer set search_path = ''
as $$
declare actor uuid := auth.uid();
begin
    perform public.current_account();
    insert into public.conversation_reads(conversation_id, user_id, last_delivered_at)
    select c.id, actor, (select max(m.created_at) from public.chat_messages m where m.conversation_id = c.id)
      from public.conversations c
     where actor in (c.client_id, c.professional_id)
       and exists (select 1 from public.chat_messages m where m.conversation_id = c.id)
    on conflict (conversation_id, user_id) do update
        set last_delivered_at = greatest(public.conversation_reads.last_delivered_at, excluded.last_delivered_at);
end;
$$;

-- The other participant's marks, for the caller's ticks: {delivered_at, read_at}, null when absent.
create function public.conversation_receipts(p_conversation_id uuid)
returns jsonb language plpgsql stable security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    other uuid;
    mark public.conversation_reads;
begin
    perform public.current_account();
    select case when actor = c.client_id then c.professional_id else c.client_id end into other
      from public.conversations c where c.id = p_conversation_id and actor in (c.client_id, c.professional_id);
    if other is null then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    select * into mark from public.conversation_reads r where r.conversation_id = p_conversation_id and r.user_id = other;
    return jsonb_build_object(
        'delivered_at', greatest(mark.last_delivered_at, mark.last_read_at),
        'read_at', mark.last_read_at);
end;
$$;
revoke all on function public.mark_conversation_delivered(uuid), public.mark_conversations_delivered(),
    public.conversation_receipts(uuid) from public, anon;
grant execute on function public.mark_conversation_delivered(uuid), public.mark_conversations_delivered(),
    public.conversation_receipts(uuid) to authenticated;
