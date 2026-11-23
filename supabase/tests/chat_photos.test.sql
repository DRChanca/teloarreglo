begin;
create extension if not exists pgtap with schema extensions;
select plan(29);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000001801','photo-client@example.org',now()),
 ('00000000-0000-0000-0000-000000001802','photo-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000001803','photo-other@example.org',now()),
 ('00000000-0000-0000-0000-000000001804','photo-admin@example.org',now());
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000001804');
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000001801','00000000-0000-0000-0000-000000001802');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000001801','Photo plumber','Public',array['plumbing'],array['Repairs'],'[]',array['es'],'000');
insert into public.conversations(id,profile_id,client_id,professional_id,name) values
 ('30000000-0000-0000-0000-000000001801','10000000-0000-0000-0000-000000001801',
  '00000000-0000-0000-0000-000000001801','00000000-0000-0000-0000-000000001802','Photo plumber');

select is((select public from storage.buckets where id='chat-attachments'),false,'chat attachments bucket is private');
select is((select file_size_limit from storage.buckets where id='chat-attachments'),2097152::bigint,
 'bucket rejects objects over 2 MiB');

set local role anon;
select throws_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','photo','image/jpeg',repeat('a',64))$$,'42501',null,'visitors cannot attach');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001803","role":"authenticated"}',true);
select throws_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','photo','image/jpeg',repeat('a',64))$$,'42501','permission_denied',
 'a third account cannot attach to the conversation');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001801","role":"authenticated"}',true);
select throws_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','photo','image/gif',repeat('a',64))$$,'22023','unsupported_attachment',
 'only JPEG/PNG photos are accepted');
select throws_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','video','video/mp4',repeat('a',64))$$,'22023','unsupported_attachment',
 'videos are not enabled by the photo migration');
select is(public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','photo','image/jpeg',repeat('a',64))->>'status','uploading',
 'client attaches before any professional reply or appointment');
select is(public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','photo','image/jpeg',repeat('a',64))->>'object_path',
 '30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001801.jpg',
 'retrying the request keeps one opaque object path');
select throws_ok($$select public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','photo','image/jpeg',repeat('b',64))$$,'22023','retry_conflict',
 'a request key cannot be reused for different content');
select is(public.can_write_chat_attachment('30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001801.jpg'),
 true,'author may upload the reserved object');
select is(public.can_write_chat_attachment('30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000009999.jpg'),
 false,'author cannot upload an arbitrary path');
select throws_ok($$select public.submit_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801')$$,'22023','upload_not_complete','no message before Storage holds the file');
select is((select count(*) from public.chat_messages),0::bigint,'nothing is sent silently');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001802","role":"authenticated"}',true);
select is(public.can_write_chat_attachment('30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001801.jpg'),
 false,'the other participant cannot write the author upload');
select is(public.can_read_chat_attachment('30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001801.jpg'),
 false,'an unconfirmed upload is not readable by the other participant');

reset role;
insert into storage.objects(bucket_id,name,metadata) values
 ('chat-attachments','30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001801.jpg',
  '{"mimetype":"image/jpeg","size":123456}');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001801","role":"authenticated"}',true);
select is(public.submit_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801')->>'attachment_kind','photo','confirmed upload becomes a photo message');
select lives_ok($$select public.submit_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801')$$,'confirmation retry is accepted');
select is((select count(*) from public.chat_messages),1::bigint,'retry does not duplicate the photo message');
select is(public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','photo','image/jpeg',repeat('a',64))->>'status','sent',
 'a lost confirmation is recovered without uploading again');
select is(public.can_write_chat_attachment('30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001801.jpg'),
 false,'a sent attachment cannot be overwritten');
select throws_ok($$select public.send_text('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001801','Other')$$,'22023','retry_conflict','a photo request key cannot become text');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001802","role":"authenticated"}',true);
select is((select attachment_path from public.chat_messages),
 '30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001801.jpg','professional sees the photo message');
select is((select count(*) from storage.objects where bucket_id='chat-attachments'),1::bigint,
 'professional can read the confirmed object through Storage RLS');
select throws_ok($$update public.chat_messages set attachment_path = 'x'$$,'42501',null,'no attachment editing');
select throws_ok($$delete from public.chat_messages$$,'42501',null,'no individual deletion');

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001803","role":"authenticated"}',true);
select is((select count(*) from storage.objects where bucket_id='chat-attachments'),0::bigint,
 'a third account cannot read the object even knowing its path');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001804","role":"authenticated"}',true);
select is(public.can_read_chat_attachment('30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001801.jpg'),
 false,'administrators get no general access to chat photos');

-- Oversized metadata (a bucket limit raised later for #19 videos) still cannot become a photo.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001802","role":"authenticated"}',true);
select is(public.request_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001802','photo','image/png',repeat('c',64))->>'object_path',
 '30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001802.png','professional can attach a PNG');
reset role;
insert into storage.objects(bucket_id,name,metadata) values
 ('chat-attachments','30000000-0000-0000-0000-000000001801/20000000-0000-0000-0000-000000001802.png',
  '{"mimetype":"image/png","size":2097153}');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000001802","role":"authenticated"}',true);
select throws_ok($$select public.submit_chat_attachment('30000000-0000-0000-0000-000000001801',
 '20000000-0000-0000-0000-000000001802')$$,'22023','attachment_too_large','photos over 2 MiB are refused');
reset role;
select * from finish();
rollback;
