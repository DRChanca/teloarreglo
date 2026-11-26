-- «Tiempo de respuesta» in the search card (#80): every search candidate carries the same
-- `response_band` as the public profile (single rule: private.response_band). Fixtures use the
-- ...8000NN (professionals) and ...8001NN (clients) ranges.
begin;
create extension if not exists pgtap with schema extensions;
select plan(5);

insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000800001', 'srb-pro-1@example.org', now()),
 ('00000000-0000-0000-0000-000000800002', 'srb-pro-2@example.org', now()),
 ('00000000-0000-0000-0000-000000800101', 'srb-client-1@example.org', now());
insert into private.professional_profiles(id,owner_id,review_status) values
 ('10000000-0000-0000-0000-000000800001', '00000000-0000-0000-0000-000000800001', 'approved'),
 ('10000000-0000-0000-0000-000000800002', '00000000-0000-0000-0000-000000800002', 'approved');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 select id, 'Banda ' || right(id::text, 1), 'Ficticio', array['plumbing'], array['Fugas'],
  '[{"code":"33044","name":"Oviedo"}]', array['es'], '+34 000 000 800'
 from (values ('10000000-0000-0000-0000-000000800001'::uuid), ('10000000-0000-0000-0000-000000800002'::uuid)) v(id);
insert into private.professional_locations(profile_id, location, h3_cell) values
 ('10000000-0000-0000-0000-000000800001', 'SRID=4326;POINT(-5.85 43.36)'::extensions.geography, '86392c967ffffff'),
 ('10000000-0000-0000-0000-000000800002', 'SRID=4326;POINT(-5.86 43.36)'::extensions.geography, '86392c967ffffff');

-- Profile 1: a conversation answered in 3 min two days ago. Profile 2: no conversations.
insert into public.conversations(id, profile_id, client_id, professional_id, name) values
 ('30000000-0000-0000-0000-000000800001', '10000000-0000-0000-0000-000000800001',
  '00000000-0000-0000-0000-000000800101', '00000000-0000-0000-0000-000000800001', 'Prueba');
insert into public.chat_messages(conversation_id, author_id, request_id, body, created_at) values
 ('30000000-0000-0000-0000-000000800001', '00000000-0000-0000-0000-000000800101', gen_random_uuid(), 'Hola',
  now() - interval '2 days'),
 ('30000000-0000-0000-0000-000000800001', '00000000-0000-0000-0000-000000800001', gen_random_uuid(), 'Dime',
  now() - interval '2 days' + interval '3 minutes');

-- What the public profile shows, read as a visitor.
create temp table catalogue_bands (profile_id uuid, response_band text) on commit drop;
grant select, insert on catalogue_bands to anon, service_role;
set local role anon;
insert into catalogue_bands
 select profile_id, response_band from public.catalogue(null, '10000000-0000-0000-0000-000000800001')
 union all
 select profile_id, response_band from public.catalogue(null, '10000000-0000-0000-0000-000000800002');
reset role;

set local role service_role;
select is((select response_band from public.search_all_candidates('33044', 'plumbing')
 where profile_id = '10000000-0000-0000-0000-000000800001'), 'instant',
 'a candidate answered in 3 min carries the instant band');
select is((select response_band from public.search_all_candidates('33044', 'plumbing')
 where profile_id = '10000000-0000-0000-0000-000000800001'),
 (select response_band from catalogue_bands where profile_id = '10000000-0000-0000-0000-000000800001'),
 'with data the candidate band matches the public profile');
select is((select response_band from public.search_all_candidates('33044', 'plumbing')
 where profile_id = '10000000-0000-0000-0000-000000800002'), null,
 'a candidate without conversations has no band');
select ok((select count(*) = 1 from catalogue_bands where profile_id = '10000000-0000-0000-0000-000000800002'
 and response_band is null) and exists (select 1 from public.search_all_candidates('33044', 'plumbing')
 where profile_id = '10000000-0000-0000-0000-000000800002' and response_band is null),
 'without data neither the candidate nor the public profile has a band');
select is((select array_agg(profile_id::text order by profile_id) from public.search_all_candidates('33044', 'plumbing')
 where profile_id::text like '10000000-0000-0000-0000-0000008000%'),
 array['10000000-0000-0000-0000-000000800001', '10000000-0000-0000-0000-000000800002'],
 'the band adds a column without changing the candidates');

reset role;
select * from finish();
rollback;
