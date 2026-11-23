-- Immediate push dispatch: a new delivery wakes deliver-push at once when the project configured it.
-- Without configuration (local and test databases) nothing is called and the domain operation never fails.
-- Fixtures use the ...4402NN range.
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000440201','dispatch-client@example.org',now()),
 ('00000000-0000-0000-0000-000000440202','dispatch-pro@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000440201','00000000-0000-0000-0000-000000440202');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000440201','Dispatch plumber','Public',array['plumbing'],array['Repairs'],'[]',array['es'],'000');
insert into private.push_devices(installation_id,user_id,token,enabled) values
 ('90000000-0000-0000-0000-000000440201','00000000-0000-0000-0000-000000440202','token-dispatch',true);

create function pg_temp.act(p uuid) returns void language sql as
 $$ select set_config('request.jwt.claims', json_build_object('sub',p,'role','authenticated')::text, true) $$;
create temp table ids(conv uuid);
grant all on ids to authenticated;
create function pg_temp.say(n int) returns void language sql as $$
 select pg_temp.act('00000000-0000-0000-0000-000000440201');
 select public.send_text((select conv from ids), ('20000000-0000-0000-0000-0000004402' || lpad(n::text, 2, '0'))::uuid, 'Hola ' || n);
$$;
grant execute on function pg_temp.act(uuid), pg_temp.say(int) to authenticated;

set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000440201');
insert into ids select (public.open_conversation('10000000-0000-0000-0000-000000440201')->>'id')::uuid;
select lives_ok($$select pg_temp.say(1)$$, 'without dispatch configuration a message is still sent');
reset role;
select is((select count(*) from private.push_deliveries where recipient_id = '00000000-0000-0000-0000-000000440202'),
 1::bigint, 'the delivery is queued for the cron fallback');
select is(to_regclass('net.http_request_queue'), null, 'pg_net is absent in this database');

-- Configured URL but no HTTP extension: the call fails and is swallowed.
select vault.create_secret('http://127.0.0.1:9/functions/v1/deliver-push', 'push_dispatch_url');
select vault.create_secret('test-dispatch-secret', 'push_dispatch_secret');
set local role authenticated;
select lives_ok($$select pg_temp.say(2)$$, 'a failing wake-up never reverts the message');
reset role;
select is((select count(*) from private.push_deliveries where recipient_id = '00000000-0000-0000-0000-000000440202'),
 2::bigint, 'the second delivery is queued');

-- With pg_net present, one request per statement goes to the configured URL with the dispatch bearer.
create extension pg_net;
set local role authenticated;
select pg_temp.say(3);
reset role;
select is((select count(*) from net.http_request_queue where url = 'http://127.0.0.1:9/functions/v1/deliver-push'),
 1::bigint, 'a new delivery wakes the dispatcher once');
select is((select headers->>'Authorization' from net.http_request_queue limit 1), 'Bearer test-dispatch-secret',
 'the wake-up carries the dispatch secret');
select * from finish();
rollback;
