-- Reviews of performed appointments (#28).
-- The client of an appointment publishes one review (1–5 stars and a comment) once the service is recorded as
-- performed: confirmed by the client, closed by the clock without an answer (#27, never an automatic review), or
-- acknowledged in a quality complaint even while the payment is held. A dispute about whether it happened keeps the
-- review pending until the administrative decision on performance (#31), which is kept apart from the money.
-- Free and direct appointments are reviewable like integrated ones.
-- The public part (stars, comment, date) lives in public.reviews; which appointment and account it came from stays in
-- private.review_sources. The profile aggregate (approved_profiles.rating/review_count) is recomputed from them.

-- Decision on performance recorded by the incident review (#31), separate from how the money is split:
-- performed enables the review, not_performed never does, missing_evidence keeps a dispute pending.
alter table public.incidents add column performance_decision text
    constraint incidents_performance_decision_check check (performance_decision in ('performed', 'not_performed', 'missing_evidence'));

create table public.reviews (
    id uuid primary key default gen_random_uuid(),
    profile_id uuid not null references private.professional_profiles(id),
    stars integer not null check (stars between 1 and 5),
    comment text not null check (length(comment) between 1 and 2000),
    created_at timestamptz not null default clock_timestamp()
);
create index reviews_profile on public.reviews(profile_id, created_at desc);
alter table public.reviews enable row level security;
revoke all on public.reviews from public, anon, authenticated;
grant select on public.reviews to anon, authenticated;
create policy public_read on public.reviews for select to anon, authenticated using (true);

create table private.review_sources (
    review_id uuid primary key references public.reviews(id),
    appointment_id uuid not null unique references public.appointments(id),
    author_id uuid not null references auth.users(id),
    request_id uuid not null,
    unique (author_id, request_id)
);
revoke all on private.review_sources from public, anon, authenticated;

-- The professional may report a review but never rewrite or remove it; nor can trusted SQL by accident.
create function private.keep_review()
returns trigger language plpgsql set search_path = '' as $$
begin
    raise exception using errcode = '42501', message = 'review_locked';
end;
$$;
revoke all on function private.keep_review() from public, anon, authenticated;
create trigger reviews_locked before update or delete on public.reviews
    for each row execute function private.keep_review();
create trigger review_sources_locked before update or delete on private.review_sources
    for each row execute function private.keep_review();

alter table public.appointment_events drop constraint appointment_events_kind_check,
    add constraint appointment_events_kind_check check (kind in ('requested', 'proposed', 'rejected', 'accepted',
        'change_proposed', 'change_accepted', 'change_rejected', 'address_correction_requested',
        'started', 'cancelled', 'done', 'completed', 'incident_opened', 'incident_evidence', 'review_published'));

-- Whether the client may review now: enabled | not_done | awaiting_confirmation | performance_disputed | not_performed.
-- The latest incident that disputes performance or carries a decision on it rules; otherwise a completed appointment
-- (client confirmation or silent close) or a quality complaint on a job marked done acknowledges the service.
create function private.review_eligibility(p_appointment_id uuid)
returns text language plpgsql stable security definer set search_path = ''
as $$
declare
    appt public.appointments;
    ruling public.incidents;
begin
    select * into appt from public.appointments a where a.id = p_appointment_id;
    if appt.stage not in ('started', 'done', 'completed') then
        return 'not_done';
    end if;
    select * into ruling from public.incidents i
      where i.appointment_id = appt.id and (i.performance = 'disputed' or i.performance_decision is not null)
      order by i.created_at desc limit 1;
    if ruling.id is not null then
        if ruling.performance_decision = 'performed' then
            return 'enabled';
        elsif ruling.performance_decision = 'not_performed' then
            return 'not_performed';
        end if;
        return 'performance_disputed';
    end if;
    if appt.stage = 'completed' or (appt.stage = 'done' and exists (select 1 from public.incidents i
        where i.appointment_id = appt.id and i.performance = 'acknowledged')) then
        return 'enabled';
    end if;
    return case when appt.stage = 'done' then 'awaiting_confirmation' else 'not_done' end;
end;
$$;
revoke all on function private.review_eligibility(uuid) from public, anon, authenticated;

create function private.review_result(p_review_id uuid)
returns jsonb language sql stable security definer set search_path = ''
as $$
    select jsonb_build_object('id', r.id, 'appointment_id', s.appointment_id, 'profile_id', r.profile_id,
        'stars', r.stars, 'comment', r.comment, 'created_at', r.created_at)
    from public.reviews r join private.review_sources s on s.review_id = r.id where r.id = p_review_id
$$;
revoke all on function private.review_result(uuid) from public, anon, authenticated;

-- Takes the appointment row lock first, like confirm_done, open_incident and the clock.
create function public.publish_review(p_appointment_id uuid, p_request_id uuid, p_stars integer, p_comment text,
    p_privacy_acknowledged boolean)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    source private.review_sources;
    existing public.reviews;
    eligibility text;
    new_review uuid;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or appt.client_id <> actor then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_privacy_acknowledged is distinct from true then
        raise exception using errcode = '22023', message = 'privacy_not_acknowledged';
    end if;
    if p_request_id is null or p_stars is null or p_stars not between 1 and 5
       or p_comment is null or length(btrim(p_comment)) not between 1 and 2000 then
        raise exception using errcode = '22023', message = 'invalid_review';
    end if;
    select * into source from private.review_sources s where s.author_id = actor and s.request_id = p_request_id;
    if source.review_id is not null then
        select * into existing from public.reviews r where r.id = source.review_id;
        if source.appointment_id <> appt.id or existing.stars <> p_stars or existing.comment <> btrim(p_comment) then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return private.review_result(existing.id);
    end if;
    if exists (select 1 from private.review_sources s where s.appointment_id = appt.id) then
        raise exception using errcode = 'PT409', message = 'review_exists';
    end if;
    eligibility := private.review_eligibility(appt.id);
    if eligibility <> 'enabled' then
        raise exception using errcode = 'PT409', message = eligibility;
    end if;
    insert into public.reviews(profile_id, stars, comment) values (appt.profile_id, p_stars, btrim(p_comment))
      returning id into new_review;
    insert into private.review_sources(review_id, appointment_id, author_id, request_id)
      values (new_review, appt.id, actor, p_request_id);
    update public.approved_profiles p set rating = agg.rating, review_count = agg.total
      from (select round(avg(r.stars), 2) as rating, count(*)::integer as total
            from public.reviews r where r.profile_id = appt.profile_id) agg
      where p.profile_id = appt.profile_id;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'review_published', actor, appt.professional_id);
    return private.review_result(new_review);
end;
$$;
revoke all on function public.publish_review(uuid, uuid, integer, text, boolean) from public, anon;
grant execute on function public.publish_review(uuid, uuid, integer, text, boolean) to authenticated;

-- For each appointment of the conversation the caller takes part in: published (with the review) or the eligibility.
create function public.review_states(p_conversation_id uuid)
returns table (appointment_id uuid, state text, review jsonb)
language plpgsql stable security definer set search_path = ''
as $$
begin
    perform public.current_account();
    return query
      select a.id, case when s.review_id is null then private.review_eligibility(a.id) else 'published' end,
          case when s.review_id is null then null else private.review_result(s.review_id) end
      from public.appointments a left join private.review_sources s on s.appointment_id = a.id
      where a.conversation_id = p_conversation_id and auth.uid() in (a.client_id, a.professional_id)
      order by a.created_at;
end;
$$;
revoke all on function public.review_states(uuid) from public, anon;
grant execute on function public.review_states(uuid) to authenticated;
