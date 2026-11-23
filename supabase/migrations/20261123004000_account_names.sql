-- The person's own name (#40), not the business name of a professional profile.
-- Single source for client_name in conversation_inbox (#39). Never the email.
create table public.account_names (
    user_id uuid primary key references auth.users(id) on delete cascade,
    name text not null check (name = btrim(name) and char_length(name) between 1 and 80),
    updated_at timestamptz not null default now()
);
alter table public.account_names enable row level security;
revoke all on public.account_names from public, anon, authenticated;
grant select on public.account_names to authenticated;
create policy own_account_name on public.account_names for select to authenticated using (user_id = auth.uid());

-- Name declared at signup (full_name) or given by Google (full_name or name); null if unusable.
-- A provider name longer than 80 characters is cut, not rejected, so Google never forces a question.
create function private.metadata_account_name(p_metadata jsonb)
returns text language sql immutable set search_path = ''
as $$
    select nullif(btrim(left(btrim(coalesce(
        nullif(btrim(p_metadata->>'full_name'), ''), p_metadata->>'name')), 80)), '')
$$;

-- Fills the name once from Auth metadata. A name already saved (by the person) is never overwritten.
create function private.seed_account_name()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare
    seeded text := private.metadata_account_name(new.raw_user_meta_data);
begin
    if seeded is not null then
        insert into public.account_names(user_id, name) values (new.id, seeded)
        on conflict (user_id) do nothing;
    end if;
    return new;
end;
$$;
revoke all on function private.seed_account_name() from public, anon, authenticated;
create trigger seed_account_name after insert or update of raw_user_meta_data on auth.users
    for each row execute function private.seed_account_name();

-- Existing accounts (Google, or #39 test data) keep the name they already declared.
insert into public.account_names(user_id, name)
select u.id, private.metadata_account_name(u.raw_user_meta_data) from auth.users u
where private.metadata_account_name(u.raw_user_meta_data) is not null
on conflict (user_id) do nothing;

create function public.my_account_name()
returns text language plpgsql stable security definer set search_path = ''
as $$
begin
    perform public.current_account();
    return (select n.name from public.account_names n where n.user_id = auth.uid());
end;
$$;
revoke all on function public.my_account_name() from public, anon;
grant execute on function public.my_account_name() to authenticated;

-- Creates or replaces the caller's name: 1–80 characters after trimming. Repeating is harmless.
create function public.set_account_name(p_name text)
returns text language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    cleaned text := btrim(coalesce(p_name, ''));
begin
    perform public.current_account();
    if char_length(cleaned) not between 1 and 80 then
        raise exception using errcode = '22023', message = 'invalid_account_name';
    end if;
    insert into public.account_names(user_id, name, updated_at) values (actor, cleaned, now())
    on conflict (user_id) do update set name = excluded.name, updated_at = excluded.updated_at;
    return cleaned;
end;
$$;
revoke all on function public.set_account_name(text) from public, anon;
grant execute on function public.set_account_name(text) to authenticated;

-- Same inbox as #39, with client_name read from account_names.
create or replace function public.conversation_inbox()
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
            'client_name', n.name,
            'unread_count', (select count(*) from public.chat_messages m
                where m.conversation_id = c.id and m.author_id <> actor
                  and m.created_at > coalesce(r.last_read_at, '-infinity')),
            'last_message_at', (select max(m.created_at) from public.chat_messages m
                where m.conversation_id = c.id)
        ) order by coalesce((select max(m.created_at) from public.chat_messages m
                where m.conversation_id = c.id), c.created_at) desc, c.id)
        from public.conversations c
        left join public.account_names n on n.user_id = c.client_id
        left join public.conversation_reads r on r.conversation_id = c.id and r.user_id = actor
        where actor in (c.client_id, c.professional_id)
    ), '[]'::jsonb);
end;
$$;
revoke all on function public.conversation_inbox() from public, anon;
grant execute on function public.conversation_inbox() to authenticated;
