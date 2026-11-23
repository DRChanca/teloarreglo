create table public.chat_messages (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations(id),
    author_id uuid not null references auth.users(id),
    request_id uuid not null,
    body text not null check (length(btrim(body)) between 1 and 4000),
    created_at timestamptz not null default clock_timestamp(),
    unique (conversation_id, author_id, request_id)
);
create index chat_messages_history on public.chat_messages(conversation_id, created_at, id);
alter table public.chat_messages enable row level security;
revoke all on public.chat_messages from public, anon, authenticated;
grant select on public.chat_messages to authenticated;
create policy participants_read on public.chat_messages for select to authenticated using (
    exists (select 1 from public.conversations c where c.id = conversation_id
      and auth.uid() in (c.client_id, c.professional_id))
);

create function public.send_text(p_conversation_id uuid, p_request_id uuid, p_body text)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    result public.chat_messages;
begin
    perform public.current_account();
    if not exists (select 1 from public.conversations c where c.id = p_conversation_id
        and actor in (c.client_id, c.professional_id)) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_request_id is null or p_body is null or length(btrim(p_body)) not between 1 and 4000 then
        raise exception using errcode = '22023', message = 'invalid_message';
    end if;
    insert into public.chat_messages(conversation_id, author_id, request_id, body)
      values (p_conversation_id, actor, p_request_id, btrim(p_body))
      on conflict (conversation_id, author_id, request_id) do nothing;
    select * into result from public.chat_messages
      where conversation_id = p_conversation_id and author_id = actor and request_id = p_request_id;
    if result.body <> btrim(p_body) then
        raise exception using errcode = '22023', message = 'retry_conflict';
    end if;
    return to_jsonb(result);
end;
$$;
revoke all on function public.send_text(uuid, uuid, text) from public, anon;
grant execute on function public.send_text(uuid, uuid, text) to authenticated;
