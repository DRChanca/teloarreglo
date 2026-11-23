-- Chat attachments (#18 photos; #19 extends the same boundary for video).
-- A message exists only after Storage holds the object, so a cancelled or failed
-- upload is never sent. Objects are private: only the two participants can read
-- a confirmed attachment, through short signed URLs. No administrator exception.
alter table public.chat_messages
    add column attachment_kind text check (attachment_kind in ('photo', 'video')),
    add column attachment_path text unique,
    add column attachment_type text,
    add column attachment_size bigint;
alter table public.chat_messages drop constraint chat_messages_body_check;
alter table public.chat_messages add constraint chat_messages_content check (
    (attachment_path is null and length(btrim(body)) between 1 and 4000)
    or (attachment_path is not null and attachment_kind is not null and attachment_type is not null
        and attachment_size > 0 and length(body) <= 4000));

create table private.chat_uploads (
    conversation_id uuid not null references public.conversations(id),
    author_id uuid not null references auth.users(id),
    request_id uuid not null,
    kind text not null,
    mime text not null,
    digest text not null check (digest ~ '^[0-9a-f]{64}$'),
    object_path text not null unique,
    created_at timestamptz not null default clock_timestamp(),
    primary key (conversation_id, author_id, request_id)
);
alter table private.chat_uploads enable row level security;
revoke all on private.chat_uploads from public, anon, authenticated;

-- Single extension point: #19 replaces this to enable ('video','video/mp4').
create function private.chat_attachment_limit(p_kind text, p_mime text)
returns bigint language sql immutable set search_path = '' as $$
    select case when p_kind = 'photo' and p_mime in ('image/jpeg', 'image/png') then 2097152::bigint end;
$$;
create function private.chat_attachment_extension(p_mime text)
returns text language sql immutable set search_path = '' as $$
    select case p_mime when 'image/jpeg' then '.jpg' when 'image/png' then '.png' when 'video/mp4' then '.mp4' end;
$$;
revoke all on function private.chat_attachment_limit(text, text), private.chat_attachment_extension(text)
    from public, anon, authenticated;

create function private.is_chat_participant(p_conversation_id uuid, p_actor uuid)
returns boolean language sql stable security definer set search_path = '' as $$
    select exists (select 1 from public.conversations c join auth.users u on u.id = p_actor
        where c.id = p_conversation_id and p_actor in (c.client_id, c.professional_id)
        and u.email_confirmed_at is not null and u.deleted_at is null
        and (u.banned_until is null or u.banned_until <= now()));
$$;
revoke all on function private.is_chat_participant(uuid, uuid) from public, anon, authenticated;

create function public.request_chat_attachment(
    p_conversation_id uuid, p_request_id uuid, p_kind text, p_mime text, p_sha256 text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    actor uuid := auth.uid();
    upload private.chat_uploads;
    message public.chat_messages;
begin
    perform public.current_account();
    if not private.is_chat_participant(p_conversation_id, actor) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_request_id is null or p_sha256 is null or p_sha256 !~ '^[0-9a-f]{64}$' then
        raise exception using errcode = '22023', message = 'invalid_attachment';
    end if;
    if private.chat_attachment_limit(p_kind, p_mime) is null then
        raise exception using errcode = '22023', message = 'unsupported_attachment';
    end if;
    select * into upload from private.chat_uploads
        where conversation_id = p_conversation_id and author_id = actor and request_id = p_request_id;
    select * into message from public.chat_messages
        where conversation_id = p_conversation_id and author_id = actor and request_id = p_request_id;
    if (message.id is not null and upload.object_path is null)
        or (upload.object_path is not null and (upload.kind, upload.mime, upload.digest)
            is distinct from (p_kind, p_mime, p_sha256)) then
        raise exception using errcode = '22023', message = 'retry_conflict';
    end if;
    if upload.object_path is null then
        insert into private.chat_uploads(conversation_id, author_id, request_id, kind, mime, digest, object_path)
            values (p_conversation_id, actor, p_request_id, p_kind, p_mime, p_sha256,
                p_conversation_id::text || '/' || p_request_id::text || private.chat_attachment_extension(p_mime))
            returning * into upload;
    end if;
    return jsonb_build_object('object_path', upload.object_path,
        'status', case when message.id is null then 'uploading' else 'sent' end,
        'message', case when message.id is not null then to_jsonb(message) end);
end;
$$;

create function public.submit_chat_attachment(p_conversation_id uuid, p_request_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    actor uuid := auth.uid();
    upload private.chat_uploads;
    stored jsonb;
    size bigint;
    result public.chat_messages;
begin
    perform public.current_account();
    if not private.is_chat_participant(p_conversation_id, actor) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    select * into upload from private.chat_uploads
        where conversation_id = p_conversation_id and author_id = actor and request_id = p_request_id;
    if not found then
        raise exception using errcode = '22023', message = 'upload_not_found';
    end if;
    select * into result from public.chat_messages
        where conversation_id = p_conversation_id and author_id = actor and request_id = p_request_id;
    if found then return to_jsonb(result); end if;
    select o.metadata into stored from storage.objects o
        where o.bucket_id = 'chat-attachments' and o.name = upload.object_path;
    if stored is null or (stored->>'size') !~ '^[0-9]+$' then
        raise exception using errcode = '22023', message = 'upload_not_complete';
    end if;
    if stored->>'mimetype' is distinct from upload.mime then
        raise exception using errcode = '22023', message = 'unsupported_attachment';
    end if;
    size := (stored->>'size')::bigint;
    if size < 1 or size > private.chat_attachment_limit(upload.kind, upload.mime) then
        raise exception using errcode = '22023', message = 'attachment_too_large';
    end if;
    insert into public.chat_messages(conversation_id, author_id, request_id, body,
            attachment_kind, attachment_path, attachment_type, attachment_size)
        values (p_conversation_id, actor, p_request_id, '', upload.kind, upload.object_path, upload.mime, size)
        on conflict (conversation_id, author_id, request_id) do nothing;
    select * into result from public.chat_messages
        where conversation_id = p_conversation_id and author_id = actor and request_id = p_request_id;
    if result.attachment_path is distinct from upload.object_path then
        raise exception using errcode = '22023', message = 'retry_conflict';
    end if;
    return to_jsonb(result);
end;
$$;

create function public.can_write_chat_attachment(p_path text)
returns boolean language sql stable security definer set search_path = '' as $$
    select exists (select 1 from private.chat_uploads up
        where up.object_path = p_path and up.author_id = auth.uid()
        and private.is_chat_participant(up.conversation_id, auth.uid())
        and not exists (select 1 from public.chat_messages m where m.attachment_path = p_path));
$$;
create function public.can_read_chat_attachment(p_path text)
returns boolean language sql stable security definer set search_path = '' as $$
    select exists (select 1 from public.chat_messages m
        where m.attachment_path = p_path and private.is_chat_participant(m.conversation_id, auth.uid()));
$$;
revoke all on function public.request_chat_attachment(uuid, uuid, text, text, text),
    public.submit_chat_attachment(uuid, uuid), public.can_write_chat_attachment(text),
    public.can_read_chat_attachment(text) from public, anon;
grant execute on function public.request_chat_attachment(uuid, uuid, text, text, text),
    public.submit_chat_attachment(uuid, uuid), public.can_write_chat_attachment(text),
    public.can_read_chat_attachment(text) to authenticated;

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
    values ('chat-attachments', 'chat-attachments', false, 2097152, array['image/jpeg', 'image/png']);
create policy chat_attachment_insert on storage.objects for insert to authenticated
    with check (bucket_id = 'chat-attachments' and public.can_write_chat_attachment(name));
create policy chat_attachment_read on storage.objects for select to authenticated
    using (bucket_id = 'chat-attachments' and public.can_read_chat_attachment(name));
-- No update/delete policy: confirmed history and its files stay immutable.
