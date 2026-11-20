begin;
create extension if not exists pgtap with schema extensions;
select plan(16);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000000151','chat-client@example.org',now()),
 ('00000000-0000-0000-0000-000000000152','chat-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000000153','chat-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000000151','00000000-0000-0000-0000-000000000152');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000000151','Chat plumber','Public',array['plumbing'],array['Repairs'],'[]',array['es'],'000');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000151","role":"authenticated"}',true);
select is(public.open_conversation('10000000-0000-0000-0000-000000000151')->>'professional_id',
 '00000000-0000-0000-0000-000000000152','professional identity comes from server ownership');
select is((select count(*) from public.conversations),1::bigint,'client reads its conversation directly through RLS');
select is(public.open_conversation('10000000-0000-0000-0000-000000000151')->>'client_id',
 '00000000-0000-0000-0000-000000000151','opening again preserves actor');
select is((select count(*) from public.conversations),1::bigint,'opening is idempotent');
select lives_ok($$select public.send_text((select id from public.conversations),
 '20000000-0000-0000-0000-000000000151','Hola')$$,'client sends before response or appointment');
select lives_ok($$select public.send_text((select id from public.conversations),
 '20000000-0000-0000-0000-000000000151','Hola')$$,'retry is accepted');
select is((select count(*) from public.chat_messages),1::bigint,'retry does not duplicate');
select throws_ok($$select public.send_text((select id from public.conversations),
 '20000000-0000-0000-0000-000000000151','Changed')$$,'22023','retry_conflict','retry cannot edit');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000152","role":"authenticated"}',true);
select is((select body from public.chat_messages),'Hola','professional reads contact');
select lives_ok($$select public.send_text((select id from public.conversations),
 '20000000-0000-0000-0000-000000000152','Bonjour')$$,'professional responds');
select is((select author_id::text from public.chat_messages where body = 'Bonjour'),
 '00000000-0000-0000-0000-000000000152','server records professional own identity');
select throws_ok($$update public.chat_messages set body = 'Edited'$$,'42501',null,'no message editing');
select throws_ok($$delete from public.chat_messages$$,'42501',null,'no individual deletion');
reset role;
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000000153');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000153","role":"authenticated"}',true);
select is((select count(*) from public.chat_messages),0::bigint,'third account including administrator cannot read messages');
select is((select count(*) from public.conversations),0::bigint,'administrator has no general conversation access');
select throws_ok($$select public.send_text('99999999-0000-0000-0000-000000000000',
 '20000000-0000-0000-0000-000000000153','Intrusion')$$,'42501','permission_denied','outsider cannot send');
reset role;
select * from finish();
rollback;
