-- Delivery and read marks for the ticks on one's own messages. Fixtures use the ...4401NN range.
-- Delivered = the other participant's app fetched the conversation (or received its push); read = it was opened.
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000440101','receipts-client@example.org',now()),
 ('00000000-0000-0000-0000-000000440102','receipts-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000440103','receipts-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000440101','00000000-0000-0000-0000-000000440102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000440101','Receipts plumber','Public',array['plumbing'],array['Repairs'],'[]',array['es'],'000');

create function pg_temp.act(p uuid) returns void language sql as
 $$ select set_config('request.jwt.claims', json_build_object('sub',p,'role','authenticated')::text, true) $$;
create temp table ids(conv uuid);
grant all on ids to authenticated;
create function pg_temp.conv() returns uuid language sql as $$ select conv from ids $$;
create function pg_temp.newest() returns timestamptz language sql as
 $$ select max(created_at) from public.chat_messages where conversation_id = pg_temp.conv() $$;
grant execute on function pg_temp.act(uuid), pg_temp.conv(), pg_temp.newest() to authenticated;

set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000440101');
insert into ids select (public.open_conversation('10000000-0000-0000-0000-000000440101')->>'id')::uuid;
select public.send_text(pg_temp.conv(), '20000000-0000-0000-0000-000000440101', 'Hola');
select is(public.conversation_receipts(pg_temp.conv()), '{"delivered_at":null,"read_at":null}'::jsonb,
 'a message only stored on the server has neither mark');

-- The professional's app fetches its inbox: every conversation is delivered up to its newest message.
select pg_temp.act('00000000-0000-0000-0000-000000440102');
select lives_ok($$select public.mark_conversations_delivered()$$, 'fetching the inbox marks delivery');
select pg_temp.act('00000000-0000-0000-0000-000000440101');
select ok((public.conversation_receipts(pg_temp.conv())->>'delivered_at')::timestamptz >= pg_temp.newest(),
 'the sender sees the message delivered');
select is(public.conversation_receipts(pg_temp.conv())->>'read_at', null, 'delivered is not read');
select is((select count(*) from public.conversation_reads), 0::bigint,
 'the sender still cannot read the other participant''s rows directly');

select public.send_text(pg_temp.conv(), '20000000-0000-0000-0000-000000440102', 'Se sale el agua');
select ok((public.conversation_receipts(pg_temp.conv())->>'delivered_at')::timestamptz < pg_temp.newest(),
 'a later message is not delivered yet');

-- A push for this conversation reaches the professional's phone.
select pg_temp.act('00000000-0000-0000-0000-000000440102');
select lives_ok($$select public.mark_conversation_delivered(pg_temp.conv())$$, 'a received push marks delivery');
select pg_temp.act('00000000-0000-0000-0000-000000440101');
select ok((public.conversation_receipts(pg_temp.conv())->>'delivered_at')::timestamptz >= pg_temp.newest(),
 'the later message is now delivered');

select public.send_text(pg_temp.conv(), '20000000-0000-0000-0000-000000440103', '¿Puedes venir?');
select pg_temp.act('00000000-0000-0000-0000-000000440102');
select public.mark_conversation_read(pg_temp.conv());
select pg_temp.act('00000000-0000-0000-0000-000000440101');
select ok((public.conversation_receipts(pg_temp.conv())->>'read_at')::timestamptz >= pg_temp.newest(),
 'opening the conversation marks it read');
select ok((public.conversation_receipts(pg_temp.conv())->>'delivered_at')::timestamptz >= pg_temp.newest(),
 'read implies delivered');

-- Marks never move backwards.
reset role;
update public.conversation_reads set last_delivered_at = '2999-01-01', last_read_at = '2998-01-01'
 where user_id = '00000000-0000-0000-0000-000000440102';
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000440102');
select public.mark_conversation_delivered(pg_temp.conv());
select public.mark_conversations_delivered();
select public.mark_conversation_read(pg_temp.conv());
select pg_temp.act('00000000-0000-0000-0000-000000440101');
select is(public.conversation_receipts(pg_temp.conv()),
 '{"delivered_at":"2999-01-01T00:00:00+00:00","read_at":"2998-01-01T00:00:00+00:00"}'::jsonb, 'marks are monotonic');

-- The professional sees the client's marks for its own messages, never anyone else's.
select pg_temp.act('00000000-0000-0000-0000-000000440102');
select is(public.conversation_receipts(pg_temp.conv()), '{"delivered_at":null,"read_at":null}'::jsonb,
 'each participant sees the other one''s marks');
select throws_ok($$update public.conversation_reads set last_delivered_at = now()$$, '42501', null,
 'no direct writes to delivery marks');

select pg_temp.act('00000000-0000-0000-0000-000000440103');
select throws_ok($$select public.conversation_receipts(pg_temp.conv())$$, '42501', 'permission_denied',
 'an outsider cannot read the marks');
select throws_ok($$select public.mark_conversation_delivered(pg_temp.conv())$$, '42501', 'permission_denied',
 'an outsider cannot mark a foreign conversation delivered');
select lives_ok($$select public.mark_conversations_delivered()$$, 'the bulk mark only touches own conversations');
reset role;
select is((select count(*) from public.conversation_reads where user_id = '00000000-0000-0000-0000-000000440103'),
 0::bigint, 'an outsider gets no marks');
set local role anon;
select throws_ok($$select public.conversation_receipts(gen_random_uuid())$$, '42501', null,
 'anonymous cannot read marks');
reset role;
select * from finish();
rollback;
