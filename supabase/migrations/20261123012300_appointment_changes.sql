-- Versioned changes to a confirmed appointment: amount, scope, time, duration and address (#23).
-- A change is only a proposal: the accepted proposal, the agenda reservation, the address and the payment
-- stay as they are until the other participant accepts that exact version. Acceptance re-checks the agenda
-- and coverage and applies everything in one transaction, or nothing.
-- The professional never replaces the client's address; it can only ask the client to correct it.

alter table public.appointment_proposals drop constraint appointment_proposals_status_check,
    add constraint appointment_proposals_status_check
    check (status in ('pending', 'rejected', 'superseded', 'accepted', 'replaced'));

-- Every version of every change is kept. kind = 'change' carries the full new conditions (address only when
-- the client changes it); kind = 'address_correction' is the professional's request, with a note and no terms.
create table public.appointment_changes (
    id uuid primary key default gen_random_uuid(),
    appointment_id uuid not null references public.appointments(id),
    author_id uuid not null references auth.users(id),
    version integer not null check (version > 0),
    request_id uuid not null,
    kind text not null constraint appointment_changes_kind_check check (kind in ('change', 'address_correction')),
    -- The accepted conditions this change modifies (shown as "before").
    base_proposal_id uuid not null references public.appointment_proposals(id),
    scope text check (length(scope) between 1 and 2000),
    start_at timestamptz,
    end_at timestamptz,
    duration_minutes integer check (duration_minutes between 15 and 1440),
    amount_cents integer check (amount_cents between 0 and 100000000),
    visit_fee_cents integer check (visit_fee_cents between 0 and 100000000),
    materials_included boolean,
    travel_included boolean,
    -- Null when the address does not change. previous_* keeps the "before" for history.
    address text check (length(address) between 1 and 300),
    latitude double precision check (latitude between -90 and 90),
    longitude double precision check (longitude between -180 and 180),
    previous_address text,
    previous_latitude double precision,
    previous_longitude double precision,
    note text check (length(note) between 1 and 500),
    -- Chosen by the client when the appointment had no cost and the change adds one.
    payment_mode text check (payment_mode in ('integrated', 'direct')),
    status text not null default 'pending'
        constraint appointment_changes_status_check check (status in ('pending', 'accepted', 'rejected', 'superseded')),
    -- The accepted proposal created from this change.
    result_proposal_id uuid references public.appointment_proposals(id),
    created_at timestamptz not null default clock_timestamp(),
    decided_at timestamptz,
    unique (appointment_id, version),
    unique (author_id, request_id),
    check ((address is null) = (latitude is null) and (address is null) = (longitude is null)),
    check (kind <> 'change' or (scope is not null and start_at is not null and duration_minutes is not null
        and amount_cents is not null and materials_included is not null and travel_included is not null
        and end_at = start_at + make_interval(mins => duration_minutes))),
    check (kind <> 'address_correction' or (note is not null and scope is null and address is null))
);
create unique index appointment_changes_one_pending on public.appointment_changes(appointment_id)
    where status = 'pending';
alter table public.appointment_changes enable row level security;
revoke all on public.appointment_changes from public, anon, authenticated;
grant select on public.appointment_changes to authenticated;
-- Changes only exist for confirmed appointments, whose exact place both participants already see (#38).
create policy participants_read on public.appointment_changes for select to authenticated using (
    exists (select 1 from public.appointments a where a.id = appointment_id
      and auth.uid() in (a.client_id, a.professional_id) and a.location_shared_at is not null)
);

alter table public.appointment_events add column change_id uuid references public.appointment_changes(id);
alter table public.appointment_events drop constraint appointment_events_kind_check,
    add constraint appointment_events_kind_check check (kind in ('requested', 'proposed', 'rejected', 'accepted',
        'change_proposed', 'change_accepted', 'change_rejected', 'address_correction_requested'));

-- A movement caused by an accepted change names it; one movement per change at most, so retries never repeat it.
alter table public.payment_movements add column change_id uuid unique references public.appointment_changes(id);
-- A reduction can bring the agreed amount down to zero (everything refunded).
alter table public.appointment_payments drop constraint appointment_payments_agreed_cents_check,
    add constraint appointment_payments_agreed_cents_check check (agreed_cents >= 0);

-- Same coverage rule as request_appointment: the point is in (or within 250 m of) a covered concejo.
create function private.point_covered(p_profile_id uuid, p_lat double precision, p_lng double precision)
returns boolean language sql stable security definer set search_path = ''
as $$
    select exists (
        select 1 from public.concejos c join public.approved_profiles p on p.profile_id = p_profile_id
        where p.coverage @> jsonb_build_array(jsonb_build_object('code', c.ine_code))
          and extensions.st_dwithin(c.boundary,
              extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography, 250))
$$;
revoke all on function private.point_covered(uuid, double precision, double precision) from public, anon, authenticated;

-- Proposes new conditions for a confirmed appointment. Either participant; only the client may change the
-- address. A new version supersedes the pending one; nothing in force changes.
create function public.propose_change(
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
    if appt.stage <> 'confirmed' then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    select * into base from public.appointment_proposals p where p.appointment_id = appt.id and p.status = 'accepted';
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
revoke all on function public.propose_change(uuid, integer, uuid, text, timestamptz, integer, integer, integer,
    boolean, boolean, text, double precision, double precision, text, text) from public, anon;
grant execute on function public.propose_change(uuid, integer, uuid, text, timestamptz, integer, integer, integer,
    boolean, boolean, text, double precision, double precision, text, text) to authenticated;

-- The professional asks the client to correct the address; only the client can then propose a new one.
create function public.request_address_correction(
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
revoke all on function public.request_address_correction(uuid, integer, uuid, text) from public, anon;
grant execute on function public.request_address_correction(uuid, integer, uuid, text) to authenticated;

-- The other participant accepts exactly this version. Under the appointment row lock and then the agenda lock
-- (same order as accept_proposal/confirm_proposal): a new interval must fit the availability and not overlap,
-- a new address must be covered, and the money difference is simulated. Any failure rolls back everything,
-- so the previous conditions, reservation, address and payment stay intact.
create function public.accept_change(p_change_id uuid, p_mode text default null)
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
    if appt.stage <> 'confirmed' or base.id is distinct from change.base_proposal_id then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
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
revoke all on function public.accept_change(uuid, text) from public, anon;
grant execute on function public.accept_change(uuid, text) to authenticated;

-- The other participant rejects (or, for a correction request, dismisses) the pending version.
-- History and every condition in force are kept.
create function public.reject_change(p_change_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    result public.appointment_changes;
begin
    perform public.current_account();
    select a.* into appt from public.appointments a
      join public.appointment_changes c on c.appointment_id = a.id
      where c.id = p_change_id for update of a;
    select * into result from public.appointment_changes c where c.id = p_change_id;
    if appt.id is null or actor not in (appt.client_id, appt.professional_id) or actor = result.author_id then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if result.status = 'rejected' then
        return to_jsonb(result);
    end if;
    if result.status <> 'pending' then
        raise exception using errcode = 'PT409', message = 'stale_version';
    end if;
    update public.appointment_changes set status = 'rejected', decided_at = now()
      where id = p_change_id returning * into result;
    update public.appointments set version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, change_id, kind, actor_id, recipient_id)
      values (appt.id, result.id, 'change_rejected', actor, result.author_id);
    return to_jsonb(result);
end;
$$;
revoke all on function public.reject_change(uuid) from public, anon;
grant execute on function public.reject_change(uuid) to authenticated;
