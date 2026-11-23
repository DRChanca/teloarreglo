-- «Realizado» by the professional and manual confirmation by the client (#25).
-- Marking done fixes the server instant from which the deadlines count (reminder at 24 h, silent close at 48 h) and
-- delivers nothing. Only the client confirms; the confirmation delivers everything still pending of the simulated
-- integrated payment, once, unless a blocking incident exists. Direct and free appointments complete without money.
-- private.complete_appointment is the one invariant shared with the clock (#27) and administration (#26/#31):
-- they must call it instead of writing their own balance logic.

alter table public.appointments drop constraint appointments_stage_check,
    add constraint appointments_stage_check check (stage in ('pending', 'confirmed', 'started', 'done', 'completed', 'cancelled'));
alter table public.appointments
    add column done_at timestamptz,
    add column reminder_due_at timestamptz,
    add column close_due_at timestamptz,
    add column completed_at timestamptz,
    add column completion text constraint appointments_completion_check check (completion in ('client_confirmed', 'no_answer')),
    add constraint appointments_done_mark check (stage not in ('done', 'completed')
        or (done_at is not null and reminder_due_at is not null and close_due_at is not null)),
    add constraint appointments_completed_mark check ((stage = 'completed') = (completed_at is not null and completion is not null));
-- Readable by both participants like the rest of the row except the exact place (#38).
grant select (done_at, reminder_due_at, close_due_at, completed_at, completion) on public.appointments to authenticated;

-- 'done' tells the client; 'completed' tells the other participant, or both when the clock closes (no actor).
alter table public.appointment_events alter column actor_id drop not null,
    drop constraint appointment_events_kind_check,
    add constraint appointment_events_kind_check check (kind in ('requested', 'proposed', 'rejected', 'accepted',
        'change_proposed', 'change_accepted', 'change_rejected', 'address_correction_requested',
        'started', 'cancelled', 'done', 'completed')),
    add constraint appointment_events_system_actor check (actor_id is not null or kind = 'completed');

-- The pending balance is delivered at most once, whoever delivers it (client, clock or administration).
create unique index payment_movements_one_delivery on public.payment_movements(appointment_id) where kind = 'delivered';

-- Deadlines counted from the «Realizado» mark. Presentation and product: 24 h and 48 h. The shortened test interval
-- (#27) must be isolated; there is no user parameter that changes them.
create function private.completion_deadlines(p_done_at timestamptz, out reminder_due_at timestamptz, out close_due_at timestamptz)
language sql immutable set search_path = ''
as $$ select p_done_at + interval '24 hours', p_done_at + interval '48 hours' $$;
revoke all on function private.completion_deadlines(timestamptz) from public, anon, authenticated;

-- Seam for incidents (#26): true while an incident blocks the delivery. #26 redefines it with create or replace.
create function private.delivery_blocked(p_appointment_id uuid)
returns boolean language sql stable security definer set search_path = ''
as $$ select false $$;
revoke all on function private.delivery_blocked(uuid) from public, anon, authenticated;

-- What mark_done, confirm_done and the shared invariant return: marks and money delivered, never the exact place.
create function private.completion_marks(p_id uuid)
returns jsonb language sql stable security definer set search_path = ''
as $$
    select jsonb_build_object('id', a.id, 'stage', a.stage, 'version', a.version, 'done_at', a.done_at,
        'reminder_due_at', a.reminder_due_at, 'close_due_at', a.close_due_at, 'completed_at', a.completed_at,
        'completion', a.completion,
        'delivered_cents', coalesce((select m.amount_cents from public.payment_movements m
            where m.appointment_id = a.id and m.kind = 'delivered'), 0))
    from public.appointments a where a.id = p_id
$$;
revoke all on function private.completion_marks(uuid) from public, anon, authenticated;

-- The one way an appointment marked done becomes completed. p_actor is the confirming client, or null for the
-- clock ('no_answer'). Takes the appointment row lock itself (callers may already hold it), then the payment row.
-- Completed already: returns the first result, never a second delivery.
create function private.complete_appointment(p_appointment_id uuid, p_actor uuid, p_completion text)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    appt public.appointments;
    payment public.appointment_payments;
begin
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or p_completion not in ('client_confirmed', 'no_answer')
       or (p_completion = 'client_confirmed') <> (p_actor is not null)
       or (p_actor is not null and p_actor <> appt.client_id) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if appt.stage = 'completed' then
        return private.completion_marks(appt.id);
    end if;
    if appt.stage <> 'done' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    if private.delivery_blocked(appt.id) then
        raise exception using errcode = 'PT409', message = 'incident_blocking';
    end if;
    if p_completion = 'no_answer' and now() < appt.close_due_at then
        raise exception using errcode = 'PT409', message = 'not_due';
    end if;
    select * into payment from public.appointment_payments p where p.appointment_id = appt.id for update;
    if payment.mode = 'integrated' and payment.pending_cents > 0 then
        insert into public.payment_movements(appointment_id, kind, amount_cents, actor_id)
          values (appt.id, 'delivered', payment.pending_cents, p_actor);
        update public.appointment_payments set delivered_cents = delivered_cents + pending_cents, pending_cents = 0
          where appointment_id = appt.id;
    end if;
    update public.appointments set stage = 'completed', completed_at = now(), completion = p_completion,
        version = version + 1 where id = appt.id;
    if p_actor is null then
        insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
          values (appt.id, 'completed', null, appt.client_id), (appt.id, 'completed', null, appt.professional_id);
    else
        insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
          values (appt.id, 'completed', p_actor, appt.professional_id);
    end if;
    return private.completion_marks(appt.id);
end;
$$;
revoke all on function private.complete_appointment(uuid, uuid, text) from public, anon, authenticated;

-- Only the professional of a started job marks it done. Marking again returns the same mark.
create function public.mark_done(p_appointment_id uuid)
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
    select * into deadlines from private.completion_deadlines(now());
    update public.appointments set stage = 'done', done_at = now(), reminder_due_at = deadlines.reminder_due_at,
        close_due_at = deadlines.close_due_at, version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'done', actor, appt.client_id);
    return private.completion_marks(appt.id);
end;
$$;
revoke all on function public.mark_done(uuid) from public, anon;
grant execute on function public.mark_done(uuid) to authenticated;

-- Only the client confirms a job marked done. Confirming again returns the first result.
create function public.confirm_done(p_appointment_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or appt.client_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if appt.stage = 'completed' then
        return private.completion_marks(appt.id);
    end if;
    if appt.stage <> 'done' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    return private.complete_appointment(appt.id, actor, 'client_confirmed');
end;
$$;
revoke all on function public.confirm_done(uuid) from public, anon;
grant execute on function public.confirm_done(uuid) to authenticated;
