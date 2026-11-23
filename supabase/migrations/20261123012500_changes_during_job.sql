-- Changes during the job (David, 2026-11-23). After «Comenzar trabajo» either participant may still propose and
-- accept a new price, scope, duration or inclusions (extra work agreed on the spot), with the same rules as #23:
-- integrated payment pays the increase or refunds the reduction (simulated) at acceptance, direct records the
-- agreement only, and a new interval must still fit the agenda. A new start time, a new address and an address
-- correction are refused with already_started. Starting keeps a pending change that leaves the start and the
-- address alone; cancelling (only before the start) still closes any pending change. Cancelled: no changes.

create or replace function public.propose_change(
    p_appointment_id uuid, p_expected_version integer, p_request_id uuid, p_scope text,
    p_start timestamptz, p_duration_minutes integer, p_amount_cents integer, p_visit_fee_cents integer,
    p_materials_included boolean, p_travel_included boolean,
    p_address text default null, p_lat double precision default null, p_lng double precision default null,
    p_note text default null, p_mode text default null)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    base public.appointment_proposals;
    payment public.appointment_payments;
    result public.appointment_changes;
    new_address text := nullif(btrim(coalesce(p_address, '')), '');
    note text := nullif(btrim(coalesce(p_note, '')), '');
    mode text;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or actor not in (appt.client_id, appt.professional_id) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_request_id is null or p_expected_version is null or p_scope is null
       or length(btrim(p_scope)) not between 1 and 2000 or p_start is null
       or p_duration_minutes is null or p_duration_minutes not between 15 and 1440
       or p_amount_cents is null or p_amount_cents not between 0 and 100000000
       or (p_visit_fee_cents is not null and p_visit_fee_cents not between 0 and 100000000)
       or p_materials_included is null or p_travel_included is null
       or length(coalesce(note, '')) > 500 or (p_mode is not null and p_mode not in ('integrated', 'direct'))
       or (new_address is not null and (length(new_address) > 300 or p_lat is null or p_lng is null
           or p_lat not between -90 and 90 or p_lng not between -180 and 180))
       or (new_address is null and (p_lat is not null or p_lng is not null)) then
        raise exception using errcode = '22023', message = 'invalid_change';
    end if;
    if new_address is not null and actor <> appt.client_id then
        raise exception using errcode = '42501', message = 'address_owner_only';
    end if;
    select * into result from public.appointment_changes c where c.author_id = actor and c.request_id = p_request_id;
    if result.id is not null then
        if result.appointment_id <> appt.id or result.kind <> 'change' or result.scope <> btrim(p_scope)
           or result.start_at <> p_start or result.duration_minutes <> p_duration_minutes
           or result.amount_cents <> p_amount_cents or result.visit_fee_cents is distinct from p_visit_fee_cents
           or result.materials_included <> p_materials_included or result.travel_included <> p_travel_included
           or result.address is distinct from new_address or result.latitude is distinct from p_lat
           or result.longitude is distinct from p_lng or result.note is distinct from note then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return to_jsonb(result);
    end if;
    if appt.version <> p_expected_version then
        raise exception using errcode = 'PT409', message = 'stale_version';
    end if;
    if appt.stage not in ('confirmed', 'started') then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    select * into base from public.appointment_proposals p where p.appointment_id = appt.id and p.status = 'accepted';
    -- During the job only price, scope, duration and inclusions change: the start and the place are where work is.
    if appt.stage = 'started' and (p_start <> base.start_at or new_address is not null) then
        raise exception using errcode = 'PT409', message = 'already_started';
    end if;
    if base.scope = btrim(p_scope) and base.start_at = p_start and base.duration_minutes = p_duration_minutes
       and base.amount_cents = p_amount_cents and base.visit_fee_cents is not distinct from p_visit_fee_cents
       and base.materials_included = p_materials_included and base.travel_included = p_travel_included
       and (new_address is null or (new_address = appt.address and p_lat = appt.latitude and p_lng = appt.longitude)) then
        raise exception using errcode = '22023', message = 'no_change';
    end if;
    if p_start <> base.start_at and p_start <= now() then
        raise exception using errcode = '22023', message = 'invalid_change';
    end if;
    if new_address is not null and not private.point_covered(appt.profile_id, p_lat, p_lng) then
        raise exception using errcode = '22023', message = 'out_of_coverage';
    end if;
    -- A cost on an appointment that had none needs the client's payment mode, given with its own change.
    select * into payment from public.appointment_payments p where p.appointment_id = appt.id;
    if payment.appointment_id is null and greatest(p_amount_cents, coalesce(p_visit_fee_cents, 0)) > 0
       and actor = appt.client_id then
        if p_mode is null then
            raise exception using errcode = '22023', message = 'invalid_payment';
        end if;
        mode := p_mode;
    end if;
    update public.appointment_changes set status = 'superseded', decided_at = now()
      where appointment_id = appt.id and status = 'pending';
    insert into public.appointment_changes(appointment_id, author_id, version, request_id, kind, base_proposal_id,
        scope, start_at, end_at, duration_minutes, amount_cents, visit_fee_cents, materials_included, travel_included,
        address, latitude, longitude, previous_address, previous_latitude, previous_longitude, note, payment_mode)
      values (appt.id, actor,
        (select coalesce(max(c.version), 0) + 1 from public.appointment_changes c where c.appointment_id = appt.id),
        p_request_id, 'change', base.id, btrim(p_scope), p_start, p_start + make_interval(mins => p_duration_minutes),
        p_duration_minutes, p_amount_cents, p_visit_fee_cents, p_materials_included, p_travel_included,
        new_address, case when new_address is not null then p_lat end, case when new_address is not null then p_lng end,
        case when new_address is not null then appt.address end, case when new_address is not null then appt.latitude end,
        case when new_address is not null then appt.longitude end, note, mode)
      returning * into result;
    update public.appointments set version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, change_id, kind, actor_id, recipient_id)
      values (appt.id, result.id, 'change_proposed', actor,
        case when actor = appt.client_id then appt.professional_id else appt.client_id end);
    return to_jsonb(result);
end;
$$;

create or replace function public.request_address_correction(
    p_appointment_id uuid, p_expected_version integer, p_request_id uuid, p_note text)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    result public.appointment_changes;
    note text := nullif(btrim(coalesce(p_note, '')), '');
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or appt.professional_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_request_id is null or p_expected_version is null or note is null or length(note) > 500 then
        raise exception using errcode = '22023', message = 'invalid_change';
    end if;
    select * into result from public.appointment_changes c where c.author_id = actor and c.request_id = p_request_id;
    if result.id is not null then
        if result.appointment_id <> appt.id or result.kind <> 'address_correction' or result.note <> note then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return to_jsonb(result);
    end if;
    if appt.version <> p_expected_version then
        raise exception using errcode = 'PT409', message = 'stale_version';
    end if;
    if appt.stage = 'started' then
        raise exception using errcode = 'PT409', message = 'already_started';
    end if;
    if appt.stage <> 'confirmed' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    update public.appointment_changes set status = 'superseded', decided_at = now()
      where appointment_id = appt.id and status = 'pending';
    insert into public.appointment_changes(appointment_id, author_id, version, request_id, kind, base_proposal_id, note)
      values (appt.id, actor,
        (select coalesce(max(c.version), 0) + 1 from public.appointment_changes c where c.appointment_id = appt.id),
        p_request_id, 'address_correction',
        (select p.id from public.appointment_proposals p where p.appointment_id = appt.id and p.status = 'accepted'), note)
      returning * into result;
    update public.appointments set version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, change_id, kind, actor_id, recipient_id)
      values (appt.id, result.id, 'address_correction_requested', actor, appt.client_id);
    return to_jsonb(result);
end;
$$;

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
    local_start timestamp;
    local_end timestamp;
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
        local_start := change.start_at at time zone 'Europe/Madrid';
        local_end := change.end_at at time zone 'Europe/Madrid';
        if not exists (select 1 from public.availability_windows w
            where w.profile_id = appt.profile_id and w.weekday = extract(isodow from local_start)
              and local_start::time >= w.start_time
              and local_end::date = local_start::date and local_end::time <= w.end_time) then
            raise exception using errcode = 'PT409', message = 'slot_unavailable';
        end if;
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

create or replace function public.start_work(p_appointment_id uuid)
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
    -- Starting accepts nothing. A pending change of price, scope, duration or inclusions stays open (it can still be
    -- agreed during the job); one that moves the start or the address, and an address correction, are closed.
    update public.appointment_changes c set status = 'superseded', decided_at = now()
      from public.appointment_proposals p
      where c.appointment_id = appt.id and c.status = 'pending' and p.id = c.base_proposal_id
        and (c.kind = 'address_correction' or c.start_at <> p.start_at or c.address is not null);
    update public.appointments set stage = 'started', started_at = now(), version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'started', actor, appt.client_id);
    return private.appointment_marks(appt.id);
end;
$$;
