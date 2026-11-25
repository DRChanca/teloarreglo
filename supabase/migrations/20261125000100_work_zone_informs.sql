-- La zona de trabajo informa, no restringe (docs/adr/0001-zona-de-trabajo-informa.md, David 2026-11-25).
-- 1. Search: every approved, located profile of the trade, flagged in_zone/featured; the Edge Function
--    ranks featured-in-zone first and everything else by distance.
-- 2. Appointments and address changes are allowed anywhere in Asturias (outside_area otherwise).
-- 3. The owner of a published profile changes its zone at once, without review; every change is
--    recorded append-only and administration can read it. Approving a later version keeps the zone in force.

-- 1. Search ------------------------------------------------------------------------------------------
create function public.search_all_candidates(p_ine_code text, p_trade text)
returns table (profile_id uuid, lat double precision, lng double precision, in_zone boolean, featured boolean)
language sql stable security definer set search_path = ''
as $$
    select p.profile_id,
           extensions.st_y(l.location::extensions.geometry),
           extensions.st_x(l.location::extensions.geometry),
           p.coverage @> jsonb_build_array(jsonb_build_object('code', p_ine_code)),
           private.is_featured(p.profile_id)
    from public.approved_profiles p
    join private.professional_locations l on l.profile_id = p.profile_id
    where p_trade is null or p_trade = any(p.trades)
    order by p.profile_id;
$$;
revoke all on function public.search_all_candidates(text, text) from public, anon, authenticated;
grant execute on function public.search_all_candidates(text, text) to service_role;

-- 2. Appointments anywhere in Asturias ----------------------------------------------------------------
-- Same tolerance as before (250 m), now against any concejo instead of the profile's zone.
create function private.point_in_area(p_lat double precision, p_lng double precision)
returns boolean language sql stable security definer set search_path = ''
as $$
    select exists (select 1 from public.concejos c
        where extensions.st_dwithin(c.boundary,
              extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography, 250))
$$;
revoke all on function private.point_in_area(double precision, double precision) from public, anon, authenticated;

-- propose_change and accept_change call point_covered and raise out_of_coverage when it is false: the
-- zone no longer restricts, so it only checks the area. The old error name stays for those two callers
-- (clients map both names); request_appointment below raises outside_area.
create or replace function private.point_covered(p_profile_id uuid, p_lat double precision, p_lng double precision)
returns boolean language sql stable security definer set search_path = ''
as $$ select private.point_in_area(p_lat, p_lng) $$;

create or replace function public.request_appointment(
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
    -- Anywhere in Asturias (in or within 250 m of a concejo); the work zone only informs (ADR 0001).
    if not private.point_in_area(p_lat, p_lng) then
        raise exception using errcode = '22023', message = 'outside_area';
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

-- Whether a point lies in the profile's work zone, for the informative notices of both participants.
-- Participants only (null otherwise); reveals nothing beyond the public zone they can already read.
-- Also returns the concejo of the address, so the professional can add it to the zone in one tap.
create function public.appointment_zone(p_appointment_id uuid)
returns jsonb language sql stable security definer set search_path = ''
as $$
    select jsonb_build_object(
        'in_zone', exists (select 1 from public.approved_profiles p
            where p.profile_id = a.profile_id
              and p.coverage @> jsonb_build_array(jsonb_build_object('code', c.ine_code))),
        'concejo', jsonb_build_object('ine_code', c.ine_code, 'name', c.name))
    from public.appointments a
    cross join lateral (select k.ine_code, k.name from public.concejos k
        order by extensions.st_distance(k.boundary,
            extensions.st_setsrid(extensions.st_makepoint(a.longitude, a.latitude), 4326)::extensions.geography)
        limit 1) c
    where a.id = p_appointment_id and auth.uid() in (a.client_id, a.professional_id)
$$;
revoke all on function public.appointment_zone(uuid) from public, anon;
grant execute on function public.appointment_zone(uuid) to authenticated;

-- 3. Work zone without review ----------------------------------------------------------------------------
create table private.work_zone_changes (
    id bigint generated always as identity primary key,
    profile_id uuid not null references private.professional_profiles(id),
    actor_id uuid not null references auth.users(id),
    ine_code text not null,
    added boolean not null,
    changed_at timestamptz not null default clock_timestamp()
);
create index work_zone_changes_profile on private.work_zone_changes(profile_id, changed_at desc);
alter table private.work_zone_changes enable row level security;
revoke all on private.work_zone_changes from public, anon, authenticated;
create trigger work_zone_changes_immutable before update or delete on private.work_zone_changes
    for each row execute function private.reject_audit_change();
create trigger work_zone_changes_no_truncate before truncate on private.work_zone_changes
    for each statement execute function private.reject_audit_change();

-- Replaces the zone of the caller's published profile with p_codes (INE codes of Asturias, at least one).
-- Applied at once to the public profile; each added or removed concejo is recorded. Same set → no-op.
create function public.set_my_work_zone(p_codes text[])
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    actor uuid := auth.uid();
    profile uuid;
    current_codes text[];
    wanted text[];
    zone jsonb;
begin
    perform public.current_account();
    select id into profile from private.professional_profiles where owner_id = actor for update;
    if profile is null or not exists (select 1 from public.approved_profiles where profile_id = profile) then
        raise exception using errcode = '42501', message = 'not_published';
    end if;
    select coalesce(array_agg(distinct c order by c), '{}') into wanted from unnest(p_codes) c where c is not null;
    if cardinality(wanted) = 0 then
        raise exception using errcode = '22023', message = 'zone_empty';
    end if;
    if cardinality(wanted) > 78
       or exists (select 1 from unnest(wanted) c where not exists (select 1 from public.concejos k where k.ine_code = c)) then
        raise exception using errcode = '22023', message = 'unknown_concejo';
    end if;
    select coalesce(array_agg(e->>'code' order by e->>'code'), '{}') into current_codes
        from public.approved_profiles a, jsonb_array_elements(a.coverage) e where a.profile_id = profile;
    if current_codes is distinct from wanted then
        select jsonb_agg(jsonb_build_object('code', k.ine_code, 'name', k.name) order by k.name) into zone
            from public.concejos k where k.ine_code = any(wanted);
        update public.approved_profiles set coverage = zone where profile_id = profile;
        insert into private.work_zone_changes(profile_id, actor_id, ine_code, added)
            select profile, actor, c, true from unnest(wanted) c where not c = any(current_codes)
            union all
            select profile, actor, c, false from unnest(current_codes) c where not c = any(wanted);
    end if;
    return (select coverage from public.approved_profiles where profile_id = profile);
end;
$$;
revoke all on function public.set_my_work_zone(text[]) from public, anon;
grant execute on function public.set_my_work_zone(text[]) to authenticated;

-- Panel history of zone changes for one profile. Administrators only; never the owner's identity.
create function public.admin_work_zone_changes(p_profile uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
    perform private.require_administrator();
    return coalesce((select jsonb_agg(jsonb_build_object('ine_code', w.ine_code,
            'name', (select k.name from public.concejos k where k.ine_code = w.ine_code),
            'added', w.added, 'changed_at', w.changed_at) order by w.changed_at desc, w.id desc)
        from private.work_zone_changes w where w.profile_id = p_profile), '[]'::jsonb);
end;
$$;
revoke all on function public.admin_work_zone_changes(uuid) from public, anon;
grant execute on function public.admin_work_zone_changes(uuid) to authenticated;

-- Approving a later version keeps the zone in force on an already published profile: a change made
-- while the version waited is never undone. The first approval still publishes the submitted zone.
create or replace function public.review_professional_profile(p_submission uuid, p_approve boolean, p_reason text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    actor uuid := private.require_administrator();
    result private.profile_submissions;
    profile_owner uuid;
    v_reason text := btrim(p_reason);
begin
    if p_approve is null or v_reason is null or length(v_reason) not between 1 and 2000 then
        raise exception using errcode = '22023', message = 'reason_required';
    end if;
    select owner_id into profile_owner from private.professional_profiles
        where id = (select profile_id from private.profile_submissions where id = p_submission) for update;
    if not found then raise exception using errcode = '22023', message = 'submission_not_found'; end if;
    if profile_owner = actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    select * into result from private.profile_submissions where id = p_submission;
    if result.status <> 'pending' then
        if exists (select 1 from private.profile_decisions where submission_id = p_submission
            and administrator_id = actor and approved = p_approve and profile_decisions.reason = v_reason) then
            return private.submission_result(result);
        end if;
        raise exception using errcode = '22023', message = 'review_already_recorded';
    end if;
    if exists (select 1 from private.profile_submissions where profile_id = result.profile_id and version > result.version) then
        raise exception using errcode = '40001', message = 'profile_changed';
    end if;
    if p_approve then
        insert into public.approved_profiles(profile_id, name, description, trades, services, coverage,
            languages, phone, images, prices, business_address, approved_at)
        select result.profile_id, r.name, r.description, r.trades, r.services, r.coverage,
            r.languages, r.phone, coalesce(r.images, '{}'), coalesce(r.prices, '[]'), r.business_address, now()
        from jsonb_populate_record(null::public.approved_profiles, result.content) r
        on conflict (profile_id) do update set name = excluded.name, description = excluded.description,
            trades = excluded.trades, services = excluded.services,
            -- ADR 0001: the zone of a published profile is edited without review; keep it.
            coverage = public.approved_profiles.coverage,
            languages = excluded.languages, phone = excluded.phone, images = excluded.images,
            prices = excluded.prices, business_address = excluded.business_address, approved_at = now();
    end if;
    insert into private.profile_decisions(submission_id, profile_id, administrator_id, approved, reason)
        values (p_submission, result.profile_id, actor, p_approve, v_reason);
    update private.profile_submissions set status = case when p_approve then 'approved' else 'rejected' end
        where id = p_submission returning * into result;
    update private.professional_profiles set review_status = result.status where id = result.profile_id;
    if profile_owner is not null then
        insert into private.notices(recipient_id, kind, source_id, payload)
            values (profile_owner, 'profile_review', p_submission, jsonb_build_object(
                'profile_id', result.profile_id, 'submission_id', p_submission,
                'status', result.status, 'reason', v_reason))
            on conflict do nothing;
    end if;
    return private.submission_result(result);
end;
$$;
