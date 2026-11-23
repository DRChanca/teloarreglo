-- Owner presents, corrects and changes a professional profile (#30).
-- Builds on the immutable versions of #29: every public change is a new submission that
-- administration decides on; public.approved_profiles keeps the last approved version until
-- a newer one is approved. Agenda, conversations and appointments never go through review.

-- Public fields of the approved version, exactly the keys a submission may carry.
create function private.approved_content(p_profile uuid)
returns jsonb language sql stable set search_path = '' as $$
    select jsonb_build_object('name', a.name, 'description', a.description, 'trades', to_jsonb(a.trades),
        'services', to_jsonb(a.services), 'coverage', a.coverage, 'languages', to_jsonb(a.languages),
        'phone', a.phone, 'images', to_jsonb(a.images), 'prices', a.prices, 'business_address', a.business_address)
    from public.approved_profiles a where a.profile_id = p_profile;
$$;

-- What an owner may present: a version administration can approve as is, without rewriting it.
create function private.profile_content_submittable(p jsonb)
returns boolean language plpgsql immutable set search_path = '' as $$
begin
    if not coalesce(private.profile_content_valid(p), false) then return false; end if;
    return length(btrim(p->>'name')) between 1 and 120
        and length(p->>'description') <= 4000
        and p->>'phone' ~ '^\+?[0-9][0-9 ]{5,19}$'
        and jsonb_array_length(p->'trades') between 1 and 7
        and (select bool_and(jsonb_typeof(t) = 'string' and t #>> '{}' = any(array['plumbing','electrical',
            'carpentry','painting','masonry','general','cleaning'])) from jsonb_array_elements(p->'trades') t)
        and jsonb_array_length(p->'services') <= 30
        and (select coalesce(bool_and(jsonb_typeof(s) = 'string' and length(btrim(s #>> '{}')) between 1 and 120), true)
            from jsonb_array_elements(p->'services') s)
        and jsonb_array_length(p->'coverage') between 1 and 78
        and (select bool_and(jsonb_typeof(c) = 'object' and coalesce(c->>'code', '') ~ '^33[0-9]{3}$'
            and jsonb_typeof(c->'name') = 'string' and length(btrim(c->>'name')) between 1 and 80)
            from jsonb_array_elements(p->'coverage') c)
        and jsonb_array_length(p->'languages') between 1 and 10
        and (select bool_and(jsonb_typeof(l) = 'string' and l #>> '{}' ~ '^[a-z]{2}$')
            from jsonb_array_elements(p->'languages') l)
        and (select coalesce(bool_and(jsonb_typeof(i) = 'string'), true)
            from jsonb_array_elements(coalesce(p->'images', '[]')) i)
        and (select coalesce(bool_and(jsonb_typeof(r) = 'object'), true)
            from jsonb_array_elements(coalesce(p->'prices', '[]')) r)
        and (p->>'business_address' is null or length(btrim(p->>'business_address')) between 1 and 240);
end;
$$;
revoke all on function private.approved_content(uuid), private.profile_content_submittable(jsonb)
    from public, anon, authenticated;

-- Owner view for editing: what is public, what to edit next and the latest review.
-- editable = latest presented version (to correct a rejection or keep editing a pending change),
-- otherwise the approved version, otherwise an empty profile. null without a profile.
create function public.my_professional_profile()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare profile private.professional_profiles;
begin
    perform public.current_account();
    select * into profile from private.professional_profiles where owner_id = auth.uid();
    if not found then return null; end if;
    return jsonb_build_object(
        'profile_id', profile.id,
        'private_address', profile.private_address,
        'public', private.approved_content(profile.id),
        'editable', coalesce(
            (select s.content from private.profile_submissions s where s.profile_id = profile.id order by s.version desc limit 1),
            private.approved_content(profile.id),
            jsonb_build_object('name', '', 'description', '', 'trades', '[]'::jsonb, 'services', '[]'::jsonb,
                'coverage', '[]'::jsonb, 'languages', '["es"]'::jsonb, 'phone', '', 'images', '[]'::jsonb,
                'prices', '[]'::jsonb, 'business_address', null)),
        'review', public.my_profile_review());
end;
$$;

-- The hiring account offers services: one profile per account, created only for itself.
create function public.start_professional_profile()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
    perform public.current_account();
    insert into private.professional_profiles(owner_id) values (auth.uid()) on conflict (owner_id) do nothing;
    return public.my_professional_profile();
end;
$$;

-- Owner presents a version of the public fields. Idempotent by p_submission (see #29).
create function public.submit_professional_profile(p_submission uuid, p_content jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare profile uuid;
begin
    perform public.current_account();
    select id into profile from private.professional_profiles where owner_id = auth.uid();
    if not found then raise exception using errcode = '22023', message = 'profile_not_found'; end if;
    if p_submission is null or not private.profile_content_submittable(p_content) then
        raise exception using errcode = '22023', message = 'invalid_profile_content';
    end if;
    return private.submit_profile_version(profile, p_submission, p_content);
end;
$$;

-- Private address: never public, never in a version, so it needs no review.
create function public.save_professional_private_address(p_address text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_address text := nullif(btrim(p_address), '');
begin
    perform public.current_account();
    if v_address is not null and length(v_address) > 240 then
        raise exception using errcode = '22023', message = 'invalid_private_address';
    end if;
    update private.professional_profiles set private_address = v_address where owner_id = auth.uid();
    if not found then raise exception using errcode = '22023', message = 'profile_not_found'; end if;
    return public.my_professional_profile();
end;
$$;

revoke all on function public.my_professional_profile(), public.start_professional_profile(),
    public.submit_professional_profile(uuid, jsonb), public.save_professional_private_address(text) from public, anon;
grant execute on function public.my_professional_profile(), public.start_professional_profile(),
    public.submit_professional_profile(uuid, jsonb), public.save_professional_private_address(text) to authenticated;

-- Panel list (#29) now also carries the currently public version, so a change to an approved
-- profile is compared with what visitors see. Still never owner identity nor private address.
create or replace function public.admin_profile_submissions(p_status text default 'pending')
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare actor uuid := private.require_administrator();
begin
    if p_status is null or p_status not in ('pending', 'approved', 'rejected', 'all') then
        raise exception using errcode = '22023', message = 'invalid_status';
    end if;
    return coalesce((select jsonb_agg(row order by row->>'submitted_at' desc) from (
        select private.submission_result(s) || jsonb_build_object(
            'content', s.content,
            'public_content', private.approved_content(s.profile_id),
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
