-- Identity is Auth's UUID. The private allowlist is managed only by trusted SQL.
-- No client-facing API accepts a role or an arbitrary account identifier.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table private.administrators (
    user_id uuid primary key references auth.users(id) on delete cascade
);
alter table private.administrators enable row level security;
revoke all on private.administrators from public, anon, authenticated;

create function public.current_account()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    account_email text;
begin
    if actor is null then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    select u.email into account_email
    from auth.users u
    where u.id = actor and u.email_confirmed_at is not null and u.deleted_at is null
      and (u.banned_until is null or u.banned_until <= now());
    if not found then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    return jsonb_build_object(
        'user_id', actor,
        'email', account_email,
        'is_administrator', exists (
            select 1 from private.administrators a where a.user_id = actor
        )
    );
end;
$$;

revoke all on function public.current_account() from public, anon;
grant execute on function public.current_account() to authenticated;
