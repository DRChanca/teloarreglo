-- «Tiempo de respuesta» (#70, #76): the band of how fast a professional answers a new client, computed from
-- the server times of the chat messages (one conversation per profile and client). Only conversations whose
-- first message is from the client count; the wait runs from that message to the professional's first message
-- in the conversation, capped at 24 h. An unanswered conversation does not count while its first message is
-- under 24 h old; from then on it weighs just above the cap, so a median on unanswered conversations is
-- `over_day` while a reply of exactly 24 h or later is `same_day`. The ten most recent conversations with a
-- value (by the time of the client's first message) are summarised with the median and mapped to a band closed
-- above. Clients only ever receive the band, never message times or client identities.
--
-- private.response_band is the single rule; the catalogue and the search candidates (#80) call it.

create function private.response_band(p_profile_id uuid)
returns text language sql stable security definer set search_path = ''
as $$
    with waits as (
        select first_msg.created_at as asked_at,
            case
                when reply.created_at is not null
                    then least(extract(epoch from reply.created_at - first_msg.created_at), 86400)
                when now() - first_msg.created_at >= interval '24 hours' then 86401
            end as wait_s
        from public.conversations c
        cross join lateral (
            select m.author_id, m.created_at from public.chat_messages m
            where m.conversation_id = c.id order by m.created_at, m.id limit 1
        ) first_msg
        cross join lateral (
            select min(m.created_at) as created_at from public.chat_messages m
            where m.conversation_id = c.id and m.author_id = c.professional_id
        ) reply
        where c.profile_id = p_profile_id and first_msg.author_id = c.client_id
    ), recent as (
        select wait_s from waits where wait_s is not null order by asked_at desc limit 10
    ), summary as (
        select percentile_cont(0.5) within group (order by wait_s) as median_s from recent
    )
    select case
        when median_s is null then null
        when median_s <= 300 then 'instant'
        when median_s <= 1800 then 'under_30m'
        when median_s <= 3600 then 'under_1h'
        when median_s <= 10800 then 'under_3h'
        when median_s <= 86400 then 'same_day'
        else 'over_day'
    end
    from summary;
$$;
revoke all on function private.response_band(uuid) from public, anon, authenticated;

-- Public band for one approved profile; reveals nothing else. The catalogue is security invoker, so it
-- reaches the private rule through this definer wrapper, like public.is_featured.
create function public.response_band(p_profile_id uuid)
returns text language sql stable security definer set search_path = ''
as $$
    select private.response_band(a.profile_id) from public.approved_profiles a where a.profile_id = p_profile_id;
$$;
revoke all on function public.response_band(uuid) from public, anon, authenticated;
grant execute on function public.response_band(uuid) to anon, authenticated;

-- The return type changes, so the catalogue is dropped and recreated with the same columns plus the band.
drop function public.catalogue(text, uuid);

create function public.catalogue(p_trade text default null, p_id uuid default null)
returns table (
    profile_id uuid, name text, description text, trades text[], services text[], coverage jsonb,
    languages text[], phone text, images text[], prices jsonb, rating numeric, review_count integer,
    approved_at timestamptz, business_address text, cover_path text,
    business_lat double precision, business_lng double precision, featured boolean, response_band text
)
language sql stable security invoker set search_path = ''
as $$
    select p.profile_id, p.name, p.description, p.trades, p.services, p.coverage, p.languages, p.phone,
        p.images, p.prices, p.rating, p.review_count, p.approved_at, p.business_address, p.cover_path,
        p.business_lat, p.business_lng, f.featured, public.response_band(p.profile_id)
    from public.approved_profiles p
    cross join lateral (select public.is_featured(p.profile_id) as featured) f
    where (p_trade is null or p_trade = any(p.trades))
      and (p_id is null or p.profile_id = p_id)
    order by f.featured desc, p.name, p.profile_id;
$$;
revoke all on function public.catalogue(text, uuid) from public;
grant execute on function public.catalogue(text, uuid) to anon, authenticated;
