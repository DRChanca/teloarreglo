-- Immutable private uploads become publicly readable only after a reviewed snapshot points to them.
alter table public.approved_profiles add column cover_path text;
create table private.professional_cover_requests (
    id uuid primary key,
    version bigint generated always as identity unique,
    profile_id uuid not null references private.professional_profiles(id),
    owner_id uuid not null references auth.users(id),
    digest text,
    object_path text unique,
    status text not null check (status in ('uploading','pending','approved','rejected')),
    reason text,
    created_at timestamptz not null default clock_timestamp(),
    submitted_at timestamptz,
    reviewed_at timestamptz,
    reviewed_by uuid references auth.users(id),
    check ((digest is null and object_path is null and status <> 'uploading')
        or (digest is not null and digest ~ '^[0-9a-f]{64}$' and object_path is not null))
);
create index cover_requests_profile_version on private.professional_cover_requests(profile_id,version desc);
alter table private.professional_cover_requests enable row level security;
revoke all on private.professional_cover_requests from public,anon,authenticated;
create table private.professional_cover_decisions (
    request_id uuid primary key references private.professional_cover_requests(id),
    administrator_id uuid not null references auth.users(id),
    approved boolean not null,
    reason text not null check (length(btrim(reason)) > 0),
    created_at timestamptz not null default clock_timestamp()
);
alter table private.professional_cover_decisions enable row level security;
revoke all on private.professional_cover_decisions from public,anon,authenticated;

create function private.cover_result(p private.professional_cover_requests)
returns jsonb language sql immutable set search_path = '' as $$
    select jsonb_build_object('id', p.id, 'status', p.status,
        'object_path', p.object_path, 'reason', p.reason);
$$;
revoke all on function private.cover_result(private.professional_cover_requests) from public,anon,authenticated;

create function public.professional_cover(p_profile uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare result private.professional_cover_requests;
begin
    perform public.current_account();
    if not exists(select 1 from private.professional_profiles where id=p_profile and owner_id=auth.uid()) then
        raise exception using errcode='42501',message='permission_denied';
    end if;
    select * into result from private.professional_cover_requests
        where profile_id=p_profile and status <> 'uploading' order by version desc limit 1;
    if not found then return null; end if;
    return private.cover_result(result);
end;
$$;

create function public.request_professional_cover(p_profile uuid,p_request uuid,p_sha256 text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare result private.professional_cover_requests;
begin
    perform public.current_account();
    perform 1 from private.professional_profiles where id=p_profile and owner_id=auth.uid() for update;
    if not found then raise exception using errcode='42501',message='permission_denied'; end if;
    if p_sha256 is not null and p_sha256 !~ '^[0-9a-f]{64}$' then
        raise exception using errcode='22023',message='invalid_image_digest';
    end if;
    select * into result from private.professional_cover_requests where id=p_request;
    if found then
        if result.owner_id<>auth.uid() or result.profile_id<>p_profile
            or result.digest is distinct from p_sha256 then
            raise exception using errcode='22023',message='request_conflict';
        end if;
        return private.cover_result(result);
    end if;
    insert into private.professional_cover_requests(id,profile_id,owner_id,digest,object_path,status,submitted_at)
    values(p_request,p_profile,auth.uid(),p_sha256,
        case when p_sha256 is not null then auth.uid()::text||'/'||p_profile::text||'/'||p_request::text||'.jpg' end,
        case when p_sha256 is null then 'pending' else 'uploading' end,
        case when p_sha256 is null then clock_timestamp() end)
    returning * into result;
    return private.cover_result(result);
end;
$$;

create function public.submit_professional_cover(p_request uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare result private.professional_cover_requests; profile uuid;
begin
    perform public.current_account();
    select profile_id into profile from private.professional_cover_requests where id=p_request and owner_id=auth.uid();
    perform 1 from private.professional_profiles where id=profile and owner_id=auth.uid() for update;
    if not found then raise exception using errcode='42501',message='permission_denied'; end if;
    select * into result from private.professional_cover_requests where id=p_request;
    if result.status <> 'uploading' then return private.cover_result(result); end if;
    if exists(select 1 from private.professional_cover_requests where profile_id=profile
        and version>result.version and status<>'uploading') then
        raise exception using errcode='40001',message='cover_changed';
    end if;
    if not exists(select 1 from storage.objects where bucket_id='professional-covers' and name=result.object_path
        and metadata->>'mimetype'='image/jpeg'
        and (metadata->>'size') ~ '^[0-9]+$'
        and (metadata->>'size')::bigint between 1 and 2097152) then
        raise exception using errcode='22023',message='upload_not_complete';
    end if;
    update private.professional_cover_requests set status='pending',submitted_at=clock_timestamp()
        where id=p_request returning * into result;
    return private.cover_result(result);
end;
$$;

create function public.review_professional_cover(p_request uuid,p_approve boolean,p_reason text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare result private.professional_cover_requests; profile uuid;
begin
    if not (public.current_account()->>'is_administrator')::boolean then
        raise exception using errcode='42501',message='permission_denied';
    end if;
    if p_reason is null or length(btrim(p_reason)) not between 1 and 2000 or p_approve is null then
        raise exception using errcode='22023',message='reason_required';
    end if;
    select profile_id into profile from private.professional_cover_requests where id=p_request;
    perform 1 from private.professional_profiles where id=profile for update;
    if not found then raise exception using errcode='22023',message='cover_not_found'; end if;
    select * into result from private.professional_cover_requests where id=p_request;
    if result.status in ('approved','rejected') then
        if exists(select 1 from private.professional_cover_decisions where request_id=p_request
            and administrator_id=auth.uid() and approved=p_approve and reason=btrim(p_reason)) then
            return private.cover_result(result);
        end if;
        raise exception using errcode='22023',message='review_already_recorded';
    end if;
    if result.status<>'pending' or exists(select 1 from private.professional_cover_requests
        where profile_id=profile and version>result.version and status<>'uploading') then
        raise exception using errcode='40001',message='cover_changed';
    end if;
    if p_approve then
        update public.approved_profiles set cover_path=result.object_path,approved_at=now() where profile_id=profile;
        if not found then raise exception using errcode='22023',message='profile_not_published'; end if;
    end if;
    insert into private.professional_cover_decisions(request_id,administrator_id,approved,reason)
        values(p_request,auth.uid(),p_approve,btrim(p_reason));
    update private.professional_cover_requests set status=case when p_approve then 'approved' else 'rejected' end,
        reason=btrim(p_reason),reviewed_at=clock_timestamp(),reviewed_by=auth.uid()
        where id=p_request returning * into result;
    return private.cover_result(result);
end;
$$;

create function public.can_write_professional_cover(p_path text)
returns boolean language sql stable security definer set search_path = '' as $$
    select exists(select 1 from private.professional_cover_requests r
        join auth.users u on u.id=auth.uid()
        join private.professional_profiles p on p.id=r.profile_id and p.owner_id=u.id
        where r.object_path=p_path and r.owner_id=u.id and r.status='uploading'
        and u.email_confirmed_at is not null and u.deleted_at is null
        and (u.banned_until is null or u.banned_until<=now()));
$$;
create function public.can_read_professional_cover(p_path text)
returns boolean language sql stable security definer set search_path = '' as $$
    select exists(select 1 from public.approved_profiles where cover_path=p_path)
    or exists(select 1 from private.professional_cover_requests r join auth.users u on u.id=auth.uid()
        where r.object_path=p_path and u.email_confirmed_at is not null and u.deleted_at is null
        and (u.banned_until is null or u.banned_until<=now())
        and (r.owner_id=u.id or exists(select 1 from private.administrators where user_id=u.id)));
$$;
revoke all on function public.professional_cover(uuid),
 public.request_professional_cover(uuid,uuid,text),public.submit_professional_cover(uuid),
 public.review_professional_cover(uuid,boolean,text),public.can_write_professional_cover(text) from public,anon;
grant execute on function public.professional_cover(uuid),
 public.request_professional_cover(uuid,uuid,text),public.submit_professional_cover(uuid),
 public.review_professional_cover(uuid,boolean,text),public.can_write_professional_cover(text) to authenticated;
revoke all on function public.can_read_professional_cover(text) from public;
grant execute on function public.can_read_professional_cover(text) to anon,authenticated;
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
    values('professional-covers','professional-covers',false,2097152,array['image/jpeg']);
create policy professional_cover_insert on storage.objects for insert to authenticated
    with check(bucket_id='professional-covers' and public.can_write_professional_cover(name));
create policy professional_cover_read on storage.objects for select to anon,authenticated
    using(bucket_id='professional-covers' and public.can_read_professional_cover(name));
-- No client update/delete policy: submitted objects and decisions stay immutable.
