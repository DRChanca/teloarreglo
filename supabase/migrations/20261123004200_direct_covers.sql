-- Covers are published directly by the owner, without administrative review (David, 2026-11-23).
-- Kept: the private bucket (JPEG, 2 MiB), immutable objects, owner-only requests, server checks of
-- the stored object and idempotent request keys. Does not edit earlier migrations.
--
-- private.professional_profiles.cover_path is the owner's current cover, published or not. When a
-- profile is first approved its snapshot takes it, so an unapproved profile shows its photo as
-- soon as it is published.
alter table private.professional_profiles add column cover_path text;
update private.professional_profiles p set cover_path = a.cover_path
    from public.approved_profiles a where a.profile_id = p.id and a.cover_path is not null;

alter table private.professional_cover_requests drop constraint professional_cover_requests_status_check,
    add constraint professional_cover_requests_status_check
    check (status in ('uploading', 'pending', 'approved', 'rejected', 'published'));

-- Decision taken now for proposals still waiting for review: the latest one of each profile is
-- published, as if sent today; older ones stay as history.
with latest as (
    select distinct on (profile_id) id, profile_id, object_path, status from private.professional_cover_requests
    where status <> 'uploading' order by profile_id, version desc
), published as (
    update private.professional_cover_requests r set status = 'published'
    from latest l where r.id = l.id and l.status = 'pending'
    returning r.profile_id, r.object_path
), profiles as (
    update private.professional_profiles p set cover_path = published.object_path
    from published where p.id = published.profile_id
    returning p.id
)
update public.approved_profiles a set cover_path = published.object_path
    from published where a.profile_id = published.profile_id;

create function private.apply_profile_cover()
returns trigger language plpgsql set search_path = '' as $$
begin
    if new.cover_path is null then
        new.cover_path := (select cover_path from private.professional_profiles where id = new.profile_id);
    end if;
    return new;
end;
$$;
create trigger approved_profiles_cover before insert on public.approved_profiles
    for each row execute function private.apply_profile_cover();

create function private.publish_profile_cover(p_profile uuid, p_path text)
returns void language sql set search_path = '' as $$
    update private.professional_profiles set cover_path = p_path where id = p_profile;
    update public.approved_profiles set cover_path = p_path where profile_id = p_profile;
$$;
revoke all on function private.apply_profile_cover(), private.publish_profile_cover(uuid, text)
    from public, anon, authenticated;

-- Without a digest: back to the trade image, published at once.
create or replace function public.request_professional_cover(p_profile uuid,p_request uuid,p_sha256 text default null)
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
        case when p_sha256 is not null then p_profile::text||'/'||p_request::text||'.jpg' end,
        case when p_sha256 is null then 'published' else 'uploading' end,
        case when p_sha256 is null then clock_timestamp() end)
    returning * into result;
    if p_sha256 is null then perform private.publish_profile_cover(p_profile, null); end if;
    return private.cover_result(result);
end;
$$;

-- Publishes the photo once Storage holds the JPEG (≤ 2 MiB) reserved for this request.
create or replace function public.submit_professional_cover(p_request uuid)
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
    update private.professional_cover_requests set status='published',submitted_at=clock_timestamp()
        where id=p_request returning * into result;
    perform private.publish_profile_cover(profile, result.object_path);
    return private.cover_result(result);
end;
$$;

-- No cover review remains. Earlier decisions stay as history in private.professional_cover_decisions.
revoke all on function public.review_professional_cover(uuid,boolean,text) from public, anon, authenticated;
