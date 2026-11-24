-- The professional fills the agenda when presenting the profile, not after approval (#41).
-- submit_professional_profile gains an optional p_agenda {default_duration_minutes, windows[]}, saved
-- with save_agenda in the same transaction: an invalid agenda (22023 invalid_agenda) presents nothing,
-- and an invalid profile saves no agenda. The agenda still needs no review and is never part of the
-- presented version. Without p_agenda (older clients, trusted scripts) it behaves exactly as before.
drop function public.submit_professional_profile(uuid, jsonb);

create function public.submit_professional_profile(p_submission uuid, p_content jsonb, p_agenda jsonb default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare profile private.professional_profiles; approved jsonb; content jsonb := p_content; result jsonb;
begin
    perform public.current_account();
    select * into profile from private.professional_profiles where owner_id = auth.uid();
    if not found then raise exception using errcode = '22023', message = 'profile_not_found'; end if;
    if jsonb_typeof(content) = 'object' then
        approved := private.approved_content(profile.id);
        content := content || jsonb_build_object(
            'services', coalesce(to_jsonb(profile.services), approved->'services', content->'services'),
            'prices', coalesce(profile.prices, approved->'prices', content->'prices', '[]'::jsonb));
    end if;
    if p_submission is null or not private.profile_content_submittable(content) then
        raise exception using errcode = '22023', message = 'invalid_profile_content';
    end if;
    if p_agenda is not null then
        if jsonb_typeof(p_agenda) <> 'object' or jsonb_typeof(p_agenda->'default_duration_minutes') <> 'number'
           or (p_agenda->>'default_duration_minutes')::numeric <> trunc((p_agenda->>'default_duration_minutes')::numeric)
           or abs((p_agenda->>'default_duration_minutes')::numeric) > 100000 then
            raise exception using errcode = '22023', message = 'invalid_agenda';
        end if;
        perform public.save_agenda((p_agenda->>'default_duration_minutes')::integer, p_agenda->'windows');
    end if;
    result := private.submit_profile_version(profile.id, p_submission, content);
    return result;
end;
$$;
revoke all on function public.submit_professional_profile(uuid, jsonb, jsonb) from public, anon;
grant execute on function public.submit_professional_profile(uuid, jsonb, jsonb) to authenticated;
