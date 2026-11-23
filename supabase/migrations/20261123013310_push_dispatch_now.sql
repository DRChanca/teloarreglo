-- Immediate push dispatch (#32 follow-up). The one-minute Supabase Cron job stays as the fallback; on top of it, each
-- statement that queues deliveries asks deliver-push to run now, so a chat message reaches the other phone in
-- seconds. The request is queued by pg_net and only leaves after the domain transaction commits.
--
-- Configuration lives in Supabase Vault, per project, never in Git:
--   * push_dispatch_url    -> https://<ref>.supabase.co/functions/v1/deliver-push
--   * push_dispatch_secret -> the same PUSH_DISPATCH_SECRET the cron job uses (already created for #32)
-- Without push_dispatch_url (local and test databases) nothing is called. Any failure (pg_net missing, Vault
-- unavailable) is swallowed: waking the dispatcher must never revert a message, an appointment or a payment.
create function private.push_dispatch_now() returns trigger
language plpgsql security definer set search_path = ''
as $$
declare
    target text;
    secret text;
begin
    if not exists (select 1 from added) then
        return null;
    end if;
    begin
        select s.decrypted_secret into target from vault.decrypted_secrets s where s.name = 'push_dispatch_url';
        select s.decrypted_secret into secret from vault.decrypted_secrets s where s.name = 'push_dispatch_secret';
        if target is not null and secret is not null then
            perform net.http_post(url := target, body := '{}'::jsonb,
                headers := jsonb_build_object('Authorization', 'Bearer ' || secret, 'Content-Type', 'application/json'));
        end if;
    exception when others then
        null;
    end;
    return null;
end;
$$;
revoke all on function private.push_dispatch_now() from public, anon, authenticated;
create trigger push_deliveries_dispatch_now after insert on private.push_deliveries
    referencing new table as added for each statement execute function private.push_dispatch_now();
