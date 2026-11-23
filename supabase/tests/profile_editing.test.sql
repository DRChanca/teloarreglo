-- Owner presents, corrects and changes a professional profile (#30): the approved version stays
-- public until a newer one is approved, and only the owner edits their own data.
begin;
create extension if not exists pgtap with schema extensions;
select plan(42);
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data,raw_app_meta_data) values
 ('00000000-0000-0000-0000-000000003001','editing-owner@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000003002','editing-other@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000003003','editing-admin@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000003004','editing-client@example.org',now(),'{}','{}');
insert into private.administrators values ('00000000-0000-0000-0000-000000003003');

create temporary table t30(name text primary key, value jsonb);
grant all on t30 to authenticated;
insert into t30 values ('first', '{"name":"Fontanería Prueba","description":"Primera versión","trades":["plumbing"],
 "services":["Fugas"],"coverage":[{"code":"33044","name":"Oviedo"}],"languages":["es"],"phone":"+34 600 000 030",
 "images":[],"prices":[]}');

-- Visitors cannot present or read anything.
set local role anon;
select throws_ok($$select public.start_professional_profile()$$,'42501',null,'a visitor cannot start a profile');
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003001',
 (select value from t30 where name='first'))$$,'42501',null,'a visitor cannot present a profile');
select throws_ok($$select public.my_professional_profile()$$,'42501',null,'a visitor cannot read an own profile');

-- The same hiring account offers services.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003001","role":"authenticated"}',true);
select is(public.my_professional_profile(),null,'an account starts without a professional profile');
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003001',
 (select value from t30 where name='first'))$$,'22023','profile_not_found','presenting needs an own profile');
select ok(public.start_professional_profile()->>'profile_id' is not null,'the account starts its professional profile');
select is(public.start_professional_profile()->>'profile_id',public.current_account()->>'professional_profile_id',
 'starting again returns the same profile, linked to the account');
select is((public.current_account()->>'can_hire')::boolean,true,'the account can still hire');
insert into t30 select 'profile', to_jsonb(public.my_professional_profile()->>'profile_id');
select is(public.my_professional_profile()->'review'->>'status',null,'nothing is presented yet');
select is((public.my_professional_profile()->'review'->>'published')::boolean,false,'nothing is published yet');

-- Content is validated against what the catalogue can publish.
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003009',
 (select value || '{"private_address":"Calle privada 30"}' from t30 where name='first'))$$,
 '22023','invalid_profile_content','a private address cannot be presented as public content');
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003009',
 (select value || '{"name":"  "}' from t30 where name='first'))$$,'22023','invalid_profile_content','a name is required');
select throws_ok($$select public.submit_profile_version('20000000-0000-0000-0000-000000003009',
 (select value || '{"trades":["astrology"]}' from t30 where name='first'))$$,'42883',null,
 'the trusted entry point stays private');
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003009',
 (select value || '{"trades":["astrology"]}' from t30 where name='first'))$$,'22023','invalid_profile_content',
 'unknown trades are refused');
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003009',
 (select value || '{"coverage":[]}' from t30 where name='first'))$$,'22023','invalid_profile_content',
 'at least one concejo is covered');
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003009',
 (select value || '{"phone":""}' from t30 where name='first'))$$,'22023','invalid_profile_content','a phone is required');

-- Private address is the owner's alone and never part of a version.
select is(public.save_professional_private_address('Calle privada 30')->>'private_address','Calle privada 30',
 'owner keeps a private address');

-- First presentation, idempotent by its request id.
select is(public.submit_professional_profile('20000000-0000-0000-0000-000000003001',
 (select value from t30 where name='first'))->>'status','pending','owner presents the profile for review');
select is(public.submit_professional_profile('20000000-0000-0000-0000-000000003001',
 (select value from t30 where name='first'))->>'version',
 public.my_professional_profile()->'review'->>'version','a lost response is retried without a new version');
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003001',
 (select value || '{"description":"otra"}' from t30 where name='first'))$$,'22023','request_conflict',
 'a request id cannot carry a different intention');
select is((select count(*) from public.catalogue(null,(select value#>>'{}' from t30 where name='profile')::uuid)),0::bigint,
 'a pending first version is not public');

-- Third parties cannot read nor change the owner's profile.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003002","role":"authenticated"}',true);
select is(public.my_professional_profile(),null,'another account does not see the owner profile');
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000003001',
 (select value from t30 where name='first'))$$,'22023','profile_not_found','another account cannot present for the owner');
select throws_ok($$select public.save_professional_draft((select value#>>'{}' from t30 where name='profile')::uuid,'{}')$$,
 '42501','permission_denied','another account cannot write the owner draft');
select throws_ok($$select * from private.professional_profiles$$,'42501',null,'profiles are not readable directly');

-- Administration rejects the presented version; the owner corrects and presents again.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003003","role":"authenticated"}',true);
select ok(position('Calle privada 30' in public.admin_profile_submissions('all')::text)=0,
 'the panel never receives the private address');
select is(public.review_professional_profile('20000000-0000-0000-0000-000000003001',false,'Falta la zona de Gijón')->>'status',
 'rejected','administration rejects with a reason');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003001","role":"authenticated"}',true);
select is(public.my_professional_profile()->'review'->>'reason','Falta la zona de Gijón','owner reads the reason');
select is(public.my_professional_profile()->'editable'->>'description','Primera versión',
 'owner corrects starting from the rejected version');
select is(public.submit_professional_profile('20000000-0000-0000-0000-000000003002',
 (select value || '{"coverage":[{"code":"33044","name":"Oviedo"},{"code":"33024","name":"Gijón"}]}' from t30 where name='first'))->>'status',
 'pending','owner presents a corrected version');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003003","role":"authenticated"}',true);
select is(public.review_professional_profile('20000000-0000-0000-0000-000000003002',true,'Correcto')->>'status',
 'approved','administration approves the correction');

-- A conversation with a client exists before the profile changes.
reset role;
insert into public.conversations(profile_id,client_id,professional_id,name)
 select (value#>>'{}')::uuid,'00000000-0000-0000-0000-000000003004','00000000-0000-0000-0000-000000003001','Fontanería Prueba'
 from t30 where name='profile';
set local role authenticated;

-- A public change of an approved profile stays pending while the approved version is shown.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003001","role":"authenticated"}',true);
select is(public.submit_professional_profile('20000000-0000-0000-0000-000000003003',
 (select value || '{"description":"Cambio pendiente","coverage":[{"code":"33044","name":"Oviedo"}]}' from t30 where name='first'))->>'status',
 'pending','owner presents a public change');
select is(public.my_professional_profile()->'public'->>'description','Primera versión','owner still sees the approved version as public');
select is(public.my_professional_profile()->'editable'->>'description','Cambio pendiente','owner edits from the pending change');
select is((select description from public.catalogue('plumbing') where profile_id=(select value#>>'{}' from t30 where name='profile')::uuid),
 'Primera versión','the catalogue keeps the approved version while the change is pending');
select is(public.save_agenda(60,'[{"weekday":1,"start":"09:00","end":"14:00"}]')->>'default_duration_minutes','60',
 'the agenda changes without review');
select is(public.my_professional_profile()->'review'->>'description',null,'the review reply never carries content');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000003003","role":"authenticated"}',true);
select is(jsonb_array_length(public.admin_profile_submissions()),1,'the agenda change created no submission');
select is(public.admin_profile_submissions()->0->'public_content'->>'description','Primera versión',
 'administration compares the change with the public version');
select is(public.review_professional_profile('20000000-0000-0000-0000-000000003003',false,'Descripción poco clara')->>'published',
 'true','rejecting a change keeps the profile published');
select is((select description from public.catalogue('plumbing') where profile_id=(select value#>>'{}' from t30 where name='profile')::uuid),
 'Primera versión','after a rejected change the approved version remains public');
reset role;
select is((select count(*) from public.conversations where client_id='00000000-0000-0000-0000-000000003004'),1::bigint,
 'reviews never remove conversations');
select * from finish();
rollback;
