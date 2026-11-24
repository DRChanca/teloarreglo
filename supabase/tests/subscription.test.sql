-- Simulated optional subscription (#33): no charge, no bank data, free profile untouched,
-- "Destacado" zones limited to the approved coverage.
begin;
create extension if not exists pgtap with schema extensions;
select plan(22);
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data,raw_app_meta_data) values
 ('00000000-0000-0000-0000-000000003301','subscription-pro@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000003302','subscription-client@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000003303','subscription-draft@example.org',now(),'{}','{}');
insert into private.professional_profiles(id,owner_id,review_status) values
 ('10000000-0000-0000-0000-000000003301','00000000-0000-0000-0000-000000003301','approved'),
 ('10000000-0000-0000-0000-000000003303','00000000-0000-0000-0000-000000003303','pending');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone,rating,review_count) values
 ('10000000-0000-0000-0000-000000003301','Fontanería Suscripción','Ficticio',array['plumbing'],array['Fugas'],
  '[{"code":"33044","name":"Oviedo"},{"code":"33024","name":"Gijón"}]',array['es'],'+34 000 000 033',4.50,2);

-- The table has no place for bank, card, invoice or renewal data.
select columns_are('private','subscription_simulations',array['profile_id','active','updated_at'],
 'the simulation stores no bank, card, invoice or renewal data');

set local role anon;
select throws_ok($$select public.my_subscription()$$,'42501',null,'visitors cannot read a subscription');
select throws_ok($$select public.simulate_subscription(true)$$,'42501',null,'visitors cannot simulate');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003302","role":"authenticated"}',true);
select is(public.my_subscription(),null,'an account without professional profile has no subscription scene');
select throws_ok($$select public.simulate_subscription(true)$$,'42501','professional_profile_required',
 'only professionals can simulate the subscription');
select throws_ok($$select * from private.subscription_simulations$$,'42501',null,'simulations are not readable directly');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003301","role":"authenticated"}',true);
select is(public.my_subscription()->>'simulated','false','a professional starts on the free profile');
select is((public.my_subscription()->>'price_cents')::int,500,'the proposed price is 5 € in integer cents');
select is(public.my_subscription()->>'currency','EUR','the price is in euros');
select is(public.my_subscription()->>'period','month','the price is per month');
select is(public.my_subscription()->>'charged','false','nothing is ever charged');
select is(public.my_subscription()->'featured_zones',
 '[{"code":"33044","name":"Oviedo"},{"code":"33024","name":"Gijón"}]'::jsonb,
 'Destacado zones are exactly the approved coverage');
select throws_ok($$select public.simulate_subscription(null)$$,'22023','invalid_choice','a missing choice is rejected');

select is(public.simulate_subscription(true)->>'simulated','true','the professional can try the simulation');
select is(public.simulate_subscription(true)->>'charged','false','repeating the simulation is idempotent and free');
select is((select count(*) from public.catalogue(null,'10000000-0000-0000-0000-000000003301')),1::bigint,
 'the simulated subscriber keeps the same public profile');
select is((select row(rating,review_count)::text from public.catalogue(null,'10000000-0000-0000-0000-000000003301')),
 '(4.50,2)','the subscription does not change reviews or rating');
select is(public.simulate_subscription(false)->>'simulated','false','the simulation can be cancelled at any time');
select is((select count(*) from public.catalogue('plumbing') where name='Fontanería Suscripción'),1::bigint,
 'a free professional stays listed in the catalogue');

reset role;
select is((select review_status from private.professional_profiles where id='10000000-0000-0000-0000-000000003301'),
 'approved','simulating does not buy or change the review/verification status');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003303","role":"authenticated"}',true);
select is(public.my_subscription()->'featured_zones','[]'::jsonb,'an unpublished profile has no Destacado zones');
select is(public.simulate_subscription(true)->'featured_zones','[]'::jsonb,
 'subscribing does not publish an unreviewed profile in any zone');

select * from finish();
rollback;
