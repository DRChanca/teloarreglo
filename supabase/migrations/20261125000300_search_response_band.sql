-- «Tiempo de respuesta» in the search card (#80): each search candidate carries `response_band`, the
-- same value as the public profile (catalogue). The rule stays in private.response_band (#76); this only
-- calls it. The Edge Function passes it through unchanged and never orders by it.
-- The return type changes, so the function is dropped and recreated with the same grants.
drop function public.search_all_candidates(text, text);
create function public.search_all_candidates(p_ine_code text, p_trade text)
returns table (profile_id uuid, lat double precision, lng double precision, in_zone boolean, featured boolean,
               response_band text)
language sql stable security definer set search_path = ''
as $$
    select p.profile_id,
           extensions.st_y(l.location::extensions.geometry),
           extensions.st_x(l.location::extensions.geometry),
           p.coverage @> jsonb_build_array(jsonb_build_object('code', p_ine_code)),
           private.is_featured(p.profile_id),
           private.response_band(p.profile_id)
    from public.approved_profiles p
    join private.professional_locations l on l.profile_id = p.profile_id
    where p_trade is null or p_trade = any(p.trades)
    order by p.profile_id;
$$;
revoke all on function public.search_all_candidates(text, text) from public, anon, authenticated;
grant execute on function public.search_all_candidates(text, text) to service_role;
