-- Private contact phone of each account (#74, spec #71). Asked after signing in, after the name and
-- the home. Same protection as the home (#37): only its owner reads it (RLS) and writes it (definer
-- RPC). No search, chat, request, catalogue or public view reads this table; only the exact location
-- of a confirmed appointment (#79) will hand it to the professional.
create table public.contact_phones (
    user_id uuid primary key references auth.users(id) on delete cascade,
    -- E.164: '+', country code not starting with 0, 8 to 15 digits in total.
    phone text not null check (phone ~ '^\+[1-9][0-9]{7,14}$'),
    updated_at timestamptz not null default now()
);
alter table public.contact_phones enable row level security;
revoke all on public.contact_phones from public, anon, authenticated;
grant select on public.contact_phones to authenticated;
create policy contact_phones_owner_read on public.contact_phones for select to authenticated
    using (user_id = (select auth.uid()));

-- The phone in E.164, or null if it cannot be accepted. Spaces, dashes, dots and parentheses are
-- ignored. A Spanish number of 9 digits (6, 7, 8 or 9 first) gets +34; anything else needs '+' and
-- its country code. Same rule as normalizeContactPhone in the app (identity/ContactPhones.kt).
create function private.normalize_contact_phone(p_phone text)
returns text language sql immutable set search_path = ''
as $$
    select case
        when compact ~ '^[6-9][0-9]{8}$' then '+34' || compact
        when compact ~ '^\+[1-9][0-9]{7,14}$' then compact
    end
    from (select regexp_replace(coalesce(p_phone, ''), '[[:space:]().-]', '', 'g') as compact) c
$$;
revoke all on function private.normalize_contact_phone(text) from public, anon, authenticated;

-- The signed-in account's contact phone; null when it has none (the app then asks for it).
create function public.my_contact_phone()
returns text language plpgsql stable security definer set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    return (select p.phone from public.contact_phones p where p.user_id = auth.uid());
end;
$$;
revoke all on function public.my_contact_phone() from public, anon;
grant execute on function public.my_contact_phone() to authenticated;

-- Creates or replaces the caller's contact phone and returns it in E.164.
create function public.set_contact_phone(p_phone text)
returns text language plpgsql volatile security definer set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_phone text := private.normalize_contact_phone(p_phone);
begin
    if v_user is null then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    if v_phone is null then
        raise exception using errcode = '22023', message = 'invalid_contact_phone';
    end if;
    insert into public.contact_phones as p (user_id, phone, updated_at) values (v_user, v_phone, now())
    on conflict (user_id) do update set phone = excluded.phone, updated_at = now();
    return v_phone;
end;
$$;
revoke all on function public.set_contact_phone(text) from public, anon;
grant execute on function public.set_contact_phone(text) to authenticated;
