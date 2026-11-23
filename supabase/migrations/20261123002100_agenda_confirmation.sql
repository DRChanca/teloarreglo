-- One agenda per professional profile (availability, usual duration, blocks) and confirmation
-- of free proposals by the client, protected against overlaps by a PostgreSQL exclusion constraint.
-- Ticket: Gestionar disponibilidad y confirmar visitas gratuitas sin solapes (#21).
-- Paid confirmation (#22) adds its own entry point; it must reserve through agenda_reservations too.

create extension if not exists btree_gist with schema extensions;

alter table public.appointments drop constraint appointments_stage_check,
    add constraint appointments_stage_check check (stage in ('pending', 'confirmed'));
alter table public.appointment_proposals drop constraint appointment_proposals_status_check,
    add constraint appointment_proposals_status_check check (status in ('pending', 'rejected', 'superseded', 'accepted'));
alter table public.appointment_events drop constraint appointment_events_kind_check,
    add constraint appointment_events_kind_check check (kind in ('requested', 'proposed', 'rejected', 'accepted'));

-- Weekly availability is expressed in Madrid local time (the demo is Asturias only).
-- Windows end at most at 23:59 the same day; an appointment must fit inside one window.
create table public.agenda_settings (
    profile_id uuid primary key references private.professional_profiles(id),
    professional_id uuid not null unique references auth.users(id),
    default_duration_minutes integer not null check (default_duration_minutes between 15 and 1440),
    timezone text not null default 'Europe/Madrid' check (timezone = 'Europe/Madrid'),
    updated_at timestamptz not null default clock_timestamp()
);
create table public.availability_windows (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references public.agenda_settings(profile_id),
    professional_id uuid not null references auth.users(id),
    weekday integer not null check (weekday between 1 and 7),  -- ISO: 1 = Monday
    start_time time not null,
    end_time time not null,
    check (end_time > start_time)
);
create index availability_windows_profile on public.availability_windows(profile_id, weekday);

-- Everything that occupies the single agenda: confirmed appointments and the professional's blocks.
-- Half-open [start, end) ranges: contiguous intervals touch without overlapping.
create table public.agenda_reservations (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references private.professional_profiles(id),
    professional_id uuid not null references auth.users(id),
    kind text not null check (kind in ('appointment', 'block')),
    during tstzrange not null check (not isempty(during) and lower_inc(during) and not upper_inc(during)),
    appointment_id uuid unique references public.appointments(id),
    proposal_id uuid references public.appointment_proposals(id),
    request_id uuid,
    reason text check (length(reason) <= 200),
    created_at timestamptz not null default clock_timestamp(),
    unique (professional_id, request_id),
    check ((kind = 'appointment') = (appointment_id is not null and proposal_id is not null)),
    check ((kind = 'block') = (request_id is not null)),
    constraint agenda_reservations_no_overlap exclude using gist (profile_id with =, during with &&)
);

alter table public.agenda_settings enable row level security;
alter table public.availability_windows enable row level security;
alter table public.agenda_reservations enable row level security;
revoke all on public.agenda_settings, public.availability_windows, public.agenda_reservations
    from public, anon, authenticated;
grant select on public.agenda_settings, public.availability_windows, public.agenda_reservations to authenticated;
create policy owner_read on public.agenda_settings for select to authenticated using (professional_id = auth.uid());
create policy owner_read on public.availability_windows for select to authenticated using (professional_id = auth.uid());
create policy owner_read on public.agenda_reservations for select to authenticated using (professional_id = auth.uid());

-- The caller's own profile id, or permission_denied.
create function private.own_profile_id() returns uuid language plpgsql security definer set search_path = ''
as $$
declare
    result uuid;
begin
    perform public.current_account();
    select p.id into result from private.professional_profiles p where p.owner_id = auth.uid();
    if result is null then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    return result;
end;
$$;
revoke all on function private.own_profile_id() from public, anon, authenticated;

-- Serializes every write to one profile's agenda (schedule, blocks, confirmations) until commit.
-- Concurrent inserts checked by the exclusion constraint can otherwise deadlock (40P01 → HTTP 500)
-- instead of one of them failing cleanly with slot_unavailable.
create function private.lock_agenda(p_profile_id uuid) returns void language sql security definer set search_path = ''
as $$ select pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('agenda:' || p_profile_id::text, 0)) $$;
revoke all on function private.lock_agenda(uuid) from public, anon, authenticated;

create function public.my_agenda() returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    profile uuid := private.own_profile_id();
    settings public.agenda_settings;
begin
    select * into settings from public.agenda_settings s where s.profile_id = profile;
    return jsonb_build_object(
        'profile_id', profile,
        'timezone', 'Europe/Madrid',
        'default_duration_minutes', settings.default_duration_minutes,
        'windows', coalesce((select jsonb_agg(jsonb_build_object('weekday', w.weekday,
            'start', to_char(w.start_time, 'HH24:MI'), 'end', to_char(w.end_time, 'HH24:MI'))
            order by w.weekday, w.start_time) from public.availability_windows w where w.profile_id = profile), '[]'),
        'blocks', coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'start_at', lower(r.during),
            'end_at', upper(r.during), 'reason', r.reason) order by lower(r.during))
            from public.agenda_reservations r
            where r.profile_id = profile and r.kind = 'block' and upper(r.during) > now()), '[]'),
        'bookings', coalesce((select jsonb_agg(jsonb_build_object('appointment_id', r.appointment_id,
            'start_at', lower(r.during), 'end_at', upper(r.during)) order by lower(r.during))
            from public.agenda_reservations r
            where r.profile_id = profile and r.kind = 'appointment' and upper(r.during) > now()), '[]'));
end;
$$;
revoke all on function public.my_agenda() from public, anon;
grant execute on function public.my_agenda() to authenticated;

-- Replaces the whole weekly schedule atomically, so a retry is naturally idempotent.
-- Confirmed appointments keep their reservation even if their hours stop being available.
create function public.save_agenda(p_default_duration_minutes integer, p_windows jsonb)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    profile uuid := private.own_profile_id();
    item jsonb;
begin
    if p_default_duration_minutes is null or p_default_duration_minutes not between 15 and 1440
       or p_windows is null or jsonb_typeof(p_windows) <> 'array' or jsonb_array_length(p_windows) > 50 then
        raise exception using errcode = '22023', message = 'invalid_agenda';
    end if;
    for item in select * from jsonb_array_elements(p_windows) loop
        if jsonb_typeof(item) <> 'object' or jsonb_typeof(item->'weekday') <> 'number'
           or (item->>'weekday')::numeric not in (1, 2, 3, 4, 5, 6, 7)
           or coalesce(item->>'start', '') !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
           or coalesce(item->>'end', '') !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
           or (item->>'end')::time <= (item->>'start')::time then
            raise exception using errcode = '22023', message = 'invalid_agenda';
        end if;
    end loop;
    -- A concurrent confirmation sees either the old or the new schedule, never a mix.
    perform private.lock_agenda(profile);
    insert into public.agenda_settings(profile_id, professional_id, default_duration_minutes)
      values (profile, auth.uid(), p_default_duration_minutes)
      on conflict (profile_id) do update set default_duration_minutes = excluded.default_duration_minutes,
        updated_at = clock_timestamp();
    delete from public.availability_windows w where w.profile_id = profile;
    insert into public.availability_windows(profile_id, professional_id, weekday, start_time, end_time)
      select profile, auth.uid(), (e->>'weekday')::integer, (e->>'start')::time, (e->>'end')::time
      from jsonb_array_elements(p_windows) e;
    return public.my_agenda();
end;
$$;
revoke all on function public.save_agenda(integer, jsonb) from public, anon;
grant execute on function public.save_agenda(integer, jsonb) to authenticated;

create function public.add_agenda_block(p_request_id uuid, p_start timestamptz, p_end timestamptz, p_reason text)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    profile uuid := private.own_profile_id();
    result public.agenda_reservations;
    reason text := nullif(btrim(coalesce(p_reason, '')), '');
begin
    if p_request_id is null or p_start is null or p_end is null or p_end <= p_start
       or p_end - p_start > interval '31 days' or length(coalesce(reason, '')) > 200 then
        raise exception using errcode = '22023', message = 'invalid_block';
    end if;
    -- Also serializes simultaneous retries of the same key, which then find the first result.
    perform private.lock_agenda(profile);
    select * into result from public.agenda_reservations r
      where r.professional_id = auth.uid() and r.request_id = p_request_id;
    if result.id is not null then
        if result.during <> tstzrange(p_start, p_end, '[)') or result.reason is distinct from reason then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return to_jsonb(result);
    end if;
    if p_end <= now() then
        raise exception using errcode = '22023', message = 'invalid_block';
    end if;
    begin
        insert into public.agenda_reservations(profile_id, professional_id, kind, during, request_id, reason)
          values (profile, auth.uid(), 'block', tstzrange(p_start, p_end, '[)'), p_request_id, reason)
          returning * into result;
    exception when exclusion_violation then
        raise exception using errcode = 'PT409', message = 'slot_unavailable';
    end;
    return to_jsonb(result);
end;
$$;
revoke all on function public.add_agenda_block(uuid, timestamptz, timestamptz, text) from public, anon;
grant execute on function public.add_agenda_block(uuid, timestamptz, timestamptz, text) to authenticated;

-- Idempotent: removing a block that no longer exists (or never was the caller's) changes nothing.
create function public.remove_agenda_block(p_block_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    profile uuid := private.own_profile_id();
begin
    perform private.lock_agenda(profile);
    delete from public.agenda_reservations r
      where r.id = p_block_id and r.profile_id = profile and r.kind = 'block';
    return jsonb_build_object('id', p_block_id, 'removed', true);
end;
$$;
revoke all on function public.remove_agenda_block(uuid) from public, anon;
grant execute on function public.remove_agenda_block(uuid) to authenticated;

-- The client accepts the current free proposal: proposal, stage, reservation and notice change together
-- or not at all. Confirmations of one professional are serialized by lock_agenda; the exclusion
-- constraint remains the final guarantee against overlaps.
create function public.accept_proposal(p_proposal_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    result public.appointment_proposals;
    local_start timestamp;
    local_end timestamp;
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
    if result.start_at <= now() then
        raise exception using errcode = 'PT409', message = 'proposal_expired';
    end if;
    local_start := result.start_at at time zone 'Europe/Madrid';
    local_end := result.end_at at time zone 'Europe/Madrid';
    if not exists (select 1 from public.availability_windows w
        where w.profile_id = appt.profile_id and w.weekday = extract(isodow from local_start)
          and local_start::time >= w.start_time
          and local_end::date = local_start::date and local_end::time <= w.end_time) then
        raise exception using errcode = 'PT409', message = 'slot_unavailable';
    end if;
    begin
        insert into public.agenda_reservations(profile_id, professional_id, kind, during, appointment_id, proposal_id)
          values (appt.profile_id, appt.professional_id, 'appointment',
            tstzrange(result.start_at, result.end_at, '[)'), appt.id, result.id);
    exception when exclusion_violation then
        raise exception using errcode = 'PT409', message = 'slot_unavailable';
    end;
    update public.appointment_proposals set status = 'accepted', decided_at = now()
      where id = p_proposal_id returning * into result;
    update public.appointments set stage = 'confirmed', version = version + 1 where id = appt.id;
    insert into public.appointment_events(appointment_id, proposal_id, kind, actor_id, recipient_id)
      values (appt.id, result.id, 'accepted', actor, appt.professional_id);
    return to_jsonb(result);
end;
$$;
revoke all on function public.accept_proposal(uuid) from public, anon;
grant execute on function public.accept_proposal(uuid) to authenticated;
