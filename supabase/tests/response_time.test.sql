-- «Tiempo de respuesta» (#76): the public profile carries `response_band`, the band of the median first
-- reply in the profile's ten most recent conversations started by the client. An unanswered conversation
-- does not count until its first message is 24 h old; from then on it weighs just above the 24 h cap.
-- Every wait is capped at 24 h. Inside one transaction now() is fixed, so elapsed time is simulated by
-- writing the messages in the past. Fixtures use the ...7600NN (professionals) and ...7601NN (clients) ranges.
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);

insert into auth.users(id,email,email_confirmed_at)
 select ('00000000-0000-0000-0000-0000007600' || lpad(n::text, 2, '0'))::uuid, 'rt-pro-' || n || '@example.org', now()
 from generate_series(1, 16) n;
insert into auth.users(id,email,email_confirmed_at)
 select ('00000000-0000-0000-0000-0000007601' || lpad(n::text, 2, '0'))::uuid, 'rt-client-' || n || '@example.org', now()
 from generate_series(1, 25) n;
insert into private.professional_profiles(id,owner_id,review_status)
 select ('10000000-0000-0000-0000-0000007600' || lpad(n::text, 2, '0'))::uuid,
  ('00000000-0000-0000-0000-0000007600' || lpad(n::text, 2, '0'))::uuid, 'approved'
 from generate_series(1, 16) n;
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 select ('10000000-0000-0000-0000-0000007600' || lpad(n::text, 2, '0'))::uuid, 'Respuesta ' || n, 'Ficticio',
  array['plumbing'], array['Fugas'], '[{"code":"33044","name":"Oviedo"}]', array['es'], '+34 000 000 760'
 from generate_series(1, 16) n;

-- One conversation of profile p with client c whose first message was written `ago` before now.
-- `reply`: delay of the professional's first message (null: no reply). `pro_first`: the professional
-- writes first and the client answers a minute later.
create function pg_temp.conv(p int, c int, ago interval, reply interval, pro_first boolean default false)
returns void language plpgsql as $$
declare
    conv_id uuid := gen_random_uuid();
    pro uuid := ('00000000-0000-0000-0000-0000007600' || lpad(p::text, 2, '0'))::uuid;
    client uuid := ('00000000-0000-0000-0000-0000007601' || lpad(c::text, 2, '0'))::uuid;
begin
    insert into public.conversations(id, profile_id, client_id, professional_id, name)
      values (conv_id, ('10000000-0000-0000-0000-0000007600' || lpad(p::text, 2, '0'))::uuid, client, pro, 'Prueba');
    if pro_first then
        insert into public.chat_messages(conversation_id, author_id, request_id, body, created_at)
          values (conv_id, pro, gen_random_uuid(), 'Hola', now() - ago),
                 (conv_id, client, gen_random_uuid(), 'Hola', now() - ago + interval '1 minute');
    else
        insert into public.chat_messages(conversation_id, author_id, request_id, body, created_at)
          values (conv_id, client, gen_random_uuid(), 'Hola', now() - ago);
        if reply is not null then
            insert into public.chat_messages(conversation_id, author_id, request_id, body, created_at)
              values (conv_id, pro, gen_random_uuid(), 'Dime', now() - ago + reply);
        end if;
    end if;
end $$;

create function pg_temp.band(p int) returns text language sql as $$
    select response_band from public.catalogue(null, ('10000000-0000-0000-0000-0000007600' || lpad(p::text, 2, '0'))::uuid);
$$;
grant execute on function pg_temp.band(int) to anon, authenticated;

-- 2: answered in 3 min.
select pg_temp.conv(2, 1, interval '2 days', interval '3 minutes');
-- 3: one very slow reply among fast ones does not sink the median.
select pg_temp.conv(3, 1, interval '3 days', interval '1 minute');
select pg_temp.conv(3, 2, interval '2 days', interval '20 hours');
select pg_temp.conv(3, 3, interval '1 day', interval '2 minutes');
-- 4: unanswered for less than 24 h does not count yet.
select pg_temp.conv(4, 1, interval '23 hours 59 minutes', null);
-- 5: unanswered for exactly 24 h counts, above the cap.
select pg_temp.conv(5, 1, interval '24 hours', null);
-- 6: a reply after 30 h counts as 24 h.
select pg_temp.conv(6, 1, interval '3 days', interval '30 hours');
-- 7: the ten most recent replied in 2 h; eleven older ones in 1 min do not count.
select pg_temp.conv(7, n, interval '1 hour' * (100 + n), interval '2 hours') from generate_series(1, 10) n;
select pg_temp.conv(7, n, interval '1 hour' * (200 + n), interval '1 minute') from generate_series(11, 21) n;
-- 8: conversations started by the professional do not count.
select pg_temp.conv(8, 1, interval '3 days', interval '3 minutes');
select pg_temp.conv(8, 2, interval '48 hours', null, true);
select pg_temp.conv(8, 3, interval '50 hours', null, true);
-- 9: only the professional's first reply counts, not later ones.
select pg_temp.conv(9, 1, interval '2 days', interval '3 minutes');
insert into public.chat_messages(conversation_id, author_id, request_id, body, created_at)
 select c.id, c.client_id, gen_random_uuid(), 'Otra cosa', now() - interval '1 day' from public.conversations c
 where c.profile_id = '10000000-0000-0000-0000-000000760009';
insert into public.chat_messages(conversation_id, author_id, request_id, body, created_at)
 select c.id, c.professional_id, gen_random_uuid(), 'Tarde', now() - interval '2 hours' from public.conversations c
 where c.profile_id = '10000000-0000-0000-0000-000000760009';
-- 10–14: each exact limit falls in the lower band; 15: just above 3 h.
select pg_temp.conv(10, 1, interval '2 days', interval '5 minutes');
select pg_temp.conv(11, 1, interval '2 days', interval '30 minutes');
select pg_temp.conv(12, 1, interval '2 days', interval '1 hour');
select pg_temp.conv(13, 1, interval '2 days', interval '3 hours');
select pg_temp.conv(14, 1, interval '2 days', interval '24 hours');
select pg_temp.conv(15, 1, interval '2 days', interval '3 hours 1 second');
-- 16: the median falls on unanswered conversations.
select pg_temp.conv(16, 1, interval '5 days', interval '10 minutes');
select pg_temp.conv(16, 2, interval '4 days', null);
select pg_temp.conv(16, 3, interval '3 days', null);

set local role anon;
select is(pg_temp.band(1), null, 'a profile without conversations has no band');
select is(pg_temp.band(2), 'instant', 'a conversation answered in 3 min is instant');
select is(pg_temp.band(3), 'instant', 'one very slow reply does not sink a fast median');
select is(pg_temp.band(4), null, 'an unanswered conversation under 24 h does not count yet');
select is(pg_temp.band(5), 'over_day', 'from 24 h an unanswered conversation counts above the cap');
select is(pg_temp.band(6), 'same_day', 'a reply after 30 h counts as 24 h');
select is(pg_temp.band(7), 'under_3h', 'only the ten most recent conversations count');
select is(pg_temp.band(8), 'instant', 'conversations started by the professional do not count');
select is(pg_temp.band(9), 'instant', 'only the first reply of the professional counts');
select is(pg_temp.band(10), 'instant', 'exactly 5 min is instant');
select is(pg_temp.band(11), 'under_30m', 'exactly 30 min is under_30m');
select is(pg_temp.band(12), 'under_1h', 'exactly 1 h is under_1h');
select is(pg_temp.band(13), 'under_3h', 'exactly 3 h is under_3h');
select is(pg_temp.band(14), 'same_day', 'exactly 24 h is same_day');
select is(pg_temp.band(15), 'same_day', 'just over 3 h is same_day');
select is(pg_temp.band(16), 'over_day', 'a median on unanswered conversations is over_day');
select ok((select (to_jsonb(c) - array['profile_id','name','description','trades','services','coverage','languages',
  'phone','images','prices','rating','review_count','approved_at','business_address','cover_path','business_lat',
  'business_lng','featured','response_band']) = '{}'::jsonb
 from public.catalogue(null, '10000000-0000-0000-0000-000000760002') c),
 'the catalogue adds only the band: no message times or client identities');
select is((select count(*)::integer from public.catalogue() c where c.name like 'Respuesta %' and c.response_band is not null),
 14, 'the full list carries the band too');
select throws_ok($$ select private.response_band('10000000-0000-0000-0000-000000760002') $$, '42501', null,
 'visitors cannot call the private computation');

reset role;
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000760002","role":"authenticated"}',true);
select is(pg_temp.band(2), 'instant', 'the owner sees the same band on their own profile');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000760199","role":"authenticated"}',true);
select is(pg_temp.band(3), 'instant', 'any account sees the band');

reset role;
select * from finish();
rollback;
