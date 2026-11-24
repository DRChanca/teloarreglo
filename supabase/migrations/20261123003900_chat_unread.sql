-- Read state is per participant and conversation; the server computes unread counts.
-- Only the participant's own messages-seen mark is stored, never a per-message receipt.
create table public.conversation_reads (
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    last_read_at timestamptz not null,
    primary key (conversation_id, user_id)
);
alter table public.conversation_reads enable row level security;
revoke all on public.conversation_reads from public, anon, authenticated;
grant select on public.conversation_reads to authenticated;
create policy own_read_state on public.conversation_reads for select to authenticated using (
    user_id = auth.uid() and exists (select 1 from public.conversations c where c.id = conversation_id
      and auth.uid() in (c.client_id, c.professional_id))
);

-- Marks read up to the newest persisted message, so a message written later stays unread.
create function public.mark_conversation_read(p_conversation_id uuid)
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
    select coalesce(max(m.created_at), clock_timestamp()) into seen
      from public.chat_messages m where m.conversation_id = p_conversation_id;
    insert into public.conversation_reads(conversation_id, user_id, last_read_at)
      values (p_conversation_id, actor, seen)
      on conflict (conversation_id, user_id) do update
      set last_read_at = greatest(public.conversation_reads.last_read_at, excluded.last_read_at);
end;
$$;
revoke all on function public.mark_conversation_read(uuid) from public, anon;
grant execute on function public.mark_conversation_read(uuid) to authenticated;

-- Inbox for the caller: the business name, the client's self-declared Auth display
-- name (never the email) and the caller's own unread count, newest activity first.
create function public.conversation_inbox()
returns jsonb language plpgsql stable security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
begin
    perform public.current_account();
    return coalesce((
        select jsonb_agg(jsonb_build_object(
            'id', c.id, 'profile_id', c.profile_id, 'client_id', c.client_id,
            'professional_id', c.professional_id, 'name', c.name, 'created_at', c.created_at,
            'client_name', nullif(left(btrim(coalesce(
                nullif(btrim(u.raw_user_meta_data->>'full_name'), ''),
                u.raw_user_meta_data->>'name')), 80), ''),
            'unread_count', (select count(*) from public.chat_messages m
                where m.conversation_id = c.id and m.author_id <> actor
                  and m.created_at > coalesce(r.last_read_at, '-infinity')),
            'last_message_at', (select max(m.created_at) from public.chat_messages m
                where m.conversation_id = c.id)
        ) order by coalesce((select max(m.created_at) from public.chat_messages m
                where m.conversation_id = c.id), c.created_at) desc, c.id)
        from public.conversations c
        join auth.users u on u.id = c.client_id
        left join public.conversation_reads r on r.conversation_id = c.id and r.user_id = actor
        where actor in (c.client_id, c.professional_id)
    ), '[]'::jsonb);
end;
$$;
revoke all on function public.conversation_inbox() from public, anon;
grant execute on function public.conversation_inbox() to authenticated;
