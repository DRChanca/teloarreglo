begin;
create extension if not exists pgtap with schema extensions;
select plan(3);

insert into private.professional_profiles(id, review_status)
values ('10000000-0000-0000-0000-000000003601', 'approved');

select lives_ok($$insert into public.approved_profiles(profile_id, name, description, trades, services,
  coverage, languages, phone, images)
 values ('10000000-0000-0000-0000-000000003601', 'Cleaning test', 'Public', array['cleaning'],
  array['Limpieza de hogar'], '[{"code":"33044","name":"Oviedo"}]', array['es'], '+34 000 000 361',
  array['cleaning'])$$, 'cleaning is an accepted trade');

select is((select name from public.catalogue('cleaning')), 'Cleaning test', 'catalogue filters by cleaning');

select throws_ok($$insert into private.professional_profiles(id) values ('10000000-0000-0000-0000-000000003602');
 insert into public.approved_profiles(profile_id, name, description, trades, services, coverage, languages, phone)
 values ('10000000-0000-0000-0000-000000003602', 'Unknown', '', array['gardening'], '{}', '[]', '{es}', '000')$$,
 '23514', null, 'unknown trades are still rejected');

select * from finish();
rollback;
