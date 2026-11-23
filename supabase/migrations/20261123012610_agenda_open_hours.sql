-- Agenda without configured hours and early slot checks (David, 2026-11-23; spec #11, fix to #21–#23).
-- A client paid for a proposal and got slot_unavailable because the professional had never saved any
-- availability: every window check failed. Now:
--  * A profile with no availability window at all is not restricted by hours; only its bookings and
--    blocks (agenda_reservations) apply. Once one window exists, appointments must fit a window.
--  * The check runs already when a proposal (#20) or a change with another interval (#23) is sent, so
--    the author learns it at once; confirmation keeps the check (and the exclusion constraint) for races.
--  * Leaving the hours is reported as PT409 outside_hours; overlapping a booking stays slot_unavailable.

-- Why an interval cannot be booked now: 'outside_hours', 'slot_unavailable' or null when it fits.
-- p_appointment_id ignores that appointment's own reservation (moving a confirmed appointment).
create function private.agenda_slot_problem(p_profile_id uuid, p_start timestamptz, p_end timestamptz,
    p_appointment_id uuid default null)
returns text language sql stable security definer set search_path = ''
as $$
    select case
        when exists (select 1 from public.availability_windows w where w.profile_id = p_profile_id)
         and not exists (select 1 from public.availability_windows w
            where w.profile_id = p_profile_id
              and w.weekday = extract(isodow from p_start at time zone 'Europe/Madrid')
              and (p_start at time zone 'Europe/Madrid')::time >= w.start_time
              and (p_end at time zone 'Europe/Madrid')::date = (p_start at time zone 'Europe/Madrid')::date
              and (p_end at time zone 'Europe/Madrid')::time <= w.end_time)
            then 'outside_hours'
        when exists (select 1 from public.agenda_reservations r
            where r.profile_id = p_profile_id and r.during && tstzrange(p_start, p_end, '[)')
              and r.appointment_id is distinct from p_appointment_id)
            then 'slot_unavailable'
    end;
$$;
revoke all on function private.agenda_slot_problem(uuid, timestamptz, timestamptz, uuid) from public, anon, authenticated;

create function private.check_agenda_slot(p_profile_id uuid, p_start timestamptz, p_end timestamptz,
    p_appointment_id uuid default null)
returns void language plpgsql stable security definer set search_path = ''
as $$
declare problem text := private.agenda_slot_problem(p_profile_id, p_start, p_end, p_appointment_id);
begin
    if problem is not null then
        raise exception using errcode = 'PT409', message = problem;
    end if;
end;
$$;
revoke all on function private.check_agenda_slot(uuid, timestamptz, timestamptz, uuid) from public, anon, authenticated;

-- Early check on what is sent: a new pending proposal (#20) and a change that moves or resizes the
-- interval (#23, either participant). A trigger keeps the RPC bodies of #20/#23 untouched.
-- Accepted rows written by accept_change are already checked there and are skipped.
create function private.check_sent_interval() returns trigger language plpgsql security definer set search_path = ''
as $$
declare
    appt public.appointments;
    base public.appointment_proposals;
begin
    select * into appt from public.appointments a where a.id = new.appointment_id;
    if tg_table_name = 'appointment_proposals' then
        if new.status = 'pending' then
            perform private.check_agenda_slot(appt.profile_id, new.start_at, new.end_at, null);
        end if;
    elsif new.kind = 'change' then
        select * into base from public.appointment_proposals p where p.id = new.base_proposal_id;
        if (new.start_at, new.end_at) is distinct from (base.start_at, base.end_at) then
            perform private.check_agenda_slot(appt.profile_id, new.start_at, new.end_at, appt.id);
        end if;
    end if;
    return new;
end;
$$;
revoke all on function private.check_sent_interval() from public, anon, authenticated;
create trigger appointment_proposals_check_interval before insert on public.appointment_proposals
    for each row execute function private.check_sent_interval();
create trigger appointment_changes_check_interval before insert on public.appointment_changes
    for each row execute function private.check_sent_interval();

-- Same as #22, with the shared check: no hours configured means no hours restriction.
create or replace function private.reserve_proposal(p_appointment public.appointments, p_proposal public.appointment_proposals)
returns public.appointment_proposals language plpgsql security definer set search_path = ''
as $$
declare
    result public.appointment_proposals;
begin
    if p_proposal.start_at <= now() then
        raise exception using errcode = 'PT409', message = 'proposal_expired';
    end if;
    perform private.check_agenda_slot(p_appointment.profile_id, p_proposal.start_at, p_proposal.end_at, null);
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

-- Same as 20261123012500 (changes during the job), with the shared agenda check.
create or replace function public.accept_change(p_change_id uuid, p_mode text default null)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    change public.appointment_changes;
    base public.appointment_proposals;
    accepted public.appointment_proposals;
    payment public.appointment_payments;
    total integer;
    diff integer := 0;
    integrated boolean;
    mode text;
begin
    perform public.current_account();
    select a.* into appt from public.appointments a
      join public.appointment_changes c on c.appointment_id = a.id
      where c.id = p_change_id for update of a;
    select * into change from public.appointment_changes c where c.id = p_change_id;
    if appt.id is null or actor not in (appt.client_id, appt.professional_id) or actor = change.author_id then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    perform private.lock_agenda(appt.profile_id);
    if change.status = 'accepted' then
        return to_jsonb(change);
    end if;
    if change.status <> 'pending' then
        raise exception using errcode = 'PT409', message = 'stale_version';
    end if;
    if change.kind <> 'change' then
        raise exception using errcode = '22023', message = 'invalid_change';
    end if;
    select * into base from public.appointment_proposals p where p.appointment_id = appt.id and p.status = 'accepted';
    if appt.stage not in ('confirmed', 'started') or base.id is distinct from change.base_proposal_id then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    if appt.stage = 'started' and (change.start_at <> base.start_at or change.address is not null) then
        raise exception using errcode = 'PT409', message = 'already_started';
    end if;
    if (change.start_at, change.end_at) <> (base.start_at, base.end_at) then
        if change.start_at <> base.start_at and change.start_at <= now() then
            raise exception using errcode = 'PT409', message = 'proposal_expired';
        end if;
        perform private.check_agenda_slot(appt.profile_id, change.start_at, change.end_at, appt.id);
    end if;
    if change.address is not null and not private.point_covered(appt.profile_id, change.latitude, change.longitude) then
        raise exception using errcode = '22023', message = 'out_of_coverage';
    end if;
    select * into payment from public.appointment_payments p where p.appointment_id = appt.id;
    total := greatest(change.amount_cents, coalesce(change.visit_fee_cents, 0));
    if payment.appointment_id is not null then
        integrated := payment.mode = 'integrated';
        diff := total - payment.agreed_cents;
        if integrated and diff < 0 and -diff > payment.pending_cents then
            -- Money already delivered to the professional cannot be refunded by a change.
            raise exception using errcode = 'PT409', message = 'incompatible_state';
        end if;
    elsif total > 0 then
        -- The appointment had no cost: the client chooses how the new amount is paid.
        mode := coalesce(change.payment_mode, case when actor = appt.client_id then p_mode end);
        if mode is null or mode not in ('integrated', 'direct') then
            raise exception using errcode = '22023', message = 'invalid_payment';
        end if;
        integrated := mode = 'integrated';
    end if;
    -- The new conditions become the accepted proposal; the previous one stays in history as replaced.
    update public.appointment_proposals set status = 'replaced' where id = base.id;
    insert into public.appointment_proposals(appointment_id, author_id, version, request_id, scope, start_at, end_at,
        duration_minutes, amount_cents, visit_fee_cents, materials_included, travel_included, status, decided_at)
      values (appt.id, change.author_id,
        (select max(p.version) + 1 from public.appointment_proposals p where p.appointment_id = appt.id),
        change.id, change.scope, change.start_at, change.end_at, change.duration_minutes, change.amount_cents,
        change.visit_fee_cents, change.materials_included, change.travel_included, 'accepted', now())
      returning * into accepted;
    begin
        update public.agenda_reservations set during = tstzrange(change.start_at, change.end_at, '[)'),
            proposal_id = accepted.id
          where appointment_id = appt.id;
    exception when exclusion_violation then
        raise exception using errcode = 'PT409', message = 'slot_unavailable';
    end;
    if change.address is not null then
        update public.appointments set address = change.address, latitude = change.latitude, longitude = change.longitude
          where id = appt.id;
    end if;
    if payment.appointment_id is not null then
        update public.appointment_payments set proposal_id = accepted.id, agreed_cents = total,
            paid_cents = paid_cents + case when integrated then greatest(diff, 0) else 0 end,
            pending_cents = pending_cents + case when integrated then diff else 0 end,
            refunded_cents = refunded_cents + case when integrated then greatest(-diff, 0) else 0 end
          where appointment_id = appt.id;
    elsif total > 0 then
        diff := total;
        insert into public.appointment_payments(appointment_id, proposal_id, client_id, professional_id, request_id,
            mode, agreed_cents, paid_cents, pending_cents)
          values (appt.id, accepted.id, appt.client_id, appt.professional_id, change.id, mode, total,
            case when integrated then total else 0 end, case when integrated then total else 0 end);
    end if;
    -- Direct payment records the agreement only: no integrated money is invented.
    if integrated and diff <> 0 then
        insert into public.payment_movements(appointment_id, kind, amount_cents, actor_id, change_id)
          values (appt.id, case when diff > 0 then 'paid' else 'refunded' end, abs(diff), actor, change.id);
    end if;
    update public.appointment_changes set status = 'accepted', decided_at = now(), result_proposal_id = accepted.id,
        payment_mode = coalesce(payment_mode, mode)
      where id = change.id returning * into change;
    update public.appointments set version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, proposal_id, change_id, kind, actor_id, recipient_id)
      values (appt.id, accepted.id, change.id, 'change_accepted', actor, change.author_id);
    return to_jsonb(change);
end;
$$;
