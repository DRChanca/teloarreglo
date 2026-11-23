begin;
create extension if not exists pgtap with schema extensions;
select plan(20);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000001901','video-client@example.org',now()),
 ('00000000-0000-0000-0000-000000001902','video-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000001903','video-other@example.org',now()),
 ('00000000-0000-0000-0000-000000001904','video-admin@example.org',now());
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000001904');
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000001901','00000000-0000-0000-0000-000000001902');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000001901','Video plumber','Public',array['plumbing'],array['Repairs'],'[]',array['es'],'000');
insert into public.conversations(id,profile_id,client_id,professional_id,name) values
 ('30000000-0000-0000-0000-000000001901','10000000-0000-0000-0000-000000001901',
  '00000000-0000-0000-0000-000000001901','00000000-0000-0000-0000-000000001902','Video plumber');

select is((select file_size_limit from storage.buckets where id='chat-attachments'),20971520::bigint,
 'the shared bucket admits up to 20 MiB');
select is((select allowed_mime_types from storage.buckets where id='chat-attachments'),
 array['image/jpeg','image/png','video/mp4'],'the bucket admits MP4 next to JPEG/PNG');
select is((select public from storage.buckets where id='chat-attachments'),false,'the bucket stays private');
select is((select count(*) from storage.buckets where id like 'chat%'),1::bigint,'videos reuse the photo bucket');
select is(private.chat_attachment_limit('photo','image/jpeg'),2097152::bigint,'photos keep their 2 MiB limit');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001903","role":"authenticated"}',true);
select throws_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001901','video','video/mp4',repeat('a',64))$$,'42501','permission_denied',
 'a third account cannot attach a video');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001901","role":"authenticated"}',true);
select throws_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001901','video','video/quicktime',repeat('a',64))$$,'22023','unsupported_attachment',
 'only MP4 videos are accepted');
select throws_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001901','photo','video/mp4',repeat('a',64))$$,'22023','unsupported_attachment',
 'an MP4 cannot be sent as a photo');
select is(public.request_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001901','video','video/mp4',repeat('a',64))->>'object_path',
 '30000000-0000-0000-0000-000000001901/20000000-0000-0000-0000-000000001901.mp4',
 'client reserves a video before any professional reply, with an opaque .mp4 path');
select throws_ok($$select public.submit_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001901')$$,'22023','upload_not_complete','no video message before Storage holds the file');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001902","role":"authenticated"}',true);
select is(public.can_read_chat_attachment('30000000-0000-0000-0000-000000001901/20000000-0000-0000-0000-000000001901.mp4'),
 false,'an unconfirmed video is not readable by the other participant');

reset role;
insert into storage.objects(bucket_id,name,metadata) values
 ('chat-attachments','30000000-0000-0000-0000-000000001901/20000000-0000-0000-0000-000000001901.mp4',
  '{"mimetype":"video/mp4","size":15728640}');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001901","role":"authenticated"}',true);
select is(public.submit_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001901')->>'attachment_kind','video','a stored 15 MiB MP4 becomes a video message');
select lives_ok($$select public.submit_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001901')$$,'confirmation retry is accepted');
select is((select count(*) from public.chat_messages),1::bigint,'retry does not duplicate the video message');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001902","role":"authenticated"}',true);
select is((select attachment_type from public.chat_messages),'video/mp4','professional sees the video message');
select is(public.can_read_chat_attachment('30000000-0000-0000-0000-000000001901/20000000-0000-0000-0000-000000001901.mp4'),
 true,'professional can open the confirmed video');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001903","role":"authenticated"}',true);
select is((select count(*) from storage.objects where bucket_id='chat-attachments'),0::bigint,
 'a third account cannot read the video even knowing its path');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001904","role":"authenticated"}',true);
select is(public.can_read_chat_attachment('30000000-0000-0000-0000-000000001901/20000000-0000-0000-0000-000000001901.mp4'),
 false,'administrators get no general access to chat videos');

-- A video over 20 MiB (only possible if the bucket limit were raised) is still refused.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001902","role":"authenticated"}',true);
select lives_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001902','video','video/mp4',repeat('c',64))$$,'professional can attach a video too');
reset role;
insert into storage.objects(bucket_id,name,metadata) values
 ('chat-attachments','30000000-0000-0000-0000-000000001901/20000000-0000-0000-0000-000000001902.mp4',
  '{"mimetype":"video/mp4","size":20971521}');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001902","role":"authenticated"}',true);
select throws_ok($$select public.submit_chat_attachment('30000000-0000-0000-0000-000000001901',
 '20000000-0000-0000-0000-000000001902')$$,'22023','attachment_too_large','videos over 20 MiB are refused');
reset role;
select * from finish();
rollback;
