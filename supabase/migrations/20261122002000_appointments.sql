-- Appointment requests and versioned proposals, without definitive reservation.
-- Ticket: Solicitar cita y recibir una propuesta (#20).
-- The exact client address and pin exist only here, readable by the two participants.
-- Later tickets (#21–#25) extend the stage/status checks by name and add reservations.

create table public.appointments (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations(id),
    profile_id uuid not null references private.professional_profiles(id),
    client_id uuid not null references auth.users(id),
    professional_id uuid not null references auth.users(id),
    request_id uuid not null,
    problem text not null check (length(problem) between 1 and 2000),
    requested_start timestamptz not null,
    requested_end timestamptz not null,
    address text not null check (length(address) between 1 and 300),
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    -- Optimistic concurrency token: every accepted change increments it.
    version integer not null default 1 check (version > 0),
    stage text not null default 'pending' constraint appointments_stage_check check (stage in ('pending')),
    created_at timestamptz not null default clock_timestamp(),
    unique (client_id, request_id),
    check (requested_end > requested_start),
    check (client_id <> professional_id)
);
create index appointments_conversation on public.appointments(conversation_id, created_at);
alter table public.appointments enable row level security;
revoke all on public.appointments from public, anon, authenticated;
grant select on public.appointments to authenticated;
create policy participants_read on public.appointments for select to authenticated
    using (auth.uid() in (client_id, professional_id));

-- Every version is kept; rejection or revision never deletes history.
create table public.appointment_proposals (
    id uuid primary key default gen_random_uuid(),
    appointment_id uuid not null references public.appointments(id),
    author_id uuid not null references auth.users(id),
    version integer not null check (version > 0),
    request_id uuid not null,
    scope text not null check (length(scope) between 1 and 2000),
    start_at timestamptz not null,
    end_at timestamptz not null,
    duration_minutes integer not null check (duration_minutes between 15 and 1440),
    amount_cents integer not null check (amount_cents between 0 and 100000000),
    -- Cost charged even if no repair is done; null when the visit itself is free.
    visit_fee_cents integer check (visit_fee_cents between 0 and 100000000),
    materials_included boolean not null default false,
    travel_included boolean not null default false,
    status text not null default 'pending'
        constraint appointment_proposals_status_check check (status in ('pending', 'rejected', 'superseded')),
    created_at timestamptz not null default clock_timestamp(),
    decided_at timestamptz,
    unique (appointment_id, version),
    unique (appointment_id, request_id),
    check (end_at = start_at + make_interval(mins => duration_minutes))
);
create unique index appointment_proposals_one_pending on public.appointment_proposals(appointment_id)
    where status = 'pending';
alter table public.appointment_proposals enable row level security;
revoke all on public.appointment_proposals from public, anon, authenticated;
grant select on public.appointment_proposals to authenticated;
create policy participants_read on public.appointment_proposals for select to authenticated using (
    exists (select 1 from public.appointments a where a.id = appointment_id
      and auth.uid() in (a.client_id, a.professional_id))
);

-- Persisted notices of confirmed operations, for in-app state and future push delivery (#32).
create table public.appointment_events (
    id uuid primary key default gen_random_uuid(),
    appointment_id uuid not null references public.appointments(id),
    proposal_id uuid references public.appointment_proposals(id),
    kind text not null constraint appointment_events_kind_check check (kind in ('requested', 'proposed', 'rejected')),
    actor_id uuid not null references auth.users(id),
    recipient_id uuid not null references auth.users(id),
    created_at timestamptz not null default clock_timestamp()
);
create index appointment_events_recipient on public.appointment_events(recipient_id, created_at);
alter table public.appointment_events enable row level security;
revoke all on public.appointment_events from public, anon, authenticated;
grant select on public.appointment_events to authenticated;
create policy participants_read on public.appointment_events for select to authenticated
    using (auth.uid() in (actor_id, recipient_id));

create function public.request_appointment(
    p_conversation_id uuid, p_request_id uuid, p_problem text,
    p_start timestamptz, p_end timestamptz, p_address text,
    p_lat double precision, p_lng double precision)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    conv public.conversations;
    result public.appointments;
begin
    perform public.current_account();
    select * into conv from public.conversations c where c.id = p_conversation_id;
    if conv.id is null or conv.client_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_request_id is null or p_problem is null or length(btrim(p_problem)) not between 1 and 2000
       or p_address is null or length(btrim(p_address)) not between 1 and 300
       or p_start is null or p_end is null or p_end <= p_start or p_end - p_start > interval '24 hours'
       or p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
        raise exception using errcode = '22023', message = 'invalid_appointment';
    end if;
    select * into result from public.appointments a where a.client_id = actor and a.request_id = p_request_id;
    if result.id is not null then
        if result.conversation_id <> p_conversation_id or result.problem <> btrim(p_problem)
           or result.requested_start <> p_start or result.requested_end <> p_end
           or result.address <> btrim(p_address) or result.latitude <> p_lat or result.longitude <> p_lng then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return to_jsonb(result);
    end if;
    if p_start <= now() then
        raise exception using errcode = '22023', message = 'invalid_appointment';
    end if;
    -- The pin must fall in (or within 250 m of) a concejo the published profile covers.
    if not exists (
        select 1 from public.concejos c join public.approved_profiles p on p.profile_id = conv.profile_id
        where p.coverage @> jsonb_build_array(jsonb_build_object('code', c.ine_code))
          and extensions.st_dwithin(c.boundary,
              extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography, 250)) then
        raise exception using errcode = '22023', message = 'out_of_coverage';
    end if;
    insert into public.appointments(conversation_id, profile_id, client_id, professional_id, request_id,
        problem, requested_start, requested_end, address, latitude, longitude)
      values (conv.id, conv.profile_id, actor, conv.professional_id, p_request_id,
        btrim(p_problem), p_start, p_end, btrim(p_address), p_lat, p_lng)
      returning * into result;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (result.id, 'requested', actor, conv.professional_id);
    return to_jsonb(result);
end;
$$;
revoke all on function public.request_appointment(uuid, uuid, text, timestamptz, timestamptz, text,
    double precision, double precision) from public, anon;
grant execute on function public.request_appointment(uuid, uuid, text, timestamptz, timestamptz, text,
    double precision, double precision) to authenticated;

-- A new version supersedes the pending one; it never confirms or reserves the interval.
create function public.propose_appointment(
    p_appointment_id uuid, p_expected_version integer, p_request_id uuid, p_scope text,
    p_start timestamptz, p_duration_minutes integer, p_amount_cents integer,
    p_visit_fee_cents integer, p_materials_included boolean, p_travel_included boolean)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    result public.appointment_proposals;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or appt.professional_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_request_id is null or p_expected_version is null or p_scope is null
       or length(btrim(p_scope)) not between 1 and 2000 or p_start is null
       or p_duration_minutes is null or p_duration_minutes not between 15 and 1440
       or p_amount_cents is null or p_amount_cents not between 0 and 100000000
       or (p_visit_fee_cents is not null and p_visit_fee_cents not between 0 and 100000000)
       or p_materials_included is null or p_travel_included is null then
        raise exception using errcode = '22023', message = 'invalid_proposal';
    end if;
    select * into result from public.appointment_proposals p
      where p.appointment_id = appt.id and p.request_id = p_request_id;
    if result.id is not null then
        if result.scope <> btrim(p_scope) or result.start_at <> p_start
           or result.duration_minutes <> p_duration_minutes or result.amount_cents <> p_amount_cents
           or result.visit_fee_cents is distinct from p_visit_fee_cents
           or result.materials_included <> p_materials_included or result.travel_included <> p_travel_included then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return to_jsonb(result);
    end if;
    if appt.version <> p_expected_version then
        raise exception using errcode = '40001', message = 'stale_version';
    end if;
    if appt.stage <> 'pending' then
        raise exception using errcode = '55000', message = 'incompatible_state';
    end if;
    if p_start <= now() then
        raise exception using errcode = '22023', message = 'invalid_proposal';
    end if;
    update public.appointment_proposals set status = 'superseded', decided_at = now()
      where appointment_id = appt.id and status = 'pending';
    insert into public.appointment_proposals(appointment_id, author_id, version, request_id, scope, start_at, end_at,
        duration_minutes, amount_cents, visit_fee_cents, materials_included, travel_included)
      values (appt.id, actor,
        (select coalesce(max(p.version), 0) + 1 from public.appointment_proposals p where p.appointment_id = appt.id),
        p_request_id, btrim(p_scope), p_start, p_start + make_interval(mins => p_duration_minutes),
        p_duration_minutes, p_amount_cents, p_visit_fee_cents, p_materials_included, p_travel_included)
      returning * into result;
    update public.appointments set version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, proposal_id, kind, actor_id, recipient_id)
      values (appt.id, result.id, 'proposed', actor, appt.client_id);
    return to_jsonb(result);
end;
$$;
revoke all on function public.propose_appointment(uuid, integer, uuid, text, timestamptz, integer, integer,
    integer, boolean, boolean) from public, anon;
grant execute on function public.propose_appointment(uuid, integer, uuid, text, timestamptz, integer, integer,
    integer, boolean, boolean) to authenticated;

-- Rejecting a proposal keeps chat and history; the appointment stays pending, unreserved.
create function public.reject_proposal(p_proposal_id uuid)
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
    select * into result from public.appointment_proposals p where p.id = p_proposal_id;
    if result.status = 'rejected' then
        return to_jsonb(result);
    end if;
    if result.status <> 'pending' then
        raise exception using errcode = '40001', message = 'stale_version';
    end if;
    update public.appointment_proposals set status = 'rejected', decided_at = now()
      where id = p_proposal_id returning * into result;
    update public.appointments set version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, proposal_id, kind, actor_id, recipient_id)
      values (appt.id, result.id, 'rejected', actor, appt.professional_id);
    return to_jsonb(result);
end;
$$;
revoke all on function public.reject_proposal(uuid) from public, anon;
grant execute on function public.reject_proposal(uuid) to authenticated;
