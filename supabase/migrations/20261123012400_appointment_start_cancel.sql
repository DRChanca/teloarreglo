-- «Comenzar trabajo» and cancellation before the start (#24).
-- A confirmed appointment becomes 'started' only by its professional; starting moves no money and authorises no
-- extra cost. Before the start either participant cancels: the reservation is released, everything still pending
-- of the simulated integrated payment is refunded without penalty, a pending change is closed and the other
-- participant is told. Direct and free appointments move no money. After the start there is no cancellation with
-- an automatic refund: that is an incident (#26). Both operations take the appointment row lock first, so a start
-- and a cancellation at the same time end in exactly one of them.

alter table public.appointments drop constraint appointments_stage_check,
    add constraint appointments_stage_check check (stage in ('pending', 'confirmed', 'started', 'cancelled'));
alter table public.appointments
    add column started_at timestamptz,
    add column cancelled_at timestamptz,
    add column cancelled_by uuid references auth.users(id),
    add constraint appointments_started_mark check (stage <> 'started' or started_at is not null),
    add constraint appointments_cancelled_mark check ((stage = 'cancelled') = (cancelled_at is not null and cancelled_by is not null));
-- Readable by both participants like the rest of the row except the exact place (#38).
grant select (started_at, cancelled_at, cancelled_by) on public.appointments to authenticated;

alter table public.appointment_events drop constraint appointment_events_kind_check,
    add constraint appointment_events_kind_check check (kind in ('requested', 'proposed', 'rejected', 'accepted',
        'change_proposed', 'change_accepted', 'change_rejected', 'address_correction_requested',
        'started', 'cancelled'));

-- The refund caused by a cancellation is marked, and there is at most one per appointment.
alter table public.payment_movements add column cancellation boolean not null default false,
    add constraint payment_movements_cancellation_check check (not cancellation or kind = 'refunded');
create unique index payment_movements_one_cancellation on public.payment_movements(appointment_id) where cancellation;

-- What both operations return: the marks, never the exact place.
create function private.appointment_marks(p_id uuid)
returns jsonb language sql stable security definer set search_path = ''
as $$
    select jsonb_build_object('id', a.id, 'stage', a.stage, 'version', a.version, 'started_at', a.started_at,
        'cancelled_at', a.cancelled_at, 'cancelled_by', a.cancelled_by,
        'refunded_cents', coalesce((select m.amount_cents from public.payment_movements m
            where m.appointment_id = a.id and m.cancellation), 0))
    from public.appointments a where a.id = p_id
$$;
revoke all on function private.appointment_marks(uuid) from public, anon, authenticated;

-- Only the professional of a confirmed appointment starts it. Starting again returns the same mark.
create function public.start_work(p_appointment_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or appt.professional_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if appt.stage = 'started' then
        return private.appointment_marks(appt.id);
    end if;
    if appt.stage <> 'confirmed' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    -- A pending change is not accepted by starting: it is closed and the conditions in force stay.
    update public.appointment_changes set status = 'superseded', decided_at = now()
      where appointment_id = appt.id and status = 'pending';
    update public.appointments set stage = 'started', started_at = now(), version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'started', actor, appt.client_id);
    return private.appointment_marks(appt.id);
end;
$$;
revoke all on function public.start_work(uuid) from public, anon;
grant execute on function public.start_work(uuid) to authenticated;

-- Either participant cancels a confirmed appointment that has not started. Cancelling again (by either) returns
-- the first cancellation without a second refund or notice.
create function public.cancel_appointment(p_appointment_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    payment public.appointment_payments;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or actor not in (appt.client_id, appt.professional_id) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    -- Same lock order as accept_proposal/confirm_proposal/accept_change: appointment row, then agenda.
    perform private.lock_agenda(appt.profile_id);
    if appt.stage = 'cancelled' then
        return private.appointment_marks(appt.id);
    end if;
    if appt.stage = 'started' then
        raise exception using errcode = 'PT409', message = 'already_started';
    end if;
    if appt.stage <> 'confirmed' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    delete from public.agenda_reservations r where r.appointment_id = appt.id;
    update public.appointment_changes set status = 'superseded', decided_at = now()
      where appointment_id = appt.id and status = 'pending';
    select * into payment from public.appointment_payments p where p.appointment_id = appt.id for update;
    -- Only integrated money still pending is returned; a direct payment was never handled by the app.
    if payment.mode = 'integrated' and payment.pending_cents > 0 then
        insert into public.payment_movements(appointment_id, kind, amount_cents, actor_id, cancellation)
          values (appt.id, 'refunded', payment.pending_cents, actor, true);
        update public.appointment_payments set refunded_cents = refunded_cents + pending_cents, pending_cents = 0
          where appointment_id = appt.id;
    end if;
    update public.appointments set stage = 'cancelled', cancelled_at = now(), cancelled_by = actor,
        version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'cancelled', actor, case when actor = appt.client_id then appt.professional_id else appt.client_id end);
    return private.appointment_marks(appt.id);
end;
$$;
revoke all on function public.cancel_appointment(uuid) from public, anon;
grant execute on function public.cancel_appointment(uuid) to authenticated;
