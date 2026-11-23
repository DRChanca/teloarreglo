-- Persistent in-app notices and push delivery outbox (#32). Fixtures use the ...3201NN range.
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into public.concejos(ine_code, name, boundary) values
 ('33932', 'Prueba Avisos', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000320101','notice-client@example.org',now()),
 ('00000000-0000-0000-0000-000000320102','notice-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000320103','notice-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000320101','00000000-0000-0000-0000-000000320102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000320101','Notice plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33932","name":"Prueba Avisos"}]',array['es'],'000');

create function pg_temp.act(p uuid) returns void language sql as
 $$ select set_config('request.jwt.claims', json_build_object('sub',p,'role','authenticated')::text, true) $$;
grant execute on function pg_temp.act(uuid) to authenticated;
create temp table ids(conv uuid, appt uuid);
grant all on ids to authenticated;

set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320101');
insert into ids(conv) select (public.open_conversation('10000000-0000-0000-0000-000000320101')->>'id')::uuid;
select public.send_text((select conv from ids), '20000000-0000-0000-0000-000000320101', 'Secreto del baño');

-- A message is noticed to the other participant only, without its body.
select is(jsonb_array_length(public.my_notices()), 0, 'the author gets no notice of their own message');
select pg_temp.act('00000000-0000-0000-0000-000000320102');
select is(public.my_notices()->0->>'kind', 'message', 'the professional is told about the message');
select is(public.my_notices()->0->'payload'->>'conversation_id', (select conv::text from ids),
 'the message notice points to its conversation');
select ok(public.my_notices()::text !~ 'Secreto', 'the notice never carries the message body');
select is((public.my_notices()->0->>'read')::boolean, false, 'a new notice is unread');
select pg_temp.act('00000000-0000-0000-0000-000000320103');
select is(jsonb_array_length(public.my_notices()), 0, 'a third account receives nothing');

-- An appointment event of any kind becomes a notice for its recipient.
select pg_temp.act('00000000-0000-0000-0000-000000320101');
update ids set appt = (public.request_appointment((select conv from ids), '30000000-0000-0000-0000-000000320101',
 'Leak', '2030-01-15 08:00Z', '2030-01-15 09:00Z', 'Calle Secreta 1', 43.05, -5.95)->>'id')::uuid;
select pg_temp.act('00000000-0000-0000-0000-000000320102');
select is((select n->'payload'->>'event' from jsonb_array_elements(public.my_notices()) n where n->>'kind' = 'appointment'),
 'requested', 'the professional is told about the request');
select ok(public.my_notices()::text !~ 'Secreta|43\.05', 'the appointment notice carries no address or coordinates');


-- Several messages of one conversation are one notice with their count; reading the chat clears it.
select pg_temp.act('00000000-0000-0000-0000-000000320101');
select public.send_text((select conv from ids), '20000000-0000-0000-0000-000000320102', 'Otra cosa');
select pg_temp.act('00000000-0000-0000-0000-000000320102');
select is((select count(*)::int from jsonb_array_elements(public.my_notices()) n where n->>'kind' = 'message'), 1,
 'messages of one conversation are grouped in one notice');
select is((select (n->>'count')::int from jsonb_array_elements(public.my_notices()) n where n->>'kind' = 'message'), 2,
 'the grouped notice counts the unread messages');
select public.mark_conversation_read((select conv from ids));
select is((select (n->>'read')::boolean from jsonb_array_elements(public.my_notices()) n where n->>'kind' = 'message'), true,
 'opening the conversation marks its message notice read');
select is((select (n->>'read')::boolean from jsonb_array_elements(public.my_notices()) n where n->>'kind' = 'appointment'), false,
 'reading the chat does not mark the appointment notice read');

-- Marking read is per recipient and survives reopening.
select pg_temp.act('00000000-0000-0000-0000-000000320103');
select public.mark_notices_read(array(select (n->>'id')::uuid from jsonb_array_elements(public.my_notices()) n));
select lives_ok($$select public.mark_notices_read(array(select conv from ids))$$, 'unknown ids are ignored');
select pg_temp.act('00000000-0000-0000-0000-000000320102');
create temp table seen as select (n->>'id')::uuid as id from jsonb_array_elements(public.my_notices()) n where n->>'kind' = 'appointment';
select pg_temp.act('00000000-0000-0000-0000-000000320103');
select public.mark_notices_read(array(select id from seen));
select pg_temp.act('00000000-0000-0000-0000-000000320102');
select is((select (n->>'read')::boolean from jsonb_array_elements(public.my_notices()) n where n->>'kind' = 'appointment'), false,
 'another account cannot mark my notices read');
select public.mark_notices_read(array(select id from seen));
select is((select (n->>'read')::boolean from jsonb_array_elements(public.my_notices()) n where n->>'kind' = 'appointment'), true,
 'the recipient marks a notice read');
select throws_ok($$select * from private.notices$$, '42501', null, 'notices are not readable directly');
reset role;
set local role anon;
select throws_ok($$select public.my_notices()$$, '42501', null, 'anonymous callers have no notices');
reset role;

select * from finish();
rollback;
