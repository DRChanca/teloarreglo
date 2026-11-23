-- Public signed image URLs must not reveal an Auth account UUID in their path.
-- Keep previously prepared objects untouched; only new requests use opaque paths.
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
        case when p_sha256 is null then 'pending' else 'uploading' end,
        case when p_sha256 is null then clock_timestamp() end)
    returning * into result;
    return private.cover_result(result);
end;
$$;
