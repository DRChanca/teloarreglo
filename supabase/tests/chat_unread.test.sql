begin;
create extension if not exists pgtap with schema extensions;
select plan(20);
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values
 ('00000000-0000-0000-0000-000000003901','unread-client@example.org',now(),'{"full_name":"  Lucía Prueba  "}'),
 ('00000000-0000-0000-0000-000000003902','unread-pro@example.org',now(),'{}'),
 ('00000000-0000-0000-0000-000000003903','unread-other@example.org',now(),'{}'),
 ('00000000-0000-0000-0000-000000003904','unread-anon-name@example.org',now(),'{}');
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000003901','00000000-0000-0000-0000-000000003902');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000003901','Fontanería Unread','Public',array['plumbing'],array['Repairs'],'[]',array['es'],'000');

create function pg_temp.act(p uuid) returns void language sql as
 $$ select set_config('request.jwt.claims', json_build_object('sub',p,'role','authenticated')::text, true) $$;
create function pg_temp.row_for(p_client uuid) returns jsonb language sql as
 $$ select e from jsonb_array_elements(public.conversation_inbox()) e where e->>'client_id' = p_client::text $$;
grant execute on function pg_temp.act(uuid), pg_temp.row_for(uuid) to authenticated;
create temp table ids(conv uuid);
grant all on ids to authenticated;

set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000003901');
insert into ids select (public.open_conversation('10000000-0000-0000-0000-000000003901')->>'id')::uuid;
select public.send_text((select id from public.conversations), '20000000-0000-0000-0000-000000003901', 'Hola');
select public.send_text((select id from public.conversations), '20000000-0000-0000-0000-000000003902', 'Se sale el agua');
select is((pg_temp.row_for('00000000-0000-0000-0000-000000003901')->>'unread_count')::int, 0,
 'own messages are never unread for the author');
select pg_temp.act('00000000-0000-0000-0000-000000003904');
select public.open_conversation('10000000-0000-0000-0000-000000003901');
select public.send_text((select id from public.conversations), '20000000-0000-0000-0000-000000003904', 'Presupuesto?');

select pg_temp.act('00000000-0000-0000-0000-000000003902');
select is(jsonb_array_length(public.conversation_inbox()), 2, 'professional inbox lists both client conversations');
select is(pg_temp.row_for('00000000-0000-0000-0000-000000003901')->>'client_name', 'Lucía Prueba',
 'professional sees the trimmed client name');
select is((pg_temp.row_for('00000000-0000-0000-0000-000000003901')->>'unread_count')::int, 2,
 'server counts unread messages from the client');
select is(pg_temp.row_for('00000000-0000-0000-0000-000000003904')->>'client_name', null,
 'without a declared name the email is never exposed');
select is((pg_temp.row_for('00000000-0000-0000-0000-000000003904')->>'unread_count')::int, 1,
 'each conversation has its own count');
select lives_ok($$select public.mark_conversation_read((select id from public.conversations
 where client_id = '00000000-0000-0000-0000-000000003901'))$$, 'opening the conversation marks it read');
select is((pg_temp.row_for('00000000-0000-0000-0000-000000003901')->>'unread_count')::int, 0,
 'unread indicator disappears after reading');
select is((pg_temp.row_for('00000000-0000-0000-0000-000000003904')->>'unread_count')::int, 1,
 'reading one conversation leaves the others unread');
select public.send_text((select id from public.conversations where client_id = '00000000-0000-0000-0000-000000003901'),
 '20000000-0000-0000-0000-000000003903', 'Voy mañana');

select pg_temp.act('00000000-0000-0000-0000-000000003901');
select is(jsonb_array_length(public.conversation_inbox()), 1, 'client inbox lists only its own conversation');
select is(pg_temp.row_for('00000000-0000-0000-0000-000000003901')->>'name', 'Fontanería Unread',
 'client sees the business name');
select is((pg_temp.row_for('00000000-0000-0000-0000-000000003901')->>'unread_count')::int, 1,
 'professional reading does not mark the client copy read');
select is((select count(*) from public.conversation_reads where user_id = '00000000-0000-0000-0000-000000003902'), 0::bigint,
 'client cannot see the professional read state');
select public.send_text((select id from public.conversations), '20000000-0000-0000-0000-000000003905', 'Gracias');

select pg_temp.act('00000000-0000-0000-0000-000000003902');
select is((pg_temp.row_for('00000000-0000-0000-0000-000000003901')->>'unread_count')::int, 1,
 'a later client message is unread again');
select is((select count(*) from public.conversation_reads), 1::bigint, 'professional reads only its own read state');
select throws_ok($$insert into public.conversation_reads(conversation_id,user_id,last_read_at)
 select id, '00000000-0000-0000-0000-000000003902', now() from public.conversations limit 1$$,
 '42501', null, 'no direct writes to read state');
select throws_ok($$update public.conversation_reads set last_read_at = now()$$, '42501', null, 'no direct updates');

select pg_temp.act('00000000-0000-0000-0000-000000003903');
select is(jsonb_array_length(public.conversation_inbox()), 0, 'outsider inbox is empty');
select throws_ok($$select public.mark_conversation_read((select conv from ids))$$, '42501', 'permission_denied',
 'outsider cannot mark a foreign conversation read even knowing its id');
reset role;
set local role anon;
select throws_ok($$select public.conversation_inbox()$$, '42501', null, 'anonymous cannot read the inbox');
reset role;
select * from finish();
rollback;
