-- Server clock for jobs marked «Realizado» (#27).
-- Supabase Cron calls private.run_completion_clock() every minute, so the reminder and the silent close happen with
-- both apps closed and never depend on a phone's clock. The schedule is a manual step per project (see
-- docs/citas-contrato.md, #27); this migration only adds what the job calls.
--   * Reminder: once, to the client, when reminder_due_at has passed, the job is still 'done' and no incident blocks it.
--   * Silent close: when close_due_at has passed and no incident blocks it, through private.complete_appointment
--     (#25), the one invariant that delivers the pending integrated balance at most once.
-- Rows are taken with `for update skip locked`: a confirmation or incident in progress holds the appointment lock and
-- the clock retries it on the next run. Every run looks at everything already due, so a late or interrupted run
-- recovers what it missed without duplicates.

alter table public.appointments add column reminded_at timestamptz;

-- 'reminder' is added to whatever kinds the earlier migrations allow, so parallel producers keep theirs.
do $$
declare
    definition text := (select pg_get_constraintdef(c.oid) from pg_constraint c
        where c.conrelid = 'public.appointment_events'::regclass and c.conname = 'appointment_events_kind_check');
begin
    if definition not like '%ARRAY[%' then
        raise exception 'unexpected appointment_events_kind_check: %', definition;
    end if;
    if definition not like '%''reminder''%' then
        execute 'alter table public.appointment_events drop constraint appointment_events_kind_check, add constraint '
            || 'appointment_events_kind_check ' || replace(definition, 'ARRAY[', 'ARRAY[''reminder''::text, ');
    end if;
end;
$$;
-- The clock is the only actor without an account: it closes ('completed') and reminds ('reminder').
alter table public.appointment_events drop constraint appointment_events_system_actor,
    add constraint appointment_events_system_actor check (actor_id is not null or kind in ('completed', 'reminder'));
create unique index appointment_events_one_reminder on public.appointment_events(appointment_id) where kind = 'reminder';

-- Isolated trial of the real clock with a short interval (#27), without a second hosted project and without shortening
-- the deadlines of the shared database: only appointments whose client AND professional are both listed here get a
-- 2-minute reminder and a 5-minute close. Rows are added and removed by David with trusted SQL for disposable test
-- accounts; there is no app parameter, button or RPC that changes the deadlines. Presentation and product: 24 h / 48 h.
create table private.completion_trial_accounts (
    user_id uuid primary key references auth.users(id) on delete cascade,
    added_at timestamptz not null default now()
);
revoke all on private.completion_trial_accounts from public, anon, authenticated;

create function private.appointment_completion_deadlines(p_appointment_id uuid, p_done_at timestamptz,
    out reminder_due_at timestamptz, out close_due_at timestamptz)
language sql stable security definer set search_path = ''
as $$
    select case when trial then p_done_at + interval '2 minutes' else d.reminder_due_at end,
           case when trial then p_done_at + interval '5 minutes' else d.close_due_at end
      from private.completion_deadlines(p_done_at) d,
           (select exists (select 1 from private.completion_trial_accounts t where t.user_id = a.client_id)
               and exists (select 1 from private.completion_trial_accounts t where t.user_id = a.professional_id) as trial
              from public.appointments a where a.id = p_appointment_id) x
$$;
revoke all on function private.appointment_completion_deadlines(uuid, timestamptz) from public, anon, authenticated;

-- Same as #25 except that the deadlines come from the appointment (trial accounts or the default 24 h / 48 h).
create or replace function public.mark_done(p_appointment_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    deadlines record;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or appt.professional_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if appt.stage in ('done', 'completed') then
        return private.completion_marks(appt.id);
    end if;
    if appt.stage <> 'started' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    -- The accepted proposal holds the terms: a change still pending is closed without being applied.
    update public.appointment_changes set status = 'superseded', decided_at = now()
      where appointment_id = appt.id and status = 'pending';
    select * into deadlines from private.appointment_completion_deadlines(appt.id, now());
    update public.appointments set stage = 'done', done_at = now(), reminder_due_at = deadlines.reminder_due_at,
        close_due_at = deadlines.close_due_at, version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'done', actor, appt.client_id);
    return private.completion_marks(appt.id);
end;
$$;
revoke all on function public.mark_done(uuid) from public, anon;
grant execute on function public.mark_done(uuid) to authenticated;

-- What the clock did, for the server-side evidence of a real run. No names, addresses or amounts.
create table private.completion_clock_log (
    id bigint generated always as identity primary key,
    ran_at timestamptz not null default clock_timestamp(),
    appointment_id uuid not null,
    action text not null constraint completion_clock_log_action_check check (action in ('reminded', 'closed', 'failed')),
    detail text
);
revoke all on private.completion_clock_log from public, anon, authenticated;

create function private.run_completion_clock(p_limit integer default 200)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    appt record;
    reminded integer := 0;
    closed integer := 0;
    failed integer := 0;
begin
    -- Close first: after an interruption a job overdue for both deadlines is closed, not reminded late.
    for appt in select a.id from public.appointments a
        where a.stage = 'done' and a.close_due_at <= now() and not private.delivery_blocked(a.id)
        order by a.close_due_at limit p_limit for update skip locked loop
        begin
            perform private.complete_appointment(appt.id, null, 'no_answer');
            insert into private.completion_clock_log(appointment_id, action) values (appt.id, 'closed');
            closed := closed + 1;
        exception when others then
            -- One failing job neither stops the others nor leaves half a close: its subtransaction is undone.
            insert into private.completion_clock_log(appointment_id, action, detail)
              values (appt.id, 'failed', sqlstate || ' ' || sqlerrm);
            failed := failed + 1;
        end;
    end loop;
    for appt in select a.id, a.client_id from public.appointments a
        where a.stage = 'done' and a.reminded_at is null and a.reminder_due_at <= now() and a.close_due_at > now()
          and not private.delivery_blocked(a.id)
        order by a.reminder_due_at limit p_limit for update skip locked loop
        insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
          values (appt.id, 'reminder', null, appt.client_id);
        update public.appointments set reminded_at = now() where id = appt.id;
        insert into private.completion_clock_log(appointment_id, action) values (appt.id, 'reminded');
        reminded := reminded + 1;
    end loop;
    -- The log is evidence of recent runs, not history: a month is enough.
    delete from private.completion_clock_log where ran_at < now() - interval '30 days';
    return jsonb_build_object('reminded', reminded, 'closed', closed, 'failed', failed,
        'blocked', (select count(*)::integer from public.appointments a
            where a.stage = 'done' and a.close_due_at <= now() and private.delivery_blocked(a.id)));
end;
$$;
revoke all on function private.run_completion_clock(integer) from public, anon, authenticated;
