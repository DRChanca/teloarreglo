-- Defense in depth: administrator membership never authorizes reviewing one's own business.
create or replace function public.review_professional_cover(p_request uuid,p_approve boolean,p_reason text)
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
    if result.owner_id=auth.uid() or exists(select 1 from private.professional_profiles
        where id=profile and owner_id=auth.uid()) then
        raise exception using errcode='42501',message='permission_denied';
    end if;
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
