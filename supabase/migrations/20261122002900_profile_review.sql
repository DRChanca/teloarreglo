-- Profile review from the private panel (#29).
-- A submission is an immutable snapshot of the public fields a professional asks to publish.
-- Only an administrator (private.administrators, trusted SQL only) decides on a concrete
-- submission; approval copies that snapshot to public.approved_profiles, rejection leaves
-- the public catalogue untouched. Decisions and submitted content are append-only audit.

create table private.profile_submissions (
    id uuid primary key,
    version bigint generated always as identity unique,
    profile_id uuid not null references private.professional_profiles(id),
    content jsonb not null check (jsonb_typeof(content) = 'object'),
    status text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
    submitted_at timestamptz not null default clock_timestamp()
);
create index profile_submissions_profile_version on private.profile_submissions(profile_id, version desc);
alter table private.profile_submissions enable row level security;
revoke all on private.profile_submissions from public, anon, authenticated;

create table private.profile_decisions (
    submission_id uuid primary key references private.profile_submissions(id),
    profile_id uuid not null references private.professional_profiles(id),
    administrator_id uuid not null references auth.users(id),
    approved boolean not null,
    reason text not null check (length(btrim(reason)) between 1 and 2000),
    decided_at timestamptz not null default clock_timestamp()
);
alter table private.profile_decisions enable row level security;
revoke all on private.profile_decisions from public, anon, authenticated;

-- Persisted notices for the affected account. Push delivery (#32) reads from here;
-- push is never the only copy. One notice per (kind, source, recipient).
create table private.notices (
    id uuid primary key default gen_random_uuid(),
    recipient_id uuid not null references auth.users(id) on delete cascade,
    kind text not null check (kind ~ '^[a-z_]{1,40}$'),
    source_id uuid not null,
    payload jsonb not null default '{}' check (jsonb_typeof(payload) = 'object'),
    created_at timestamptz not null default clock_timestamp(),
    unique (kind, source_id, recipient_id)
);
create index notices_recipient_created on private.notices(recipient_id, created_at desc);
alter table private.notices enable row level security;
revoke all on private.notices from public, anon, authenticated;

-- Audit is immutable even for trusted SQL: decisions never change, submissions only
-- move once from pending to the decided status, with identical content.
create function private.reject_audit_change()
returns trigger language plpgsql set search_path = '' as $$
begin
    if tg_table_name = 'profile_submissions' and tg_op = 'UPDATE' then
        if old.status = 'pending' and new.status in ('approved', 'rejected')
            and new.id = old.id and new.profile_id = old.profile_id and new.content = old.content
            and new.submitted_at = old.submitted_at then
            return new;
        end if;
    end if;
    raise exception using errcode = '42501', message = 'audit_immutable';
end;
$$;
create trigger profile_decisions_immutable before update or delete on private.profile_decisions
    for each row execute function private.reject_audit_change();
create trigger profile_decisions_no_truncate before truncate on private.profile_decisions
    for each statement execute function private.reject_audit_change();
create trigger profile_submissions_immutable before update or delete on private.profile_submissions
    for each row execute function private.reject_audit_change();
create trigger profile_submissions_no_truncate before truncate on private.profile_submissions
    for each statement execute function private.reject_audit_change();

-- Only the public fields of approved_profiles. Never owner, addresses, coordinates or ratings.
create function private.profile_content_valid(p jsonb)
returns boolean language sql immutable set search_path = '' as $$
    select p is not null and jsonb_typeof(p) = 'object' and octet_length(p::text) <= 65536
        and (select coalesce(bool_and(k in ('name','description','trades','services','coverage',
            'languages','phone','images','prices','business_address')), true) from jsonb_object_keys(p) k)
        and jsonb_typeof(p->'name') = 'string' and jsonb_typeof(p->'description') = 'string'
        and jsonb_typeof(p->'phone') = 'string'
        and jsonb_typeof(p->'trades') = 'array' and jsonb_typeof(p->'services') = 'array'
        and jsonb_typeof(p->'coverage') = 'array' and jsonb_typeof(p->'languages') = 'array'
        and coalesce(jsonb_typeof(p->'images'), 'array') = 'array'
        and coalesce(jsonb_typeof(p->'prices'), 'array') = 'array'
        and coalesce(jsonb_typeof(p->'business_address'), 'string') in ('string', 'null');
$$;

create function private.submission_result(s private.profile_submissions)
returns jsonb language sql stable set search_path = '' as $$
    select jsonb_build_object('submission_id', s.id, 'profile_id', s.profile_id, 'version', s.version,
        'status', s.status, 'submitted_at', s.submitted_at,
        'reason', d.reason, 'decided_at', d.decided_at,
        'published', exists (select 1 from public.approved_profiles a where a.profile_id = s.profile_id))
    from (select 1) one left join private.profile_decisions d on d.submission_id = s.id;
$$;

-- Trusted entry point to submit a version (preparation SQL now; the owner RPC of #30 later).
-- Idempotent by p_submission: same profile and content returns the existing row.
create function private.submit_profile_version(p_profile uuid, p_submission uuid, p_content jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare result private.profile_submissions;
begin
    if p_submission is null or not private.profile_content_valid(p_content) then
        raise exception using errcode = '22023', message = 'invalid_profile_content';
    end if;
    perform 1 from private.professional_profiles where id = p_profile for update;
    if not found then raise exception using errcode = '22023', message = 'profile_not_found'; end if;
    select * into result from private.profile_submissions where id = p_submission;
    if found then
        if result.profile_id <> p_profile or result.content <> p_content then
            raise exception using errcode = '22023', message = 'request_conflict';
        end if;
        return private.submission_result(result);
    end if;
    insert into private.profile_submissions(id, profile_id, content)
        values (p_submission, p_profile, p_content) returning * into result;
    update private.professional_profiles set review_status = 'pending' where id = p_profile;
    return private.submission_result(result);
end;
$$;
revoke all on function private.submit_profile_version(uuid, uuid, jsonb) from public, anon, authenticated;
revoke all on function private.profile_content_valid(jsonb) from public, anon, authenticated;
revoke all on function private.submission_result(private.profile_submissions) from public, anon, authenticated;
revoke all on function private.reject_audit_change() from public, anon, authenticated;

create function private.require_administrator()
returns uuid language plpgsql stable security definer set search_path = '' as $$
begin
    if not coalesce((public.current_account()->>'is_administrator')::boolean, false) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    return auth.uid();
end;
$$;
revoke all on function private.require_administrator() from public, anon, authenticated;

-- Panel list: submitted public content and decision, never owner identity or private address.
-- p_status: 'pending' (default), 'approved', 'rejected' or 'all'. Own profiles are omitted.
create function public.admin_profile_submissions(p_status text default 'pending')
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare actor uuid := private.require_administrator();
begin
    if p_status is null or p_status not in ('pending', 'approved', 'rejected', 'all') then
        raise exception using errcode = '22023', message = 'invalid_status';
    end if;
    return coalesce((select jsonb_agg(row order by row->>'submitted_at' desc) from (
        select private.submission_result(s) || jsonb_build_object(
            'content', s.content,
            'latest', not exists (select 1 from private.profile_submissions n
                where n.profile_id = s.profile_id and n.version > s.version),
            'decision', case when d.submission_id is null then null else jsonb_build_object(
                'approved', d.approved, 'reason', d.reason, 'decided_at', d.decided_at,
                'administrator_email', u.email) end) as row
        from private.profile_submissions s
        join private.professional_profiles p on p.id = s.profile_id
        left join private.profile_decisions d on d.submission_id = s.id
        left join auth.users u on u.id = d.administrator_id
        where (p_status = 'all' or s.status = p_status)
          and p.owner_id is distinct from actor
        order by s.submitted_at desc
        limit 200) rows), '[]'::jsonb);
end;
$$;

create function public.review_professional_profile(p_submission uuid, p_approve boolean, p_reason text)
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
    -- Administration never reviews its own business, even with a dual-role account.
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
            trades = excluded.trades, services = excluded.services, coverage = excluded.coverage,
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

-- Owner view of the latest review. null without a professional profile.
create function public.my_profile_review()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare profile uuid; latest private.profile_submissions;
begin
    perform public.current_account();
    select id into profile from private.professional_profiles where owner_id = auth.uid();
    if not found then return null; end if;
    select * into latest from private.profile_submissions where profile_id = profile order by version desc limit 1;
    if not found then
        return jsonb_build_object('profile_id', profile, 'status', null,
            'published', exists (select 1 from public.approved_profiles where profile_id = profile));
    end if;
    return private.submission_result(latest);
end;
$$;

create function public.my_notices()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
    perform public.current_account();
    return coalesce((select jsonb_agg(jsonb_build_object('id', n.id, 'kind', n.kind,
            'payload', n.payload, 'created_at', n.created_at) order by n.created_at desc)
        from (select * from private.notices where recipient_id = auth.uid()
              order by created_at desc limit 100) n), '[]'::jsonb);
end;
$$;

revoke all on function public.admin_profile_submissions(text), public.review_professional_profile(uuid, boolean, text),
    public.my_profile_review(), public.my_notices() from public, anon;
grant execute on function public.admin_profile_submissions(text), public.review_professional_profile(uuid, boolean, text),
    public.my_profile_review(), public.my_notices() to authenticated;
