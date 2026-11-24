-- Spanish home address fields (#41). After the street the app asks for número, bloque, portal, escalera,
-- piso and puerta, plus postal code and locality. The readable line in home_addresses.address is still
-- what search, appointments and the summary use; the fields are kept beside it, private like the rest of
-- the row, only so the owner can edit them again. Additive: rows saved before keep address_parts null.
alter table public.home_addresses add column address_parts jsonb
    check (address_parts is null or jsonb_typeof(address_parts) = 'object');

-- Trimmed fields without empty ones, or an error for anything that is not the agreed shape.
create function private.home_address_parts(p_parts jsonb) returns jsonb
language plpgsql immutable set search_path = ''
as $$
declare
    v_key text;
    v_value jsonb;
    v_text text;
    v_limit integer;
    v_result jsonb := '{}'::jsonb;
begin
    if p_parts is null or p_parts = 'null'::jsonb then
        return null;
    end if;
    if jsonb_typeof(p_parts) <> 'object' then
        raise exception using errcode = '22023', message = 'invalid_home_address';
    end if;
    for v_key, v_value in select * from jsonb_each(p_parts) loop
        if v_key not in ('street', 'number', 'block', 'portal', 'staircase', 'floor', 'door', 'locality')
           or jsonb_typeof(v_value) <> 'string' then
            raise exception using errcode = '22023', message = 'invalid_home_address';
        end if;
        v_text := btrim(p_parts ->> v_key);
        v_limit := 20;
        if v_key = 'street' then
            v_limit := 160;
        elsif v_key = 'locality' then
            v_limit := 100;
        end if;
        if length(v_text) > v_limit then
            raise exception using errcode = '22023', message = 'invalid_home_address';
        end if;
        if v_text <> '' then
            v_result := v_result || jsonb_build_object(v_key, v_text);
        end if;
    end loop;
    if (v_result ->> 'street') is null then
        raise exception using errcode = '22023', message = 'invalid_home_address';
    end if;
    return v_result;
end;
$$;
revoke all on function private.home_address_parts(jsonb) from public, anon, authenticated;

-- The return type gains address_parts, so both functions are recreated (set_home_address returns
-- home_address()). The old five-argument call keeps working through the new default.
drop function public.set_home_address(text, double precision, double precision, text, text);
drop function public.home_address();

create function public.home_address()
returns table (address text, lat double precision, lng double precision, ine_code text, concejo_name text,
               source text, place_id text, updated_at timestamptz, address_parts jsonb)
language plpgsql stable security definer set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    return query
        select h.address, h.lat, h.lng, h.ine_code, c.name, h.source, h.place_id, h.updated_at, h.address_parts
        from public.home_addresses h left join public.concejos c on c.ine_code = h.ine_code
        where h.user_id = auth.uid();
end;
$$;
revoke all on function public.home_address() from public, anon;
grant execute on function public.home_address() to authenticated;

create function public.set_home_address(p_address text, p_lat double precision, p_lng double precision,
                                        p_source text, p_place_id text default null, p_parts jsonb default null)
returns table (address text, lat double precision, lng double precision, ine_code text, concejo_name text,
               source text, place_id text, updated_at timestamptz, address_parts jsonb)
language plpgsql volatile security definer set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_address text := btrim(p_address);
    v_place text := case when p_source = 'places' then nullif(btrim(p_place_id), '') end;
    v_parts jsonb;
    v_concejo text;
begin
    if v_user is null then
        raise exception using errcode = '28000', message = 'authentication_required';
    end if;
    if v_address is null or length(v_address) not between 1 and 300
       or p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180
       or p_source is null or p_source not in ('places', 'pin', 'gps')
       or (p_source = 'places' and (v_place is null or length(v_place) > 300)) then
        raise exception using errcode = '22023', message = 'invalid_home_address';
    end if;
    v_parts := private.home_address_parts(p_parts);
    select c.ine_code into v_concejo
    from public.concejos c
    where extensions.st_dwithin(c.boundary,
        extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography, 250)
    order by extensions.st_distance(c.boundary,
        extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography), c.ine_code
    limit 1;
    insert into public.home_addresses as h (user_id, address, lat, lng, ine_code, source, place_id, address_parts, updated_at)
    values (v_user, v_address, p_lat, p_lng, v_concejo, p_source, v_place, v_parts, now())
    on conflict (user_id) do update set address = excluded.address, lat = excluded.lat, lng = excluded.lng,
        ine_code = excluded.ine_code, source = excluded.source, place_id = excluded.place_id,
        address_parts = excluded.address_parts, updated_at = now();
    return query select * from public.home_address();
end;
$$;
revoke all on function public.set_home_address(text, double precision, double precision, text, text, jsonb) from public, anon;
grant execute on function public.set_home_address(text, double precision, double precision, text, text, jsonb) to authenticated;
