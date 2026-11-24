-- Confirmation of proposals with a cost: simulated integrated payment of the agreed total, or an
-- express agreement to pay the professional directly. No real money, provider or bank data exists.
-- Ticket: Confirmar con pago simulado o acuerdo de pago directo (#22).
-- Reservation rules are shared with the free acceptance of #21 (private.reserve_proposal).

-- One record per confirmed appointment with a cost; amounts in euro cents.
-- paid = pending + refunded + delivered always holds; direct payment moves no integrated money.
create table public.appointment_payments (
    appointment_id uuid primary key references public.appointments(id),
    proposal_id uuid not null unique references public.appointment_proposals(id),
    client_id uuid not null references auth.users(id),
    professional_id uuid not null references auth.users(id),
    request_id uuid not null,
    mode text not null constraint appointment_payments_mode_check check (mode in ('integrated', 'direct')),
    agreed_cents integer not null check (agreed_cents > 0),
    paid_cents integer not null default 0 check (paid_cents >= 0),
    pending_cents integer not null default 0 check (pending_cents >= 0),
    refunded_cents integer not null default 0 check (refunded_cents >= 0),
    delivered_cents integer not null default 0 check (delivered_cents >= 0),
    simulated boolean not null default true check (simulated),
    created_at timestamptz not null default clock_timestamp(),
    unique (client_id, request_id),
    constraint appointment_payments_balance check (paid_cents = pending_cents + refunded_cents + delivered_cents),
    constraint appointment_payments_direct_check check (mode = 'integrated'
        or (paid_cents = 0 and pending_cents = 0 and refunded_cents = 0 and delivered_cents = 0))
);

-- Append-only history of simulated integrated money. Refunds and deliveries belong to later tickets.
create table public.payment_movements (
    id uuid primary key default gen_random_uuid(),
    appointment_id uuid not null references public.appointment_payments(appointment_id),
    kind text not null constraint payment_movements_kind_check check (kind in ('paid', 'refunded', 'delivered')),
    amount_cents integer not null check (amount_cents > 0),
    actor_id uuid references auth.users(id),
    created_at timestamptz not null default clock_timestamp()
);
create index payment_movements_appointment on public.payment_movements(appointment_id, created_at);

alter table public.appointment_payments enable row level security;
alter table public.payment_movements enable row level security;
revoke all on public.appointment_payments, public.payment_movements from public, anon, authenticated;
grant select on public.appointment_payments, public.payment_movements to authenticated;
create policy participants_read on public.appointment_payments for select to authenticated
    using (client_id = auth.uid() or professional_id = auth.uid());
create policy participants_read on public.payment_movements for select to authenticated
    using (exists (select 1 from public.appointment_payments p where p.appointment_id = payment_movements.appointment_id
        and (p.client_id = auth.uid() or p.professional_id = auth.uid())));

-- Checks the agenda and reserves the proposal's interval, then marks it accepted and the appointment
-- confirmed. The caller holds the appointment row lock and private.lock_agenda; any error rolls back all.
create function private.reserve_proposal(p_appointment public.appointments, p_proposal public.appointment_proposals)
returns public.appointment_proposals language plpgsql security definer set search_path = ''
as $$
declare
    local_start timestamp := p_proposal.start_at at time zone 'Europe/Madrid';
    local_end timestamp := p_proposal.end_at at time zone 'Europe/Madrid';
    result public.appointment_proposals;
begin
    if p_proposal.start_at <= now() then
        raise exception using errcode = 'PT409', message = 'proposal_expired';
    end if;
    if not exists (select 1 from public.availability_windows w
        where w.profile_id = p_appointment.profile_id and w.weekday = extract(isodow from local_start)
          and local_start::time >= w.start_time
          and local_end::date = local_start::date and local_end::time <= w.end_time) then
        raise exception using errcode = 'PT409', message = 'slot_unavailable';
    end if;
    begin
        insert into public.agenda_reservations(profile_id, professional_id, kind, during, appointment_id, proposal_id)
          values (p_appointment.profile_id, p_appointment.professional_id, 'appointment',
            tstzrange(p_proposal.start_at, p_proposal.end_at, '[)'), p_appointment.id, p_proposal.id);
    exception when exclusion_violation then
        raise exception using errcode = 'PT409', message = 'slot_unavailable';
    end;
    update public.appointment_proposals set status = 'accepted', decided_at = now()
      where id = p_proposal.id returning * into result;
    update public.appointments set stage = 'confirmed', version = version + 1 where id = p_appointment.id;
    insert into public.appointment_events(appointment_id, proposal_id, kind, actor_id, recipient_id)
      values (p_appointment.id, p_proposal.id, 'accepted', p_appointment.client_id, p_appointment.professional_id);
    return result;
end;
$$;
revoke all on function private.reserve_proposal(public.appointments, public.appointment_proposals)
    from public, anon, authenticated;

-- Same behaviour as #21; the reservation now lives in private.reserve_proposal.
create or replace function public.accept_proposal(p_proposal_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    result public.appointment_proposals;
begin
    perform public.current_account();
    select a.* into appt from public.appointments a
      join public.appointment_proposals p on p.appointment_id = a.id
      where p.id = p_proposal_id for update of a;
    if appt.id is null or appt.client_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    -- Always taken after the appointment row lock and never the other way round, so no lock cycle.
    perform private.lock_agenda(appt.profile_id);
    select * into result from public.appointment_proposals p where p.id = p_proposal_id;
    if result.status = 'accepted' then
        return to_jsonb(result);
    end if;
    if result.status <> 'pending' then
        raise exception using errcode = 'PT409', message = 'stale_version';
    end if;
    if appt.stage <> 'pending' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    if result.amount_cents <> 0 or coalesce(result.visit_fee_cents, 0) <> 0 then
        raise exception using errcode = '22023', message = 'payment_required';
    end if;
    return to_jsonb(private.reserve_proposal(appt, result));
end;
$$;

-- The client confirms the current proposal with a cost, choosing the payment mode. Acceptance,
-- reservation and the simulated payment are one transaction: a conflict charges nothing.
-- The amount paid up front is the agreed price, or the visit fee when only that was proposed.
create function public.confirm_proposal(p_proposal_id uuid, p_request_id uuid, p_mode text)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    proposal public.appointment_proposals;
    payment public.appointment_payments;
    total integer;
begin
    perform public.current_account();
    if p_request_id is null or p_mode is null or p_mode not in ('integrated', 'direct') then
        raise exception using errcode = '22023', message = 'invalid_payment';
    end if;
    select a.* into appt from public.appointments a
      join public.appointment_proposals p on p.appointment_id = a.id
      where p.id = p_proposal_id for update of a;
    if appt.id is null or appt.client_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    perform private.lock_agenda(appt.profile_id);
    -- A key identifies one intention: same proposal and mode. Checked under the locks, so
    -- simultaneous retries of one key see the first result.
    select * into payment from public.appointment_payments p where p.client_id = actor and p.request_id = p_request_id;
    if payment.appointment_id is not null then
        if payment.proposal_id <> p_proposal_id or payment.mode <> p_mode then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return to_jsonb(payment);
    end if;
    select * into proposal from public.appointment_proposals p where p.id = p_proposal_id;
    if proposal.status = 'accepted' then
        -- A lost answer retried under a new key recovers the same result, never a second payment.
        select * into payment from public.appointment_payments p where p.proposal_id = p_proposal_id;
        if payment.appointment_id is not null and payment.mode = p_mode then
            return to_jsonb(payment);
        end if;
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    if proposal.status <> 'pending' then
        raise exception using errcode = 'PT409', message = 'stale_version';
    end if;
    if appt.stage <> 'pending' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    total := greatest(proposal.amount_cents, coalesce(proposal.visit_fee_cents, 0));
    if total = 0 then
        raise exception using errcode = '22023', message = 'payment_not_required';
    end if;
    perform private.reserve_proposal(appt, proposal);
    begin
        insert into public.appointment_payments(appointment_id, proposal_id, client_id, professional_id, request_id,
            mode, agreed_cents, paid_cents, pending_cents)
          values (appt.id, proposal.id, actor, appt.professional_id, p_request_id, p_mode, total,
            case when p_mode = 'integrated' then total else 0 end,
            case when p_mode = 'integrated' then total else 0 end)
          returning * into payment;
    exception when unique_violation then
        -- The key was already used by this client for another appointment.
        raise exception using errcode = '22023', message = 'retry_conflict';
    end;
    if p_mode = 'integrated' then
        insert into public.payment_movements(appointment_id, kind, amount_cents, actor_id)
          values (appt.id, 'paid', total, actor);
    end if;
    return to_jsonb(payment);
end;
$$;
revoke all on function public.confirm_proposal(uuid, uuid, text) from public, anon;
grant execute on function public.confirm_proposal(uuid, uuid, text) to authenticated;
