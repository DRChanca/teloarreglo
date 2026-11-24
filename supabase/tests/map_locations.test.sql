-- #17: public commercial map point and manual concejo anchors.
begin;
create extension if not exists pgtap with schema extensions;
select plan(11);

-- Two synthetic adjoining concejos (not real codes), separate from search_location's.
insert into public.concejos(ine_code, name, boundary) values
 ('33996', 'Mapa Oeste', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography),
 ('33997', 'Mapa Este',  'SRID=4326;MULTIPOLYGON(((-5.9 43.0,-5.8 43.0,-5.8 43.1,-5.9 43.1,-5.9 43.0)))'::extensions.geography);

insert into private.professional_profiles(id, private_address) values
 ('00000000-0000-0000-0000-000000001701', 'PRIVATE HOME 1701'),
 ('00000000-0000-0000-0000-000000001702', 'PRIVATE HOME 1702');
insert into public.approved_profiles
 (profile_id, name, description, trades, services, coverage, languages, phone, business_address, business_lat, business_lng)
values
 ('00000000-0000-0000-0000-000000001701', 'Mapped shop', '', array['plumbing'], array['Fugas'], '[]', array['es'],
  '+34 000 001 701', 'Calle Gascona, local ficticio, Oviedo', 43.3655, -5.8440),
 ('00000000-0000-0000-0000-000000001702', 'No premises', '', array['painting'], array['Pintura'], '[]', array['es'],
  '+34 000 001 702', null, null, null);

-- Public commercial point: only together with an approved commercial address.
set local role anon;
select is((select array[business_lat, business_lng] from public.catalogue(null, '00000000-0000-0000-0000-000000001701')),
 array[43.3655, -5.8440]::double precision[], 'approved commercial point is public in the catalogue');
select is((select business_lat from public.catalogue(null, '00000000-0000-0000-0000-000000001702')), null,
 'no commercial premises means no map point, never a private fallback');
select ok((select not (to_jsonb(p) ? 'private_address') from public.catalogue(null, '00000000-0000-0000-0000-000000001701') p),
 'the mapped profile exposes no private address field');
reset role;

select throws_ok($$update public.approved_profiles set business_lat = 43.3, business_lng = -5.8
 where profile_id = '00000000-0000-0000-0000-000000001702'$$, '23514', null,
 'a map point without an approved commercial address is rejected');
select throws_ok($$update public.approved_profiles set business_lng = null
 where profile_id = '00000000-0000-0000-0000-000000001701'$$, '23514', null, 'half a point is rejected');
select throws_ok($$update public.approved_profiles set business_lat = 91
 where profile_id = '00000000-0000-0000-0000-000000001701'$$, '23514', null, 'an impossible latitude is rejected');

update public.approved_profiles set business_address = 'Calle Uría, local ficticio, Oviedo'
 where profile_id = '00000000-0000-0000-0000-000000001701';
select is((select business_lat from public.approved_profiles where profile_id = '00000000-0000-0000-0000-000000001701'), null,
 'changing the approved address drops the old point instead of pinning the wrong place');
update public.approved_profiles set business_address = 'Calle Gascona, local ficticio, Oviedo', business_lat = 43.3655, business_lng = -5.8440
 where profile_id = '00000000-0000-0000-0000-000000001701';
select is((select business_lng from public.approved_profiles where profile_id = '00000000-0000-0000-0000-000000001701'), -5.8440::double precision,
 'a reviewed update can set address and point together');

-- Manual concejo choice: a representative point inside each concejo, without the boundary.
set local role anon;
select is((select array_agg(ine_code order by name) from public.concejo_anchors() where ine_code in ('33996', '33997')),
 array['33997', '33996'], 'visitors list concejos for the manual choice, ordered by name');
reset role;
select ok((select bool_and(extensions.st_covers(c.boundary,
            extensions.st_setsrid(extensions.st_makepoint(a.lng, a.lat), 4326)::extensions.geography))
           from public.concejo_anchors() a join public.concejos c using (ine_code) where a.ine_code in ('33996', '33997')),
 'each anchor lies inside its own concejo');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000001799","role":"authenticated"}', true);
select is((select count(*) from public.concejo_anchors() where ine_code in ('33996', '33997')), 2::bigint,
 'signed-in accounts get the same anchors');
reset role;

select * from finish();
rollback;
