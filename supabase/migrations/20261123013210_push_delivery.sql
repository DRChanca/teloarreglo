-- Push transport for persisted notices (#32). FCM is only a transport: the notice in private.notices is the
-- source of truth and is written in the same transaction as the domain operation; push delivery happens later,
-- outside that transaction, so a missing permission, a bad token or an FCM outage never reverts an appointment
-- or a payment and never loses the notice.
--
-- * private.push_devices: one row per app installation, owned by the account signed in on it. Re-registering an
--   installation moves it to the new account; signing out deletes it. A token lives on one installation only.
-- * private.push_deliveries: outbox, one row per (notice, installation), created when the notice is inserted.
--   The dispatcher (Edge Function deliver-push, service_role only) claims rows with a lease, sends them and
--   reports the outcome. Retries reuse the same row, so the domain operation is never repeated; at most one
--   'sent' per delivery. A claim re-checks that the device still belongs to the recipient and is enabled.

create table private.push_devices (
    installation_id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    token text not null unique check (length(token) between 1 and 4096),
    enabled boolean not null,
    invalid_at timestamptz,
    updated_at timestamptz not null default now()
);
create index push_devices_user on private.push_devices(user_id);
alter table private.push_devices enable row level security;
revoke all on private.push_devices from public, anon, authenticated;

create table private.push_deliveries (
    id uuid primary key default gen_random_uuid(),
    notice_id uuid not null references private.notices(id) on delete cascade,
    installation_id uuid not null,
    recipient_id uuid not null references auth.users(id) on delete cascade,
    status text not null default 'pending'
        check (status in ('pending', 'sending', 'sent', 'failed', 'dropped', 'expired')),
    attempts integer not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null default now(),
    last_error text check (length(last_error) <= 500),
    created_at timestamptz not null default now(),
    sent_at timestamptz,
    unique (notice_id, installation_id)
);
create index push_deliveries_due on private.push_deliveries(next_attempt_at) where status in ('pending', 'sending');
alter table private.push_deliveries enable row level security;
revoke all on private.push_deliveries from public, anon, authenticated;

-- Fan-out when a notice is persisted. Notices inserted already read (history) are not pushed.
create function private.push_fan_out() returns trigger
language plpgsql security definer set search_path = ''
as $$
begin
    if new.read_at is null then
        insert into private.push_deliveries(notice_id, installation_id, recipient_id)
        select new.id, d.installation_id, new.recipient_id from private.push_devices d
         where d.user_id = new.recipient_id and d.enabled and d.invalid_at is null
        on conflict do nothing;
    end if;
    return null;
end;
$$;
revoke all on function private.push_fan_out() from public, anon, authenticated;
create trigger notices_push_fan_out after insert on private.notices
    for each row execute function private.push_fan_out();

-- Registers or refreshes this installation for the signed-in account. p_enabled = false keeps the row but stops
-- pushes (Android permission denied or turned off in the app).
create function public.register_push_device(p_installation_id uuid, p_token text, p_enabled boolean)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare actor uuid := auth.uid();
begin
    perform public.current_account();
    if p_installation_id is null or p_enabled is null or p_token is null or length(btrim(p_token)) = 0
        or length(p_token) > 4096 then
        raise exception using errcode = '22023', message = 'invalid_token';
    end if;
    delete from private.push_devices where token = p_token and installation_id <> p_installation_id;
    insert into private.push_devices(installation_id, user_id, token, enabled)
        values (p_installation_id, actor, p_token, p_enabled)
    on conflict (installation_id) do update set user_id = actor, token = excluded.token,
        enabled = excluded.enabled, invalid_at = null, updated_at = now();
    return jsonb_build_object('installation_id', p_installation_id, 'enabled', p_enabled);
end;
$$;

-- Called before signing out. Only the account currently owning the installation can remove it.
create function public.unregister_push_device(p_installation_id uuid)
returns void language plpgsql security definer set search_path = ''
as $$
begin
    perform public.current_account();
    delete from private.push_devices where installation_id = p_installation_id and user_id = auth.uid();
end;
$$;
revoke all on function public.register_push_device(uuid, text, boolean), public.unregister_push_device(uuid)
    from public, anon;
grant execute on function public.register_push_device(uuid, text, boolean), public.unregister_push_device(uuid)
    to authenticated;

-- Dispatcher only. Expires stale rows, drops rows whose device changed hands or was disabled, and leases due
-- rows for two minutes (a crashed dispatcher's rows become due again). The result carries only identifiers and
-- the notice kind/event: never message bodies, reasons, addresses or coordinates.
create function public.claim_push_deliveries(p_limit integer default 50)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare picked uuid[];
begin
    update private.push_deliveries set status = 'expired'
     where status in ('pending', 'sending') and created_at < now() - interval '1 day';
    update private.push_deliveries x set status = 'dropped'
     where x.status in ('pending', 'sending') and not exists (
           select 1 from private.push_devices d where d.installation_id = x.installation_id
              and d.user_id = x.recipient_id and d.enabled and d.invalid_at is null);
    select array_agg(id) into picked from (
        select x.id from private.push_deliveries x
         where x.status in ('pending', 'sending') and x.next_attempt_at <= now()
         order by x.next_attempt_at, x.created_at limit greatest(1, least(coalesce(p_limit, 50), 500))
         for update skip locked) due;
    update private.push_deliveries set status = 'sending', attempts = attempts + 1,
        next_attempt_at = now() + interval '2 minutes' where id = any(picked);
    return coalesce((select jsonb_agg(jsonb_build_object(
            'delivery_id', x.id, 'token', d.token, 'recipient_id', x.recipient_id, 'notice_id', n.id,
            'kind', n.kind, 'event', coalesce(n.payload->>'event', n.payload->>'status'),
            'conversation_id', n.payload->>'conversation_id', 'appointment_id', n.payload->>'appointment_id',
            'created_at', n.created_at) order by x.next_attempt_at)
        from private.push_deliveries x
        join private.push_devices d on d.installation_id = x.installation_id
        join private.notices n on n.id = x.notice_id
        where x.id = any(picked)), '[]'::jsonb);
end;
$$;

-- Outcome of one send. Only an in-flight row changes, so late or repeated reports are ignored.
--   sent          -> done for good;
--   retry         -> transient failure (network, 429, 5xx): exponential backoff, given up after 5 attempts;
--   invalid_token -> FCM no longer knows the token: the device is disabled until the app registers again;
--   failed        -> permanent failure for this message.
create function public.report_push_delivery(p_delivery_id uuid, p_outcome text, p_error text default null)
returns void language plpgsql security definer set search_path = ''
as $$
declare delivery private.push_deliveries;
begin
    if p_outcome is null or p_outcome not in ('sent', 'retry', 'invalid_token', 'failed') then
        raise exception using errcode = '22023', message = 'invalid_outcome';
    end if;
    select * into delivery from private.push_deliveries where id = p_delivery_id and status = 'sending' for update;
    if delivery.id is null then
        return;
    end if;
    if p_outcome = 'sent' then
        update private.push_deliveries set status = 'sent', sent_at = now(), last_error = null where id = delivery.id;
    elsif p_outcome = 'retry' and delivery.attempts < 5 then
        update private.push_deliveries set status = 'pending', last_error = left(p_error, 500),
            next_attempt_at = now() + interval '1 minute' * power(2, delivery.attempts) where id = delivery.id;
    else
        update private.push_deliveries set status = 'failed', last_error = left(p_error, 500) where id = delivery.id;
    end if;
    if p_outcome = 'invalid_token' then
        update private.push_devices set enabled = false, invalid_at = now() where installation_id = delivery.installation_id;
        update private.push_deliveries set status = 'dropped'
         where installation_id = delivery.installation_id and status = 'pending';
    end if;
end;
$$;
revoke all on function public.claim_push_deliveries(integer), public.report_push_delivery(uuid, text, text)
    from public, anon, authenticated;
grant execute on function public.claim_push_deliveries(integer), public.report_push_delivery(uuid, text, text)
    to service_role;
