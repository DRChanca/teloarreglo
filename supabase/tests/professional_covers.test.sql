begin;
create extension if not exists pgtap with schema extensions;
select plan(14);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000000031','cover-owner@example.org',now()),
 ('00000000-0000-0000-0000-000000000032','cover-other@example.org',now()),
 ('00000000-0000-0000-0000-000000000033','cover-admin@example.org',now());
insert into private.administrators values ('00000000-0000-0000-0000-000000000033');
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000000031','00000000-0000-0000-0000-000000000031');
insert into public.approved_profiles
 (profile_id,name,description,trades,services,coverage,languages,phone,cover_path)
 values ('10000000-0000-0000-0000-000000000031','Cover test','',array['plumbing'],'{}','[]','{es}','+34 000 000 031','previous.jpg');
set local role anon;
select throws_ok($$select public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031')$$,
 '42501',null,'visitors cannot request cover changes');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000032","role":"authenticated"}',true);
select throws_ok($$select public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031')$$,
 '42501','permission_denied','another account cannot change the cover');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000031","role":"authenticated"}',true);
select is(public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031')->>'status',
 'pending','default-cover changes also require review');
select is((select cover_path from public.catalogue(null,'10000000-0000-0000-0000-000000000031')),
 'previous.jpg','pending changes preserve the approved cover');
select throws_ok($$select public.review_professional_cover('20000000-0000-0000-0000-000000000031',true,'Self approval')$$,
 '42501','permission_denied','owner cannot self-approve');
select is(public.professional_cover('10000000-0000-0000-0000-000000000031')->>'status',
 'pending','owner can recover pending state from server');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000033","role":"authenticated"}',true);
select throws_ok($$select public.review_professional_cover('20000000-0000-0000-0000-000000000031',true,' ')$$,
 '22023','reason_required','review requires a reason');
select lives_ok($$select public.review_professional_cover('20000000-0000-0000-0000-000000000031',true,'Approved generic image')$$,
 'administrator can approve the submitted version');
select is((select cover_path from public.catalogue(null,'10000000-0000-0000-0000-000000000031')),
 null,'approval restores the generic cover');
select lives_ok($$select public.review_professional_cover('20000000-0000-0000-0000-000000000031',true,'Approved generic image')$$,
 'review retries are idempotent');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000031","role":"authenticated"}',true);
select throws_ok($$select public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031',repeat('a',64))$$,
 '22023','request_conflict','request key cannot be reused for different content');
select is(public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000032',repeat('a',64))->>'status',
 'uploading','new photo is not submitted before storage confirms it');
select throws_ok($$select public.submit_professional_cover('20000000-0000-0000-0000-000000000032')$$,
 '22023','upload_not_complete','missing file cannot become pending');
set local role anon;
select set_config('request.jwt.claims','{}',true);
select is(public.can_read_professional_cover('10000000-0000-0000-0000-000000000031/20000000-0000-0000-0000-000000000032.jpg'),
 false,'unpublished photo is not publicly readable');
reset role;
select * from finish();
rollback;
