-- «Aviso para llamar» (#77): one hour before a confirmed appointment starts, its professional gets a notice to call
-- the client, persisted in private.notices and pushed. Run by Supabase Cron every minute.
-- Fixtures use the ...7701NN range. 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
-- Inside one transaction now() is fixed, so time is simulated by moving each appointment's reservation (its current
-- start) relative to now(); one-minute ranges keep them from overlapping in the single agenda.
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into public.concejos(ine_code, name, boundary) values
 ('33928', 'Prueba Llamada', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000770101','call-client@example.org',now()),
 ('00000000-0000-0000-0000-000000770102','call-pro@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000770101','00000000-0000-0000-0000-000000770102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000770101','Call plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33928","name":"Prueba Llamada"}]',array['es'],'+34600000000');
insert into private.push_devices(installation_id,user_id,token,enabled) values
 ('90000000-0000-0000-0000-000000770101','00000000-0000-0000-0000-000000770102','token-call-pro',true),
 ('90000000-0000-0000-0000-000000770102','00000000-0000-0000-0000-000000770101','token-call-client',true);

set local role authenticated;
-- Eight requests: 1 soon, 2 confirmed late, 3 pending, 4 started, 5 cancelled, 6 done, 7 moved closer, 8 moved away.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000770101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000770101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000077010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle A 1', 43.05, -5.95) from generate_series(1, 8) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000770102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000077010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000077010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30, 8500, null, true, true) from generate_series(1, 8) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000770101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n),
 ('50000000-0000-0000-0000-00000077010' || n)::uuid, 'integrated') from generate_series(1, 8) n where n not in (2, 3);
select public.cancel_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000770105'));
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000770102","role":"authenticated"}',true);
select public.start_work((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000077010' || n)::uuid))
 from generate_series(4, 6) n where n <> 5;
select public.mark_done((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000770106'));
reset role;

create temp table ids on commit drop as select n, a.id from generate_series(1, 8) n
 join public.appointments a on a.request_id = ('30000000-0000-0000-0000-00000077010' || n)::uuid;
create function pg_temp.id(p int) returns uuid language sql as $$ select id from ids where n = p $$;
-- Moves an appointment's current start to now() + p_minutes.
create function pg_temp.starts_in(p int, p_minutes int) returns void language sql as $$
 update public.agenda_reservations set during = tstzrange(now() + make_interval(mins => p_minutes),
   now() + make_interval(mins => p_minutes + 1), '[)') where appointment_id = pg_temp.id(p) $$;
create function pg_temp.calls(p int) returns integer language sql as $$
 select count(*)::integer from private.notices where kind = 'call_client' and source_id = pg_temp.id(p) $$;

-- Before the hour, nothing; at 60 minutes, the notice.
select pg_temp.starts_in(1, 61);
select private.run_call_notices();
select is(pg_temp.calls(1), 0, 'no notice more than an hour before the start');
select pg_temp.starts_in(1, 60);
select pg_temp.starts_in(4, 10);
select pg_temp.starts_in(6, 20);
select pg_temp.starts_in(7, 180);
select is(private.run_call_notices(), '{"created": 1}'::jsonb, 'one run creates the due notice only');
select is((select recipient_id::text from private.notices where kind = 'call_client' and source_id = pg_temp.id(1)),
 '00000000-0000-0000-0000-000000770102', 'the notice is for the professional');
select is(pg_temp.calls(4) + pg_temp.calls(6), 0, 'no notice for started or done appointments');
select is(pg_temp.calls(5), 0, 'no notice for a cancelled appointment');
select is(pg_temp.calls(3), 0, 'no notice for a pending appointment');

-- The payload carries identifiers only, never the phone.
select is((select payload from private.notices where kind = 'call_client' and source_id = pg_temp.id(1)),
 jsonb_build_object('appointment_id', pg_temp.id(1),
   'conversation_id', (select conversation_id from public.appointments where id = pg_temp.id(1))),
 'the payload holds the appointment and its conversation');
select ok((select payload::text from private.notices where kind = 'call_client' and source_id = pg_temp.id(1))
 not like '%600000000%', 'the payload has no phone number');

-- A second run does not repeat it.
select private.run_call_notices();
select is(pg_temp.calls(1), 1, 'a repeated run does not duplicate the notice');

-- The current start counts: one moved closer gets it, one moved away before its notice does not.
select pg_temp.starts_in(7, 30);
select pg_temp.starts_in(8, 50);
select pg_temp.starts_in(8, 120);
select private.run_call_notices();
select is(pg_temp.calls(7), 1, 'a start moved into the hour gets its notice');
select is(pg_temp.calls(8), 0, 'a start moved away before the notice gets none yet');

-- Confirmed with less than an hour to go: the next run sends it.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000770101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope 2'),
 '50000000-0000-0000-0000-000000770102', 'integrated');
reset role;
select pg_temp.starts_in(2, 15);
select private.run_call_notices();
select is(pg_temp.calls(2), 1, 'confirmed within the hour: created on the next run');

-- Push: one delivery per enabled device of the professional, identifiers only.
select is((select array_agg(d.installation_id::text) from private.push_deliveries d join private.notices n
 on n.id = d.notice_id where n.kind = 'call_client' and n.source_id = pg_temp.id(1)),
 array['90000000-0000-0000-0000-000000770101'], 'the notice is pushed to the professional''s devices');
select is((select c->>'appointment_id' from jsonb_array_elements(public.claim_push_deliveries(500)) c
 where c->>'kind' = 'call_client' and c->>'appointment_id' = pg_temp.id(1)::text), pg_temp.id(1)::text,
 'the dispatcher sends it as call_client with the appointment');

-- Only the professional sees it among their notices.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000770102","role":"authenticated"}',true);
select is((select count(*)::integer from jsonb_array_elements(public.my_notices()) x where x->>'kind' = 'call_client'),
 3, 'the professional sees the call notices');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000770101","role":"authenticated"}',true);
select is((select count(*)::integer from jsonb_array_elements(public.my_notices()) x where x->>'kind' = 'call_client'),
 0, 'the client does not');
reset role;

-- Only the server runs the routine.
select ok(not has_function_privilege('authenticated', 'private.run_call_notices(integer)', 'execute')
 and not has_function_privilege('anon', 'private.run_call_notices(integer)', 'execute')
 and has_function_privilege('service_role', 'private.run_call_notices(integer)', 'execute'),
 'only service_role (and cron) can run it');

select * from finish();
rollback;
