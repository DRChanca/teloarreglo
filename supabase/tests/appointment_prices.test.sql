-- A price agreed with a client never changes for that client (David, 2026-11-23): the request
-- keeps the reference prices in force when it was made and a proposal keeps its amount, whatever
-- the owner later publishes. Fixtures use the ...0042NN range.
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

insert into public.concejos(ine_code, name, boundary) values
 ('33995', 'Prueba Precios', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000004201','prices-client@example.org',now()),
 ('00000000-0000-0000-0000-000000004202','prices-pro@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000004201','00000000-0000-0000-0000-000000004202');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone,prices)
 values ('10000000-0000-0000-0000-000000004201','Prices plumber','Public',array['plumbing'],array['Fugas'],
  '[{"code":"33995","name":"Prueba Precios"}]',array['es'],'000',
  '[{"service":"Reparación de fuga","scope":"Una hora","unit":"hour","amount_cents":3500,"includes":"IVA","excludes":""}]');

-- The client asks without a proposal: the reference price in force is kept with the request.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004201","role":"authenticated"}',true);
select public.open_conversation('10000000-0000-0000-0000-000000004201');
select is(public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000004201',
 'Leaking pipe', '2030-01-15 10:00Z', '2030-01-15 12:00Z', 'Calle 1', 43.05, -5.95)->'reference_prices'->0->>'amount_cents',
 '3500', 'the request keeps the reference price in force when it is made');
select is((select reference_prices->0->>'amount_cents' from public.appointments), '3500',
 'the client reads the kept price of the request');

-- The business raises its price afterwards.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004202","role":"authenticated"}',true);
select lives_ok($$select public.save_professional_offer('["Fugas"]',
 '[{"service":"Reparación de fuga","scope":"Una hora","unit":"hour","amount_cents":9900,"includes":"IVA","excludes":""}]')$$,
 'the business changes its reference price');
select is((select prices->0->>'amount_cents' from public.catalogue(null,'10000000-0000-0000-0000-000000004201')), '9900',
 'new visitors see the new price');
select is((select reference_prices->0->>'amount_cents' from public.appointments), '3500',
 'the existing request keeps the original price');

-- The business offers an amount: the proposal keeps it, whatever the profile says later.
select is(public.propose_appointment((select id from public.appointments), 1, '40000000-0000-0000-0000-000000004201',
 'Replace trap', '2030-01-15 10:30Z', 60, 4000, null, true, true)->>'amount_cents', '4000',
 'the proposal keeps the amount offered by the business');
select lives_ok($$select public.save_professional_offer('["Fugas"]',
 '[{"service":"Reparación de fuga","scope":"Una hora","unit":"hour","amount_cents":1000,"includes":"IVA","excludes":""}]')$$,
 'the business lowers its reference price');
select is((select amount_cents from public.appointment_proposals), 4000, 'the proposal amount does not follow the profile');

-- Not even trusted SQL rewrites a kept amount.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004201","role":"authenticated"}',true);
select public.reject_proposal((select id from public.appointment_proposals));
reset role;
select throws_ok($$update public.appointments set reference_prices = '[]'$$, '42501', 'price_locked',
 'the kept reference price of a request is immutable');
select throws_ok($$update public.appointment_proposals set amount_cents = 1$$, '42501', 'price_locked',
 'the amount of a decided proposal is immutable');
select * from finish();
rollback;
