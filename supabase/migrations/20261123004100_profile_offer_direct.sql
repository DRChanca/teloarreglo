-- Services and reference prices are published directly by the owner, without review
-- (David, 2026-11-23). Name, description, trades, coverage, languages, phone and business
-- address keep the #30 review. Does not edit earlier migrations nor prepared data.
--
-- private.professional_profiles.services/prices hold what the owner published. null means the
-- owner has not managed them yet (prepared catalogue, older profiles): then the approved snapshot
-- keeps whatever trusted SQL or the last approved version put there.
alter table private.professional_profiles
    add column services text[],
    add column prices jsonb check (prices is null or jsonb_typeof(prices) = 'array');

-- What the catalogue can always show: services 1–120 chars (≤ 30) and complete price rows (≤ 20)
-- with the public shape {service, scope, unit, amount_cents, includes, excludes}.
create function private.profile_offer_valid(p_services jsonb, p_prices jsonb)
returns boolean language plpgsql immutable set search_path = '' as $$
begin
    if jsonb_typeof(p_services) is distinct from 'array' or jsonb_typeof(p_prices) is distinct from 'array'
        or jsonb_array_length(p_services) > 30 or jsonb_array_length(p_prices) > 20 then
        return false;
    end if;
    return (select coalesce(bool_and(jsonb_typeof(s) = 'string' and length(btrim(s #>> '{}')) between 1 and 120), true)
            from jsonb_array_elements(p_services) s)
        and (select coalesce(bool_and(
                jsonb_typeof(r) = 'object'
                and (select array_agg(k order by k) from jsonb_object_keys(r) k)
                    = array['amount_cents', 'excludes', 'includes', 'scope', 'service', 'unit']
                and jsonb_typeof(r->'service') = 'string' and length(btrim(r->>'service')) between 1 and 120
                and jsonb_typeof(r->'scope') = 'string' and length(r->>'scope') <= 300
                and jsonb_typeof(r->'includes') = 'string' and length(r->>'includes') <= 300
                and jsonb_typeof(r->'excludes') = 'string' and length(r->>'excludes') <= 300
                and r->>'unit' in ('hour', 'sqm', 'unit', 'job')
                and jsonb_typeof(r->'amount_cents') = 'number' and (r->>'amount_cents') ~ '^[0-9]{1,9}$'
                and (r->>'amount_cents')::bigint <= 100000000), true)
            from jsonb_array_elements(p_prices) r);
end;
$$;

-- Whatever writes the approved snapshot (first approval, a later approved version, trusted SQL),
-- services and prices published by the owner win: approving an older version never brings back
-- older prices.
create function private.apply_profile_offer()
returns trigger language plpgsql set search_path = '' as $$
declare offer record;
begin
    select services, prices into offer from private.professional_profiles where id = new.profile_id;
    if offer.services is not null then new.services := offer.services; end if;
    if offer.prices is not null then new.prices := offer.prices; end if;
    return new;
end;
$$;
create trigger approved_profiles_offer before insert or update on public.approved_profiles
    for each row execute function private.apply_profile_offer();
revoke all on function private.profile_offer_valid(jsonb, jsonb), private.apply_profile_offer()
    from public, anon, authenticated;

-- Owner view: the editable services and prices are always the published ones.
create or replace function public.my_professional_profile()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare profile private.professional_profiles; editable jsonb;
begin
    perform public.current_account();
    select * into profile from private.professional_profiles where owner_id = auth.uid();
    if not found then return null; end if;
    editable := coalesce(
        (select s.content from private.profile_submissions s where s.profile_id = profile.id order by s.version desc limit 1),
        private.approved_content(profile.id),
        jsonb_build_object('name', '', 'description', '', 'trades', '[]'::jsonb, 'services', '[]'::jsonb,
            'coverage', '[]'::jsonb, 'languages', '["es"]'::jsonb, 'phone', '', 'images', '[]'::jsonb,
            'prices', '[]'::jsonb, 'business_address', null));
    if profile.services is not null then editable := editable || jsonb_build_object('services', to_jsonb(profile.services)); end if;
    if profile.prices is not null then editable := editable || jsonb_build_object('prices', profile.prices); end if;
    return jsonb_build_object(
        'profile_id', profile.id,
        'private_address', profile.private_address,
        'public', private.approved_content(profile.id),
        'editable', editable,
        'review', public.my_profile_review());
end;
$$;

-- A profile started from the app manages its services and prices from the start.
create or replace function public.start_professional_profile()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
    perform public.current_account();
    insert into private.professional_profiles(owner_id, services, prices) values (auth.uid(), '{}', '[]')
        on conflict (owner_id) do nothing;
    return public.my_professional_profile();
end;
$$;

-- A presented version never changes services or prices: they are replaced by the published ones
-- (or, for a profile whose owner has not managed them, by the approved ones).
create or replace function public.submit_professional_profile(p_submission uuid, p_content jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare profile private.professional_profiles; approved jsonb; content jsonb := p_content;
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
    return private.submit_profile_version(profile.id, p_submission, content);
end;
$$;

-- Owner publishes services and reference prices at once. Repeating it is harmless (same state).
-- Existing appointments, requests and proposals keep the amounts they already stored.
create function public.save_professional_offer(p_services jsonb, p_prices jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare profile uuid; v_services text[];
begin
    perform public.current_account();
    select id into profile from private.professional_profiles where owner_id = auth.uid() for update;
    if not found then raise exception using errcode = '22023', message = 'profile_not_found'; end if;
    if not coalesce(private.profile_offer_valid(p_services, p_prices), false) then
        raise exception using errcode = '22023', message = 'invalid_profile_offer';
    end if;
    v_services := array(select btrim(s.value) from jsonb_array_elements_text(p_services) with ordinality s(value, n) order by s.n);
    update private.professional_profiles set services = v_services, prices = p_prices where id = profile;
    update public.approved_profiles set services = v_services, prices = p_prices where profile_id = profile;
    return public.my_professional_profile();
end;
$$;
revoke all on function public.save_professional_offer(jsonb, jsonb) from public, anon;
grant execute on function public.save_professional_offer(jsonb, jsonb) to authenticated;
