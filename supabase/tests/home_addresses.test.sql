-- Private home address (#37). Fixtures use the ...3700NN range and synthetic concejo 33937.
begin;
create extension if not exists pgtap with schema extensions;
select plan(22);

insert into public.concejos(ine_code, name, boundary) values
 ('33937', 'Prueba Casa', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id, email, email_confirmed_at) values
 ('00000000-0000-0000-0000-000000370001', 'home-owner@example.org', now()),
 ('00000000-0000-0000-0000-000000370002', 'home-other@example.org', now());

-- Visitors have no access at all.
set local role anon;
select throws_ok($$select * from public.home_address()$$, '42501', null, 'visitors cannot read a home');
select throws_ok($$select * from public.set_home_address('Calle Falsa 1', 43.05, -5.95, 'pin', null)$$, '42501', null,
 'visitors cannot save a home');
select throws_ok($$select * from public.home_addresses$$, '42501', null, 'visitors cannot select the table');
reset role;

-- A session without a subject is not an account.
set local role authenticated;
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select throws_ok($$select * from public.set_home_address('Calle Falsa 1', 43.05, -5.95, 'pin', null)$$, '28000',
 'authentication_required', 'saving requires a signed-in account');

-- Owner: no home yet, then saves one. The server resolves the concejo from the point.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000370001","role":"authenticated"}', true);
select is((select count(*) from public.home_address()), 0::bigint, 'a new account has no home');
select is((select ine_code || ' ' || concejo_name from public.set_home_address(
  '  Calle Prueba 1, 2ºA  ', 43.05, -5.95, 'places', 'place-37')), '33937 Prueba Casa',
 'saving returns the concejo that contains the point');
select is((select row(address, lat, lng, source, place_id)::text from public.home_address()),
 row('Calle Prueba 1, 2ºA', 43.05::double precision, -5.95::double precision, 'places', 'place-37')::text,
 'the owner reads text, coordinates, source and place id back (address trimmed)');
select is((select count(*) from public.home_addresses), 1::bigint, 'the owner can select their own row');

-- Saving again replaces the single home; a pin or GPS home never keeps a Places id.
select is((select place_id from public.set_home_address('Calle Prueba 3', 43.06, -5.94, 'pin', 'stale-place')), null,
 'a pin home drops any Places id');
select is((select count(*) from public.home_address()), 1::bigint, 'still one home per account');
select is((select ine_code from public.set_home_address('Borde 1', 43.05, -5.8985, 'gps', null)), '33937',
 'a point within 250 m of a concejo resolves to it');
select is((select ine_code from public.set_home_address('Madrid 1', 40.4168, -3.7038, 'gps', null)), null,
 'a home outside every concejo is kept without concejo');

-- Invalid input is rejected with a stable error.
select throws_ok($$select * from public.set_home_address('   ', 43.05, -5.95, 'pin', null)$$, '22023',
 'invalid_home_address', 'a blank address is rejected');
select throws_ok($$select * from public.set_home_address('Calle Prueba 1', 91, -5.95, 'pin', null)$$, '22023',
 'invalid_home_address', 'an impossible latitude is rejected');
select throws_ok($$select * from public.set_home_address('Calle Prueba 1', 43.05, -5.95, 'satellite', null)$$, '22023',
 'invalid_home_address', 'an unknown source is rejected');
select throws_ok($$select * from public.set_home_address('Calle Prueba 1', 43.05, -5.95, 'places', null)$$, '22023',
 'invalid_home_address', 'a Places home needs its place id');

-- No direct writes, not even by the owner.
select throws_ok($$insert into public.home_addresses(user_id, address, lat, lng, source)
 values ('00000000-0000-0000-0000-000000370001', 'Directo 1', 43.05, -5.95, 'pin')$$, '42501', null,
 'the owner cannot insert directly');
select throws_ok($$update public.home_addresses set address = 'Otra 1'$$, '42501', null, 'the owner cannot update directly');

-- Another account sees nothing and cannot touch the owner's home.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000370002","role":"authenticated"}', true);
select is((select count(*) from public.home_addresses), 0::bigint, 'another account cannot read the owner''s home');
select is((select count(*) from public.home_address()), 0::bigint, 'another account only reads its own (none)');
select throws_ok($$delete from public.home_addresses$$, '42501', null, 'another account cannot delete homes');
reset role;

-- Deleting the account deletes its home.
delete from auth.users where id = '00000000-0000-0000-0000-000000370001';
select is((select count(*) from public.home_addresses where user_id = '00000000-0000-0000-0000-000000370001'), 0::bigint,
 'the home goes away with its account');

select * from finish();
rollback;
