-- Device registration and the push delivery outbox (#32). Fixtures use the ...3202NN range.
-- The dispatcher (Edge Function deliver-push) runs as service_role: it claims deliveries and reports outcomes.
begin;
create extension if not exists pgtap with schema extensions;
select plan(32);

insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000320201','push-client@example.org',now()),
 ('00000000-0000-0000-0000-000000320202','push-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000320203','push-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000320201','00000000-0000-0000-0000-000000320202');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000320201','Push plumber','Public',array['plumbing'],array['Repairs'],'[]',array['es'],'000');

create function pg_temp.act(p uuid) returns void language sql as
 $$ select set_config('request.jwt.claims', json_build_object('sub',p,'role','authenticated')::text, true) $$;
-- Sends a message as the client to the professional, creating one notice for the professional.
create function pg_temp.say(n int) returns void language sql as $$
 select pg_temp.act('00000000-0000-0000-0000-000000320201');
 select public.send_text((select id from public.conversations), ('20000000-0000-0000-0000-0000003202' || lpad(n::text, 2, '0'))::uuid, 'Secreto ' || n);
$$;
create function pg_temp.claim() returns jsonb language sql as $$ select public.claim_push_deliveries(50) $$;
grant execute on function pg_temp.act(uuid), pg_temp.say(int) to authenticated;
grant execute on function pg_temp.claim() to service_role, authenticated;
create temp table claimed(round int, rows jsonb);
grant all on claimed to authenticated, service_role;

set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320201');
select public.open_conversation('10000000-0000-0000-0000-000000320201');

-- Registration belongs to the signed-in account and is validated.
select throws_ok($$select public.register_push_device('90000000-0000-0000-0000-000000320201', '', true)$$,
 '22023', 'invalid_token', 'an empty token is rejected');
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select is(public.register_push_device('90000000-0000-0000-0000-000000320201', 'token-pro-1', true)->>'enabled', 'true',
 'the professional registers this device');
select throws_ok($$select * from private.push_devices$$, '42501', null, 'devices are not readable directly');
select throws_ok($$select public.claim_push_deliveries(10)$$, '42501', null, 'an account cannot claim deliveries');
select throws_ok($$select public.report_push_delivery(gen_random_uuid(), 'sent', null)$$, '42501', null,
 'an account cannot report deliveries');

-- A confirmed event creates one delivery per enabled device of its recipient; the claim carries no private content.
select pg_temp.say(1);
reset role; set local role service_role;
insert into claimed select 1, pg_temp.claim();
select is(jsonb_array_length((select rows from claimed where round = 1)), 1, 'one delivery for the one device');
select is((select rows->0->>'token' from claimed where round = 1), 'token-pro-1', 'it targets the registered token');
select is((select rows->0->>'recipient_id' from claimed where round = 1), '00000000-0000-0000-0000-000000320202',
 'it names the recipient so the device can discard it after a session change');
select is((select rows->0->>'kind' from claimed where round = 1), 'message', 'it carries the notice kind');
select ok((select rows::text from claimed where round = 1) !~ 'Secreto', 'it never carries the message body');
select is(jsonb_array_length(pg_temp.claim()), 0, 'a claimed delivery is not handed out twice while in flight');
select lives_ok(format($$select public.report_push_delivery(%L, 'sent', null)$$,
 (select rows->0->>'delivery_id' from claimed where round = 1)), 'the dispatcher reports it sent');
select lives_ok(format($$select public.report_push_delivery(%L, 'retry', 'late')$$,
 (select rows->0->>'delivery_id' from claimed where round = 1)), 'a late report after sending is ignored');
reset role;
update private.push_deliveries set next_attempt_at = now() - interval '1 minute';
set local role service_role;
select is(jsonb_array_length(pg_temp.claim()), 0, 'a sent delivery is never sent again');

-- A transient failure is retried later, then given up; the notice stays in the app.
set local role authenticated;
select pg_temp.say(2);
reset role; set local role service_role;
insert into claimed select 2, pg_temp.claim();
select public.report_push_delivery((select (rows->0->>'delivery_id')::uuid from claimed where round = 2), 'retry', 'unavailable');
select is(jsonb_array_length(pg_temp.claim()), 0, 'a retry waits for its backoff');
reset role;
select ok((select next_attempt_at > now() from private.push_deliveries
 where id = (select (rows->0->>'delivery_id')::uuid from claimed where round = 2)), 'the retry is scheduled later');
update private.push_deliveries set next_attempt_at = now() - interval '1 second';
set local role service_role;
select is((pg_temp.claim()->0->>'delivery_id'), (select rows->0->>'delivery_id' from claimed where round = 2),
 'after the backoff the same delivery is retried, not a new one');
reset role;
update private.push_deliveries set attempts = 5 where id = (select (rows->0->>'delivery_id')::uuid from claimed where round = 2);
set local role service_role;
select public.report_push_delivery((select (rows->0->>'delivery_id')::uuid from claimed where round = 2), 'retry', 'unavailable');
reset role;
select is((select status from private.push_deliveries where id = (select (rows->0->>'delivery_id')::uuid from claimed where round = 2)),
 'failed', 'after five attempts the delivery is given up');
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select is((public.my_notices()->0->>'count')::int, 2, 'failed pushes never lose the in-app notices');

-- An invalid token disables the device and drops its pending deliveries.
select pg_temp.say(3);
reset role; set local role service_role;
insert into claimed select 3, pg_temp.claim();
select public.report_push_delivery((select (rows->0->>'delivery_id')::uuid from claimed where round = 3), 'invalid_token', 'UNREGISTERED');
set local role authenticated;
select pg_temp.say(4);
reset role; set local role service_role;
select is(jsonb_array_length(pg_temp.claim()), 0, 'an invalid token receives nothing more');
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select is(public.register_push_device('90000000-0000-0000-0000-000000320201', 'token-pro-2', true)->>'enabled', 'true',
 'a refreshed token re-enables the device');
select pg_temp.say(5);
reset role; set local role service_role;
select is(pg_temp.claim()->0->>'token', 'token-pro-2', 'the refreshed token receives new notices');

-- Session changes: the installation follows the signed-in account, never the previous one.
set local role authenticated;
select pg_temp.say(6);
select pg_temp.act('00000000-0000-0000-0000-000000320203');
select public.register_push_device('90000000-0000-0000-0000-000000320201', 'token-pro-2', true);
reset role; set local role service_role;
select is(jsonb_array_length(pg_temp.claim()), 0,
 'a pending delivery for the previous account is dropped when another account signs in on the device');
set local role authenticated;
select pg_temp.say(7);
reset role; set local role service_role;
select is(jsonb_array_length(pg_temp.claim()), 0, 'the previous account no longer receives pushes on that device');
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select public.unregister_push_device('90000000-0000-0000-0000-000000320201');
reset role;
select is((select user_id::text from private.push_devices where installation_id = '90000000-0000-0000-0000-000000320201'),
 '00000000-0000-0000-0000-000000320203', 'an account cannot unregister a device that is no longer its own');

-- The same token on another installation replaces the old row; disabled devices receive nothing.
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select public.register_push_device('90000000-0000-0000-0000-000000320202', 'token-pro-2', true);
reset role;
select is((select count(*)::int from private.push_devices where token = 'token-pro-2'), 1, 'a token belongs to one installation');
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select is(public.register_push_device('90000000-0000-0000-0000-000000320202', 'token-pro-2', false)->>'enabled', 'false',
 'notifications can be turned off on this device');
select pg_temp.say(8);
reset role; set local role service_role;
select is(jsonb_array_length(pg_temp.claim()), 0, 'a disabled device receives nothing');
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select public.register_push_device('90000000-0000-0000-0000-000000320202', 'token-pro-2', true);
select pg_temp.say(9);
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select lives_ok($$select public.unregister_push_device('90000000-0000-0000-0000-000000320202')$$, 'signing out unregisters the device');
reset role; set local role service_role;
select is(jsonb_array_length(pg_temp.claim()), 0, 'after signing out nothing is pushed to that device');

-- Stale deliveries expire instead of arriving long after the event.
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000320202');
select public.register_push_device('90000000-0000-0000-0000-000000320202', 'token-pro-3', true);
select pg_temp.say(10);
reset role;
update private.push_deliveries set created_at = now() - interval '2 days' where status = 'pending';
set local role service_role;
select is(jsonb_array_length(pg_temp.claim()), 0, 'a delivery older than a day is not sent');
reset role;
set local role anon;
select throws_ok($$select public.register_push_device('90000000-0000-0000-0000-000000320209', 'x', true)$$, '42501', null,
 'anonymous callers cannot register devices');
reset role;

select * from finish();
rollback;
