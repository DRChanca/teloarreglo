begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

-- Two synthetic adjoining concejos (not real codes): A west of lon -5.9, B east of it.
insert into public.concejos(ine_code, name, boundary) values
 ('33998', 'Prueba Oeste', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography),
 ('33999', 'Prueba Este',  'SRID=4326;MULTIPOLYGON(((-5.9 43.0,-5.8 43.0,-5.8 43.1,-5.9 43.1,-5.9 43.0)))'::extensions.geography);

insert into private.professional_profiles(id, owner_id, private_address) values
 ('20000000-0000-0000-0000-000000000001', null, 'PRIVATE HOME A'),
 ('20000000-0000-0000-0000-000000000002', null, 'PRIVATE HOME B'),
 ('20000000-0000-0000-0000-000000000003', null, 'PENDING');
insert into public.approved_profiles(profile_id, name, description, trades, services, coverage, languages, phone, images, prices) values
 ('20000000-0000-0000-0000-000000000001', 'Covers west', 'd', array['plumbing'], array['s'],
  '[{"code":"33998","name":"Prueba Oeste"}]', array['es'], '+34 000 000 101', array['plumbing'], '[]'),
 ('20000000-0000-0000-0000-000000000002', 'Covers east', 'd', array['plumbing'], array['s'],
  '[{"code":"33999","name":"Prueba Este"}]', array['es'], '+34 000 000 102', array['plumbing'], '[]');
-- Profile 3 has a location but no approved version: never a candidate.
insert into private.professional_locations(profile_id, location, h3_cell) values
 ('20000000-0000-0000-0000-000000000001', 'SRID=4326;POINT(-5.95 43.05)'::extensions.geography, '86392c95fffffff'),
 ('20000000-0000-0000-0000-000000000002', 'SRID=4326;POINT(-5.85 43.05)'::extensions.geography, '86392c967ffffff'),
 ('20000000-0000-0000-0000-000000000003', 'SRID=4326;POINT(-5.95 43.06)'::extensions.geography, '86392c95fffffff');

set local role anon;
select is((select count(*) from public.resolve_concejo(43.05, -5.95)), 1::bigint, 'point well inside resolves to one concejo');
select is((select ine_code from public.resolve_concejo(43.05, -5.95)), '33998', 'resolves the containing concejo');
select is((select distance_m from public.resolve_concejo(43.05, -5.95)), 0::double precision, 'inside means distance 0');
select is((select count(*) from public.resolve_concejo(43.05, -5.9005)), 2::bigint, 'near the border both concejos are offered');
select is((select ine_code from public.resolve_concejo(43.05, -5.9005) limit 1), '33998', 'containing concejo comes first');
select is((select count(*) from public.resolve_concejo(40.4, -3.7)), 0::bigint, 'outside Asturias resolves nothing');
select throws_ok('select * from public.resolve_concejo(95, -5.9)', '22023', 'invalid_location', 'invalid latitude is rejected');
select is((select count(*) from public.concejos), 2::bigint, 'visitors can list concejo names');
select throws_ok('select boundary from public.concejos', '42501', null, 'visitors cannot download boundaries directly');
select throws_ok('select * from private.professional_locations', '42501', null, 'visitors cannot read private locations');
select throws_ok($$select * from public.search_candidates('33998', 'plumbing')$$, '42501', null,
 'visitors cannot call the server-only candidate lookup');

set local role authenticated;
select throws_ok($$select * from public.search_candidates('33998', 'plumbing')$$, '42501', null,
 'accounts cannot call the server-only candidate lookup');

set local role service_role;
select is((select ine_code from public.resolve_concejo(43.05, -5.95)), '33998',
 'the search function (service_role) can resolve concejos');
select is((select name from public.concejos where ine_code = '33999'), 'Prueba Este',
 'the search function (service_role) can validate a manually chosen concejo');
select is((select array_agg(profile_id::text) from public.search_candidates('33998', 'plumbing')),
 array['20000000-0000-0000-0000-000000000001'], 'only approved profiles covering the concejo are candidates');
select is((select count(*) from public.search_candidates('33998', 'painting')), 0::bigint, 'trade filter applies');
select is((select count(*) from public.search_candidates('33999', 'plumbing', array['86392c95fffffff'])), 0::bigint,
 'cell filter limits the lookup');
select is((select lat from public.search_candidates('33999', 'plumbing')), 43.05::double precision,
 'server receives the exact location to order by distance');
select is(public.count_candidates('33998', 'plumbing'), 1::bigint, 'count matches covering approved profiles');
reset role;
set local role anon;
select throws_ok($$select public.count_candidates('33998', 'plumbing')$$, '42501', null, 'visitors cannot count candidates');

reset role;
select * from finish();
rollback;
