-- Spanish home address fields (#41): número, bloque, portal, escalera, piso, puerta and locality are kept
-- beside the readable line so the owner can edit them again. Fixtures use the ...4100NN range.
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

insert into auth.users(id, email, email_confirmed_at) values
 ('00000000-0000-0000-0000-000000410001', 'parts-owner@example.org', now()),
 ('00000000-0000-0000-0000-000000410002', 'parts-other@example.org', now());

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000410001","role":"authenticated"}', true);

select is((select address_parts from public.set_home_address('Calle Uría 10, 4º B, 33003 Oviedo', 43.36, -5.85, 'pin',
  null, '{"street":" Calle Uría ","number":"10","floor":"4","door":"B","locality":"33003 Oviedo","portal":""}')),
 '{"street":"Calle Uría","number":"10","floor":"4","door":"B","locality":"33003 Oviedo"}'::jsonb,
 'the fields are saved trimmed, without empty ones');
select is((select address || ' | ' || (address_parts->>'door') from public.home_address()),
 'Calle Uría 10, 4º B, 33003 Oviedo | B', 'the owner reads the line and the fields back');

-- Older clients that only send the line keep working, and the old fields are not kept with a new line.
select is((select address_parts from public.set_home_address('Calle Uría 12', 43.36, -5.85, 'pin', null)), null::jsonb,
 'saving without fields leaves none');
select is((select count(*) from public.home_address()), 1::bigint, 'still one home per account');

select throws_ok($$select * from public.set_home_address('Calle Uría 1', 43.36, -5.85, 'pin', null, '{"number":"1"}')$$,
 '22023', 'invalid_home_address', 'the street is required');
select throws_ok($$select * from public.set_home_address('Calle Uría 1', 43.36, -5.85, 'pin', null,
 '{"street":"Calle Uría","floor":"123456789012345678901"}')$$, '22023', 'invalid_home_address',
 'a detail longer than 20 characters is rejected');
select throws_ok($$select * from public.set_home_address('Calle Uría 1', 43.36, -5.85, 'pin', null,
 '{"street":"Calle Uría","notes":"llamar"}')$$, '22023', 'invalid_home_address', 'unknown keys are rejected');
select throws_ok($$select * from public.set_home_address('Calle Uría 1', 43.36, -5.85, 'pin', null,
 '{"street":"Calle Uría","number":10}')$$, '22023', 'invalid_home_address', 'every field is text');

-- Another account never sees them.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000410002","role":"authenticated"}', true);
select is((select count(*) from public.home_address()), 0::bigint, 'another account reads no home');

select * from finish();
rollback;
