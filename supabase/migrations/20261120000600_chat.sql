-- Contact does not require an appointment and never carries an address.
create table public.conversations (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references private.professional_profiles(id),
    client_id uuid not null references auth.users(id),
    professional_id uuid not null references auth.users(id),
    name text not null,
    created_at timestamptz not null default now(),
    unique (profile_id, client_id),
    check (client_id <> professional_id)
);
alter table public.conversations enable row level security;
revoke all on public.conversations from public, anon, authenticated;
grant select on public.conversations to authenticated;
create policy participants_read on public.conversations for select to authenticated
    using (auth.uid() in (client_id, professional_id));

create function public.open_conversation(p_profile_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    owner uuid;
    title text;
    result public.conversations;
begin
    perform public.current_account();
    select p.owner_id, a.name into owner, title
      from private.professional_profiles p join public.approved_profiles a on a.profile_id = p.id
      where p.id = p_profile_id;
    if owner is null or owner = actor then
        raise exception using errcode = '22023', message = 'contact_unavailable';
    end if;
    insert into public.conversations(profile_id, client_id, professional_id, name)
      values (p_profile_id, actor, owner, title)
      on conflict (profile_id, client_id) do nothing;
    select * into result from public.conversations
      where profile_id = p_profile_id and client_id = actor;
    return to_jsonb(result);
end;
$$;
revoke all on function public.open_conversation(uuid) from public, anon;
grant execute on function public.open_conversation(uuid) to authenticated;
