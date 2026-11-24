-- Profile review from the private panel (#29): authorization, publication, audit and owner notice.
begin;
create extension if not exists pgtap with schema extensions;
select plan(39);
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data,raw_app_meta_data) values
 ('00000000-0000-0000-0000-000000002901','review-owner@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000002902','review-other@example.org',now(),
  '{"is_administrator":true,"role":"admin"}','{"role":"admin","is_administrator":true}'),
 ('00000000-0000-0000-0000-000000002903','review-admin@example.org',now(),'{}','{}');
insert into private.administrators values ('00000000-0000-0000-0000-000000002903');
insert into private.professional_profiles(id,owner_id,private_address) values
 ('10000000-0000-0000-0000-000000002901','00000000-0000-0000-0000-000000002901','Calle privada 1');

-- Trusted SQL (prepare_review_profile.py) submits the fictional test profile.
select is(private.submit_profile_version('10000000-0000-0000-0000-000000002901','20000000-0000-0000-0000-000000002901',
 '{"name":"Perfil de prueba","description":"Ficticio","trades":["plumbing"],"services":["Fugas"],
   "coverage":[{"code":"33044","name":"Oviedo"}],"languages":["es"],"phone":"+34 000 000 029",
   "images":["plumbing"],"prices":[]}'::jsonb)->>'status','pending','trusted SQL submits a pending version');
select throws_ok($$select private.submit_profile_version('10000000-0000-0000-0000-000000002901','20000000-0000-0000-0000-000000002909',
 '{"name":"X","private_address":"Calle privada 1"}'::jsonb)$$,'22023','invalid_profile_content',
 'a submission cannot carry private or unknown fields');
select is((select count(*) from public.catalogue(null,'10000000-0000-0000-0000-000000002901')),0::bigint,
 'a pending profile is not in the catalogue');
select is((select count(*) from public.catalogue('plumbing') where name='Perfil de prueba'),0::bigint,
 'a pending profile is not listed by trade');

-- Visitors and normal accounts, even with admin-looking metadata, have no access.
set local role anon;
select throws_ok($$select public.admin_profile_submissions()$$,'42501',null,'visitors cannot list submissions');
select throws_ok($$select public.review_professional_profile('20000000-0000-0000-0000-000000002901',true,'ok')$$,
 '42501',null,'visitors cannot review');
set local role authenticated;
select set_config('request.jwt.claims',
 '{"sub":"00000000-0000-0000-0000-000000002902","role":"authenticated","app_metadata":{"role":"admin"},"user_metadata":{"is_administrator":true}}',true);
select is((public.current_account()->>'is_administrator')::boolean,false,'metadata never grants administration');
select throws_ok($$select public.admin_profile_submissions()$$,'42501','permission_denied',
 'a normal account cannot list submissions');
select throws_ok($$select public.review_professional_profile('20000000-0000-0000-0000-000000002901',true,'ok')$$,
 '42501','permission_denied','a normal account cannot approve by calling the RPC directly');
select throws_ok($$select * from private.profile_submissions$$,'42501',null,'submissions are not readable directly');
select throws_ok($$select * from private.profile_decisions$$,'42501',null,'decisions are not readable directly');
select throws_ok($$insert into private.administrators values ('00000000-0000-0000-0000-000000002902')$$,
 '42501',null,'nobody can grant themselves administration');
select is(public.my_profile_review(),null,'an account without profile has no review');

-- Owner sees the pending state and cannot review their own profile.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000002901","role":"authenticated"}',true);
select is(public.my_profile_review()->>'status','pending','owner sees the submission is pending');
select is((public.my_profile_review()->>'published')::boolean,false,'owner sees it is not published');
select throws_ok($$select public.review_professional_profile('20000000-0000-0000-0000-000000002901',true,'ok')$$,
 '42501','permission_denied','owner cannot self-approve');

-- Administrator lists only what is needed to decide, then rejects with a reason.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000002903","role":"authenticated"}',true);
select is(jsonb_array_length(public.admin_profile_submissions()),1,'administrator lists the pending submission');
select is(public.admin_profile_submissions()->0->'content'->>'name','Perfil de prueba','the list carries the submitted content');
select ok(position('Calle privada' in public.admin_profile_submissions('all')::text)=0
 and position('review-owner' in public.admin_profile_submissions('all')::text)=0,
 'the list exposes neither private address nor owner identity');
select throws_ok($$select public.review_professional_profile('20000000-0000-0000-0000-000000002901',false,'  ')$$,
 '22023','reason_required','a rejection needs a reason');
select is(public.review_professional_profile('20000000-0000-0000-0000-000000002901',false,'Falta el teléfono real')->>'status',
 'rejected','administrator rejects with a reason');
select is(public.review_professional_profile('20000000-0000-0000-0000-000000002901',false,'Falta el teléfono real')->>'status',
 'rejected','a lost response can be retried idempotently');
select throws_ok($$select public.review_professional_profile('20000000-0000-0000-0000-000000002901',true,'Cambio de opinión')$$,
 '22023','review_already_recorded','an executed decision cannot be changed');
select is((select count(*) from public.catalogue(null,'10000000-0000-0000-0000-000000002901')),0::bigint,
 'a rejected profile stays out of the catalogue');
select is(public.admin_profile_submissions('all')->0->'decision'->>'administrator_email','review-admin@example.org',
 'history shows the deciding administrator');

-- Owner learns the result and reason; a notice is persisted.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000002901","role":"authenticated"}',true);
select is(public.my_profile_review()->>'reason','Falta el teléfono real','owner sees the rejection reason');
select ok(public.my_profile_review()->>'decided_at' is not null,'owner sees the decision date');
select is(public.my_notices()->0->>'kind','profile_review','owner receives a notice');
select is(public.my_notices()->0->'payload'->>'status','rejected','the notice states the result');
select is(jsonb_array_length(public.my_notices()),1,'a retried decision does not duplicate the notice');

-- A corrected version is approved and published without rewriting other data.
reset role;
select private.submit_profile_version('10000000-0000-0000-0000-000000002901','20000000-0000-0000-0000-000000002902',
 '{"name":"Perfil de prueba","description":"Ficticio corregido","trades":["plumbing"],"services":["Fugas"],
   "coverage":[{"code":"33044","name":"Oviedo"}],"languages":["es"],"phone":"+34 000 000 029",
   "images":["plumbing"],"prices":[]}'::jsonb);
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000002903","role":"authenticated"}',true);
select throws_ok($$select public.review_professional_profile('20000000-0000-0000-0000-000000002901',true,'Old')$$,
 '22023','review_already_recorded','an old decided version cannot be reviewed again');
select is(public.review_professional_profile('20000000-0000-0000-0000-000000002902',true,'Datos coherentes')->>'status',
 'approved','administrator approves the corrected version');
select is((select description from public.catalogue('plumbing') where profile_id='10000000-0000-0000-0000-000000002901'),
 'Ficticio corregido','approval publishes exactly the submitted version');
reset role;
select is((select private_address from private.professional_profiles where id='10000000-0000-0000-0000-000000002901'),
 'Calle privada 1','approval does not rewrite private professional data');

-- Reviewing an outdated version is refused while a newer one is pending.
select private.submit_profile_version('10000000-0000-0000-0000-000000002901','20000000-0000-0000-0000-000000002903',
 '{"name":"V3","description":"","trades":["plumbing"],"services":[],"coverage":[],"languages":["es"],"phone":"+34 000"}'::jsonb);
select private.submit_profile_version('10000000-0000-0000-0000-000000002901','20000000-0000-0000-0000-000000002904',
 '{"name":"V4","description":"","trades":["plumbing"],"services":[],"coverage":[],"languages":["es"],"phone":"+34 000"}'::jsonb);
set local role authenticated;
select throws_ok($$select public.review_professional_profile('20000000-0000-0000-0000-000000002903',false,'Old')$$,
 '40001','profile_changed','an outdated pending version cannot be decided');
select is((select name from public.catalogue(null,'10000000-0000-0000-0000-000000002901')),'Perfil de prueba',
 'pending changes keep the last approved version public');
reset role;

-- Audit cannot be edited or deleted, even by trusted SQL.
select throws_ok($$update private.profile_decisions set reason='edited'$$,'42501','audit_immutable','decisions cannot be edited');
select throws_ok($$delete from private.profile_decisions$$,'42501','audit_immutable','decisions cannot be deleted');
select throws_ok($$update private.profile_submissions set content='{}'$$,'42501','audit_immutable',
 'submitted versions cannot be rewritten');
select * from finish();
rollback;
