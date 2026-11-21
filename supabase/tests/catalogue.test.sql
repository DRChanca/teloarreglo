begin;
create extension if not exists pgtap with schema extensions;
select plan(13);

insert into auth.users(id, email, email_confirmed_at) values
 ('00000000-0000-0000-0000-000000000014', 'catalogue-owner@example.org', now()),
 ('00000000-0000-0000-0000-000000000015', 'catalogue-other@example.org', now());
insert into private.professional_profiles(id, owner_id, private_address, draft) values
 ('10000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000014',
  'PRIVATE HOME', '{"name":"Unapproved change"}'),
 ('10000000-0000-0000-0000-000000000015', null, 'SECRET', '{"name":"Pending profile"}');
insert into public.approved_profiles(profile_id, name, description, trades, services, coverage, languages, phone, images, prices)
values ('10000000-0000-0000-0000-000000000014', 'Approved plumber', 'Public description',
 array['plumbing'], array['Leak repair'], '[{"code":"33044","name":"Oviedo"}]',
 array['es'], '+34 000 000 014', array['plumbing'], '[]');

set local role anon;
select is((select count(*) from public.catalogue()), 1::bigint, 'visitor reads approved catalogue');
select is((select name from public.catalogue('plumbing')), 'Approved plumber', 'trade filter returns approved content');
select is((select count(*) from public.catalogue('painting')), 0::bigint, 'other trade is empty');
select is((select count(*) from public.catalogue(null, '10000000-0000-0000-0000-000000000015')), 0::bigint, 'pending profile is not public even by ID');
select throws_ok('select * from private.professional_profiles', '42501', null, 'visitor cannot read private data directly');
select throws_ok($$update public.approved_profiles set name = 'Forged'$$, '42501', null, 'visitor cannot change publication');
select throws_ok($$select public.save_professional_draft('10000000-0000-0000-0000-000000000014', '{}')$$,
 '42501', null, 'visitor cannot write');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000000014","role":"authenticated"}', true);
select is(public.current_account()->>'professional_profile_id', '10000000-0000-0000-0000-000000000014', 'server identifies owned profile');
select is((public.current_account()->>'can_hire')::boolean, true, 'professional can also hire');
select lives_ok($$select public.save_professional_draft('10000000-0000-0000-0000-000000000014', '{"name":"New draft"}')$$, 'owner can submit a draft');
select is((select name from public.catalogue(null, '10000000-0000-0000-0000-000000000014')), 'Approved plumber', 'draft never replaces approved snapshot');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000000015","role":"authenticated"}', true);
select throws_ok($$select public.save_professional_draft('10000000-0000-0000-0000-000000000014', '{}')$$,
 '42501', 'permission_denied', 'another account cannot edit the profile');
select throws_ok('select * from private.professional_profiles', '42501', null, 'authenticated account cannot bypass RPC');
reset role;
select * from finish();
rollback;
