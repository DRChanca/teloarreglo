-- Services and reference prices are published directly by the owner, without review
-- (David, 2026-11-23). The rest of the profile keeps the #30 review. Fixtures use ...0041NN.
begin;
create extension if not exists pgtap with schema extensions;
select plan(25);
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data,raw_app_meta_data) values
 ('00000000-0000-0000-0000-000000004101','offer-owner@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000004102','offer-other@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000004103','offer-admin@example.org',now(),'{}','{}');
insert into private.administrators values ('00000000-0000-0000-0000-000000004103');

create temporary table t41(name text primary key, value jsonb);
grant all on t41 to authenticated;
insert into t41 values
 ('content', '{"name":"Fontanería Oferta","description":"Primera versión","trades":["plumbing"],
  "services":["Servicio de la versión"],"coverage":[{"code":"33044","name":"Oviedo"}],"languages":["es"],
  "phone":"+34 600 000 041","images":[],"prices":[]}'),
 ('prices', '[{"service":"Reparación de fuga","scope":"Una hora de mano de obra","unit":"hour","amount_cents":3500,
  "includes":"Mano de obra e IVA","excludes":"Materiales"}]'),
 ('cheaper', '[{"service":"Reparación de fuga","scope":"Una hora de mano de obra","unit":"hour","amount_cents":3000,
  "includes":"Mano de obra e IVA","excludes":""}]');

set local role anon;
select throws_ok($$select public.save_professional_offer('["Fugas"]','[]')$$,'42501',null,
 'a visitor cannot publish services or prices');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004101","role":"authenticated"}',true);
select throws_ok($$select public.save_professional_offer('["Fugas"]','[]')$$,'22023','profile_not_found',
 'publishing services needs an own profile');
insert into t41 select 'profile', to_jsonb(public.start_professional_profile()->>'profile_id');

-- Validation: the catalogue must always be able to show what is saved.
select throws_ok($$select public.save_professional_offer('["Fugas"]',
 '[{"service":"Fuga","scope":"x","unit":"lightyear","amount_cents":100,"includes":"","excludes":""}]')$$,
 '22023','invalid_profile_offer','an unknown price unit is refused');
select throws_ok($$select public.save_professional_offer('["Fugas"]',
 '[{"service":"Fuga","scope":"x","unit":"hour","amount_cents":-1,"includes":"","excludes":""}]')$$,
 '22023','invalid_profile_offer','a negative price is refused');
select throws_ok($$select public.save_professional_offer('["Fugas"]',
 '[{"service":" ","scope":"x","unit":"hour","amount_cents":100,"includes":"","excludes":""}]')$$,
 '22023','invalid_profile_offer','a price needs a service name');
select throws_ok($$select public.save_professional_offer('["Fugas"]',
 '[{"service":"Fuga","scope":"x","unit":"hour","amount_cents":100}]')$$,
 '22023','invalid_profile_offer','a price carries every field the catalogue shows');
select throws_ok($$select public.save_professional_offer('["Fugas"]',
 '[{"service":"Fuga","scope":"x","unit":"hour","amount_cents":100,"includes":"","excludes":"","owner":"x"}]')$$,
 '22023','invalid_profile_offer','unknown price fields are refused');
select throws_ok($$select public.save_professional_offer('[""]','[]')$$,'22023','invalid_profile_offer',
 'an empty service is refused');

-- Before the first approval the offer is kept and is not public.
select is(public.save_professional_offer('["Fugas","Grifería"]',(select value from t41 where name='prices'))
 ->'editable'->'services','["Fugas","Grifería"]'::jsonb,'the owner saves services before the first approval');
select is(public.my_professional_profile()->'editable'->'prices'->0->>'amount_cents','3500',
 'the owner sees the saved prices');
select is((select count(*) from public.catalogue(null,(select value#>>'{}' from t41 where name='profile')::uuid)),0::bigint,
 'an unapproved profile stays out of the catalogue');
select is(public.submit_professional_profile('20000000-0000-0000-0000-000000004101',
 (select value from t41 where name='content'))->>'status','pending','the rest of the profile is presented for review');
select is(public.my_professional_profile()->'editable'->'services','["Fugas","Grifería"]'::jsonb,
 'presenting the profile does not replace the published services');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004103","role":"authenticated"}',true);
select is(public.review_professional_profile('20000000-0000-0000-0000-000000004101',true,'Correcto')->>'status','approved',
 'administration approves the first version');
select is((select services from public.catalogue('plumbing') where profile_id=(select value#>>'{}' from t41 where name='profile')::uuid),
 array['Fugas','Grifería'],'the approved profile shows the services saved by the owner');
select is((select prices->0->>'amount_cents' from public.catalogue('plumbing')
 where profile_id=(select value#>>'{}' from t41 where name='profile')::uuid),'3500','and the saved prices');

-- After approval a price change is public at once and creates no submission.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004101","role":"authenticated"}',true);
select is(public.submit_professional_profile('20000000-0000-0000-0000-000000004102',
 (select value || '{"description":"Cambio pendiente"}' from t41 where name='content'))->>'status','pending',
 'a description change waits for review');
select lives_ok($$select public.save_professional_offer('["Fugas"]',(select value from t41 where name='cheaper'))$$,
 'the owner lowers a price');
select is((select prices->0->>'amount_cents' from public.catalogue('plumbing')
 where profile_id=(select value#>>'{}' from t41 where name='profile')::uuid),'3000','the new price is public without review');
select is((select services from public.catalogue('plumbing') where profile_id=(select value#>>'{}' from t41 where name='profile')::uuid),
 array['Fugas'],'the new services are public without review');
select is((select description from public.catalogue('plumbing') where profile_id=(select value#>>'{}' from t41 where name='profile')::uuid),
 'Primera versión','other public fields still wait for review');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004103","role":"authenticated"}',true);
select is(jsonb_array_length(public.admin_profile_submissions()),1,'changing prices created no submission');
select lives_ok($$select public.review_professional_profile('20000000-0000-0000-0000-000000004102',true,'Correcto')$$,
 'administration approves the older description change');
select is((select prices->0->>'amount_cents' from public.catalogue('plumbing')
 where profile_id=(select value#>>'{}' from t41 where name='profile')::uuid),'3000',
 'approving a version never brings back older prices');

-- Another account cannot publish for the owner.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000004102","role":"authenticated"}',true);
select throws_ok($$select public.save_professional_offer('["Gratis"]','[]')$$,'22023','profile_not_found',
 'another account cannot change the owner services');
reset role;
select * from finish();
rollback;
