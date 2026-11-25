-- La zona de trabajo informa, no restringe (ADR 0001, 25-11-2026). Fixtures use ...0047NN.
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);

-- Three synthetic concejos (not real codes).
insert into public.concejos(ine_code, name, boundary) values
 ('33991', 'Zona Uno', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography),
 ('33992', 'Zona Dos', 'SRID=4326;MULTIPOLYGON(((-5.9 43.0,-5.8 43.0,-5.8 43.1,-5.9 43.1,-5.9 43.0)))'::extensions.geography),
 ('33993', 'Zona Tres', 'SRID=4326;MULTIPOLYGON(((-5.8 43.0,-5.7 43.0,-5.7 43.1,-5.8 43.1,-5.8 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data,raw_app_meta_data) values
 ('00000000-0000-0000-0000-000000004701','zone-owner@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000004702','zone-other@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000004703','zone-admin@example.org',now(),'{}','{}');
insert into private.administrators values ('00000000-0000-0000-0000-000000004703');

create temporary table t47(name text primary key, value jsonb);
grant all on t47 to authenticated, service_role;
insert into t47 values
 ('content', '{"name":"Fontanería Zona","description":"Primera versión","trades":["plumbing"],
  "services":["Fugas"],"coverage":[{"code":"33991","name":"Zona Uno"}],"languages":["es"],
  "phone":"+34 600 000 047","images":[],"prices":[]}');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004701","role":"authenticated"}',true);
insert into t47 select 'profile', to_jsonb(public.start_professional_profile()->>'profile_id');
select throws_ok($$select public.set_my_work_zone(array['33991','33992'])$$,'42501','not_published',
 'an unpublished profile changes its zone through review');
select public.submit_professional_profile('20000000-0000-0000-0000-000000004701', (select value from t47 where name='content'));
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004703","role":"authenticated"}',true);
select is(public.review_professional_profile('20000000-0000-0000-0000-000000004701',true,'Correcto')->>'status','approved',
 'the first version, zone included, is reviewed');

-- Without review once published.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004701","role":"authenticated"}',true);
select is(public.set_my_work_zone(array['33992','33991']),
 '[{"code":"33992","name":"Zona Dos"},{"code":"33991","name":"Zona Uno"}]'::jsonb,
 'the owner adds a concejo and gets the zone in force');
select is((select coverage from public.catalogue('plumbing') where profile_id=(select value#>>'{}' from t47 where name='profile')::uuid),
 '[{"code":"33992","name":"Zona Dos"},{"code":"33991","name":"Zona Uno"}]'::jsonb,'the public profile shows it at once');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004703","role":"authenticated"}',true);
select is(jsonb_array_length(public.admin_profile_submissions('all')),1,'changing the zone creates no submission');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004701","role":"authenticated"}',true);
select is(jsonb_array_length(public.set_my_work_zone(array['33991','33992','33992'])),2,'repeated codes count once');
select throws_ok($$select public.set_my_work_zone(array[]::text[])$$,'22023','zone_empty','the zone keeps at least one concejo');
select throws_ok($$select public.set_my_work_zone(array[null]::text[])$$,'22023','zone_empty','null codes are no concejo');
select throws_ok($$select public.set_my_work_zone(array['33991','33000'])$$,'22023','unknown_concejo',
 'only known concejos of Asturias');
select is(jsonb_array_length(public.set_my_work_zone(array['33992'])),1,'the owner removes a concejo without review');

-- Another account has no profile to change.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004702","role":"authenticated"}',true);
select throws_ok($$select public.set_my_work_zone(array['33991'])$$,'42501','not_published','only the owner of a published profile');
set local role anon;
select throws_ok($$select public.set_my_work_zone(array['33991'])$$,'42501',null,'a visitor cannot change any zone');

-- Approving a later version keeps the zone in force.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004701","role":"authenticated"}',true);
select public.submit_professional_profile('20000000-0000-0000-0000-000000004702',
 (select value || '{"description":"Segunda versión"}' from t47 where name='content'));
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004703","role":"authenticated"}',true);
select public.review_professional_profile('20000000-0000-0000-0000-000000004702',true,'Correcto');
select is((select description || ' ' || (coverage->0->>'code') from public.catalogue('plumbing')
 where profile_id=(select value#>>'{}' from t47 where name='profile')::uuid),'Segunda versión 33992',
 'an approval publishes the new content and never undoes a zone change');

-- Administration keeps the trail.
select is(jsonb_array_length(public.admin_work_zone_changes((select value#>>'{}' from t47 where name='profile')::uuid)),2,
 'one entry per added or removed concejo');
select is(public.admin_work_zone_changes((select value#>>'{}' from t47 where name='profile')::uuid)->0
 - 'changed_at','{"ine_code":"33991","name":"Zona Uno","added":false}'::jsonb,'latest first: Zona Uno removed');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004701","role":"authenticated"}',true);
select throws_ok($$select public.admin_work_zone_changes((select value#>>'{}' from t47 where name='profile')::uuid)$$,
 '42501','permission_denied','the owner does not read the panel trail');
reset role;
select throws_ok($$delete from private.work_zone_changes$$,'42501','audit_immutable','the trail cannot be erased');

-- Search: every approved, located profile, flagged in or out of the zone.
insert into private.professional_locations(profile_id, location, h3_cell)
 select (value#>>'{}')::uuid, 'SRID=4326;POINT(-5.85 43.05)'::extensions.geography, '86392c967ffffff' from t47 where name='profile';
set local role service_role;
select is((select in_zone from public.search_all_candidates('33992','plumbing')
 where profile_id=(select value#>>'{}' from t47 where name='profile')::uuid),true,'in zone where it works');
select is((select in_zone from public.search_all_candidates('33993','plumbing')
 where profile_id=(select value#>>'{}' from t47 where name='profile')::uuid),false,'listed, but out of zone, elsewhere');
select is((select count(*) from public.search_all_candidates('33993','electrical')
 where profile_id=(select value#>>'{}' from t47 where name='profile')::uuid),0::bigint,'the trade still filters');
set local role anon;
select throws_ok($$select * from public.search_all_candidates('33992',null)$$,'42501',null,
 'visitors never read locations through the search');

select * from finish();
rollback;
