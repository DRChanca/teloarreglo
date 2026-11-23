-- Covers (#30 and David's decision of 2026-11-23): the owner publishes a cover directly, without
-- administrative review. Storage still checks format and size and only the owner changes it.
-- An unapproved profile keeps its cover until the profile is published.
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000000031','cover-owner@example.org',now()),
 ('00000000-0000-0000-0000-000000000032','cover-other@example.org',now()),
 ('00000000-0000-0000-0000-000000000033','cover-admin@example.org',now()),
 ('00000000-0000-0000-0000-000000000034','cover-new@example.org',now());
insert into private.administrators values ('00000000-0000-0000-0000-000000000033');
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000000031','00000000-0000-0000-0000-000000000031'),
 ('10000000-0000-0000-0000-000000000034','00000000-0000-0000-0000-000000000034');
insert into public.approved_profiles
 (profile_id,name,description,trades,services,coverage,languages,phone,cover_path)
 values ('10000000-0000-0000-0000-000000000031','Cover test','',array['plumbing'],'{}','[]','{es}','+34 000 000 031','previous.jpg');

set local role anon;
select throws_ok($$select public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031')$$,
 '42501',null,'visitors cannot change covers');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000032","role":"authenticated"}',true);
select throws_ok($$select public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031')$$,
 '42501','permission_denied','another account cannot change the cover');

-- Back to the trade image: public at once.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000031","role":"authenticated"}',true);
select is(public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031')->>'status',
 'published','the trade image is published without review');
select is((select cover_path from public.catalogue(null,'10000000-0000-0000-0000-000000000031')),
 null,'the catalogue shows the trade image at once');
select is(public.professional_cover('10000000-0000-0000-0000-000000000031')->>'status',
 'published','owner recovers the state from the server');
select is(public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031')->>'status',
 'published','retrying the same request is idempotent');
select throws_ok($$select public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000031',repeat('a',64))$$,
 '22023','request_conflict','a request key cannot be reused for different content');

-- A photo is published only once Storage holds the checked JPEG.
select is(public.request_professional_cover('10000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000032',repeat('a',64))->>'status',
 'uploading','a new photo waits for its upload');
select throws_ok($$select public.submit_professional_cover('20000000-0000-0000-0000-000000000032')$$,
 '22023','upload_not_complete','a missing file is never published');
reset role;
insert into storage.objects(bucket_id,name,metadata) values
 ('professional-covers','10000000-0000-0000-0000-000000000031/20000000-0000-0000-0000-000000000032.jpg',
  '{"mimetype":"image/png","size":1000}');
set local role authenticated;
select throws_ok($$select public.submit_professional_cover('20000000-0000-0000-0000-000000000032')$$,
 '22023','upload_not_complete','a file that is not a JPEG is never published');
reset role;
update storage.objects set metadata='{"mimetype":"image/jpeg","size":1000}'
 where name='10000000-0000-0000-0000-000000000031/20000000-0000-0000-0000-000000000032.jpg';
set local role anon;
select set_config('request.jwt.claims','{}',true);
select is(public.can_read_professional_cover('10000000-0000-0000-0000-000000000031/20000000-0000-0000-0000-000000000032.jpg'),
 false,'an unpublished upload is not public');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000032","role":"authenticated"}',true);
select throws_ok($$select public.submit_professional_cover('20000000-0000-0000-0000-000000000032')$$,
 '42501','permission_denied','another account cannot publish the owner photo');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000031","role":"authenticated"}',true);
select is(public.submit_professional_cover('20000000-0000-0000-0000-000000000032')->>'status',
 'published','the owner photo is published without review');
select is((select cover_path from public.catalogue(null,'10000000-0000-0000-0000-000000000031')),
 '10000000-0000-0000-0000-000000000031/20000000-0000-0000-0000-000000000032.jpg','the catalogue shows the new photo at once');
set local role anon;
select set_config('request.jwt.claims','{}',true);
select is(public.can_read_professional_cover('10000000-0000-0000-0000-000000000031/20000000-0000-0000-0000-000000000032.jpg'),
 true,'visitors can read the published photo');

-- Administration no longer reviews covers.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000033","role":"authenticated"}',true);
select throws_ok($$select public.review_professional_cover('20000000-0000-0000-0000-000000000032',false,'No')$$,
 '42501',null,'there is no cover review left to call');

-- An unapproved profile keeps its photo until the profile is published.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000034","role":"authenticated"}',true);
select public.request_professional_cover('10000000-0000-0000-0000-000000000034','20000000-0000-0000-0000-000000000034',repeat('b',64));
reset role;
insert into storage.objects(bucket_id,name,metadata) values
 ('professional-covers','10000000-0000-0000-0000-000000000034/20000000-0000-0000-0000-000000000034.jpg',
  '{"mimetype":"image/jpeg","size":2000}');
set local role authenticated;
select is(public.submit_professional_cover('20000000-0000-0000-0000-000000000034')->>'status',
 'published','an unapproved profile saves its photo');
select is((select count(*) from public.catalogue(null,'10000000-0000-0000-0000-000000000034')),0::bigint,
 'the unapproved profile is still not public');
select is(public.can_read_professional_cover('10000000-0000-0000-0000-000000000034/20000000-0000-0000-0000-000000000034.jpg'),
 true,'the owner can see the saved photo');
reset role;
select private.submit_profile_version('10000000-0000-0000-0000-000000000034','20000000-0000-0000-0000-000000000035',
 '{"name":"Cover new","description":"","trades":["plumbing"],"services":[],"coverage":[{"code":"33044","name":"Oviedo"}],
   "languages":["es"],"phone":"+34 600 000 034","images":[],"prices":[]}');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000033","role":"authenticated"}',true);
select public.review_professional_profile('20000000-0000-0000-0000-000000000035',true,'Correcto');
select is((select cover_path from public.catalogue(null,'10000000-0000-0000-0000-000000000034')),
 '10000000-0000-0000-0000-000000000034/20000000-0000-0000-0000-000000000034.jpg','the photo appears when the profile is published');
set local role anon;
select set_config('request.jwt.claims','{}',true);
select is(public.can_read_professional_cover('10000000-0000-0000-0000-000000000034/20000000-0000-0000-0000-000000000034.jpg'),
 true,'visitors can read it once the profile is published');
reset role;
select * from finish();
rollback;
