-- Server clock for jobs marked «Realizado» (#27): reminder to the client and silent close, run by Supabase Cron.
-- Fixtures use the ...2701NN range. 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
-- Inside one transaction now() is fixed, so the elapsed time is simulated by moving the server marks into the past.
begin;
create extension if not exists pgtap with schema extensions;
select plan(29);

insert into public.concejos(ine_code, name, boundary) values
 ('33927', 'Prueba Reloj', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000270101','clock-client@example.org',now()),
 ('00000000-0000-0000-0000-000000270102','clock-pro@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000270101','00000000-0000-0000-0000-000000270102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000270101','Clock plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33927","name":"Prueba Reloj"}]',array['es'],'000');

set local role authenticated;
-- Eight requests: 1 integrated, 2 direct, 3 free, 4 integrated with a blocking incident, 5 confirmed by the client,
-- 6 integrated overdue for both deadlines (recovery), 7 trial accounts, 8 only the professional is a trial account.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000270101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000270101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000027010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle A 1', 43.05, -5.95) from generate_series(1, 8) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000270102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000027010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000027010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30,
 case when n = 3 then 0 else 8500 end, null, true, true) from generate_series(1, 8) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000270101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n),
 ('50000000-0000-0000-0000-00000027010' || n)::uuid, case when n = 2 then 'direct' else 'integrated' end)
 from generate_series(1, 8) n where n <> 3;
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope 3'));
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000270101') as integrated,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000270102') as direct,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000270103') as free,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000270104') as blocked,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000270105') as confirmed,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000270106') as overdue,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000270107') as trial,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000270108') as half_trial;
grant select on ids to authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000270102","role":"authenticated"}',true);
select public.start_work(id) from public.appointments where request_id::text like '30000000-0000-0000-0000-00000027010_';
select public.mark_done(id) from public.appointments where request_id::text like '30000000-0000-0000-0000-00000027010_'
 and request_id not in ('30000000-0000-0000-0000-000000270107', '30000000-0000-0000-0000-000000270108');
reset role;

-- Before the reminder is due nothing happens.
select private.run_completion_clock();
select is((select count(*)::integer from public.appointment_events where kind = 'reminder'
 and appointment_id = (select integrated from ids)), 0, 'no reminder before 24 h');

-- 24 h later: one reminder to the client, from the system, that becomes a notice (and push) like any event.
update public.appointments set done_at = now() - interval '24 hours', reminder_due_at = now(),
 close_due_at = now() + interval '24 hours' where id in (select integrated from ids union all select direct from ids);
select private.run_completion_clock();
select is((select array_agg(coalesce(actor_id::text, 'system') || '->' || recipient_id) from public.appointment_events
 where kind = 'reminder' and appointment_id = (select integrated from ids)),
 array['system->00000000-0000-0000-0000-000000270101'], 'one reminder to the client at 24 h');
select is((select payload->>'event' from private.notices where kind = 'appointment'
 and recipient_id = '00000000-0000-0000-0000-000000270101'
 and payload->>'appointment_id' = (select integrated from ids)::text order by created_at desc, id limit 1),
 'reminder', 'the reminder is a persisted notice for the client');
select is((select stage || '/' || p.pending_cents from public.appointments a join public.appointment_payments p
 on p.appointment_id = a.id where a.id = (select integrated from ids)), 'done/8500', 'the reminder changes nothing else');
select private.run_completion_clock();
select is((select count(*)::integer from public.appointment_events where kind = 'reminder'
 and appointment_id in (select integrated from ids union all select direct from ids)), 2,
 'a repeated run sends no second reminder');

-- Before 48 h nothing closes, not even an overdue reminder's job.
select is((select stage from public.appointments where id = (select integrated from ids)), 'done', 'no close before 48 h');

-- A blocking incident (#26) and a confirmation by the client happen before the deadline.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000270101","role":"authenticated"}',true);
select public.open_incident((select blocked from ids), '60000000-0000-0000-0000-000000270104', 'quality', 'Leaks again', true);
select public.confirm_done((select confirmed from ids));
reset role;

-- 48 h later the clock closes every due job that no incident blocks.
update public.appointments set done_at = now() - interval '48 hours', reminder_due_at = now() - interval '24 hours',
 close_due_at = now() where id in (select integrated from ids union all select direct from ids
 union all select free from ids union all select blocked from ids union all select confirmed from ids);
-- An interruption left this one overdue for both deadlines: it is closed, without a late reminder.
update public.appointments set done_at = now() - interval '72 hours', reminder_due_at = now() - interval '48 hours',
 close_due_at = now() - interval '24 hours' where id = (select overdue from ids);
select is(private.run_completion_clock(), '{"closed": 4, "blocked": 1, "failed": 0, "reminded": 0}'::jsonb,
 'one run closes the four due jobs and leaves the blocked one');
select is((select stage || '/' || completion || '/' || (completed_at = now()) from public.appointments
 where id = (select integrated from ids)), 'completed/no_answer/true', 'integrated: finalised without confirmation');
select is((select paid_cents || '/' || pending_cents || '/' || delivered_cents from public.appointment_payments
 where appointment_id = (select integrated from ids)), '8500/0/8500', 'integrated: the pending balance is delivered');
select is((select array_agg(kind || ':' || amount_cents || ':' || coalesce(actor_id::text, 'system') order by created_at)
 from public.payment_movements where appointment_id = (select integrated from ids)),
 array['paid:8500:00000000-0000-0000-0000-000000270101', 'delivered:8500:system'], 'integrated: one delivery by the system');
select is((select array_agg(recipient_id::text order by recipient_id) from public.appointment_events
 where kind = 'completed' and actor_id is null and appointment_id = (select integrated from ids)),
 array['00000000-0000-0000-0000-000000270101', '00000000-0000-0000-0000-000000270102'],
 'both participants are told of the silent close');
select is((select count(*)::integer from private.notices where payload->>'event' = 'completed'
 and payload->>'appointment_id' = (select integrated from ids)::text), 2, 'the close is a notice for each participant');
select is((select a.stage || '/' || a.completion || '/' || p.paid_cents || '/' || p.delivered_cents from public.appointments a
 join public.appointment_payments p on p.appointment_id = a.id where a.id = (select direct from ids)),
 'completed/no_answer/0/0', 'direct: closed without managed money');
select is((select count(*)::integer from public.payment_movements where appointment_id in
 (select direct from ids union all select free from ids)), 0, 'direct and free: no invented movement');
select is((select stage || '/' || completion from public.appointments where id = (select free from ids)),
 'completed/no_answer', 'free: closed as well');
select is((select a.stage || '/' || p.pending_cents || '/' || p.delivered_cents from public.appointments a
 join public.appointment_payments p on p.appointment_id = a.id where a.id = (select blocked from ids)),
 'done/8500/0', 'a blocking incident keeps the job open and the money pending');
select is((select count(*)::integer from public.appointment_events where kind = 'reminder'
 and appointment_id = (select blocked from ids)), 0, 'a blocked job gets no reminder');
select is((select completion || '/' || p.delivered_cents from public.appointments a join public.appointment_payments p
 on p.appointment_id = a.id where a.id = (select confirmed from ids)), 'client_confirmed/8500',
 'a job the client confirmed keeps the client''s confirmation');
select is((select a.stage || '/' || a.completion || '/' || p.delivered_cents from public.appointments a
 join public.appointment_payments p on p.appointment_id = a.id where a.id = (select overdue from ids)),
 'completed/no_answer/8500', 'recovery: an overdue job is closed by the next run');
select is((select count(*)::integer from public.appointment_events where kind = 'reminder'
 and appointment_id = (select overdue from ids)), 0, 'recovery: no late reminder for a job that closes');

-- Repeated runs, and a confirmation that arrives after the close, change nothing.
select is(private.run_completion_clock(), '{"closed": 0, "blocked": 1, "failed": 0, "reminded": 0}'::jsonb,
 'a repeated run does nothing new');
select is((select count(*)::integer from public.payment_movements where kind = 'delivered' and appointment_id in
 (select integrated from ids union all select overdue from ids)), 2, 'never a second delivery');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000270101","role":"authenticated"}',true);
select is(public.confirm_done((select integrated from ids))->>'completion', 'no_answer',
 'a late confirmation returns the close already made');
reset role;
select is((select array_agg(action order by action) from private.completion_clock_log where appointment_id in
 (select integrated from ids)), array['closed', 'reminded'], 'the server log records what the clock did');

-- The short interval of the isolated trial (#27): only when both participants are trial accounts, set by David with
-- trusted SQL. Nobody else, and no app parameter, can shorten the deadlines.
insert into private.completion_trial_accounts(user_id) values ('00000000-0000-0000-0000-000000270102');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000270102","role":"authenticated"}',true);
select is((select (extract(epoch from close_due_at - done_at) / 3600)::integer from jsonb_to_record(
 public.mark_done((select half_trial from ids))) as m(done_at timestamptz, close_due_at timestamptz)), 48,
 'one trial participant is not enough: 48 h');
select throws_ok($$insert into private.completion_trial_accounts(user_id) values (auth.uid())$$, '42501', null,
 'an account cannot make itself a trial account');
select throws_ok($$select private.run_completion_clock()$$, '42501', null, 'app accounts cannot run the clock');
reset role;
insert into private.completion_trial_accounts(user_id) values ('00000000-0000-0000-0000-000000270101');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000270102","role":"authenticated"}',true);
select is((select extract(epoch from reminder_due_at - done_at)::integer || '/' || extract(epoch from close_due_at - done_at)::integer
 from jsonb_to_record(public.mark_done((select trial from ids))) as m(done_at timestamptz, reminder_due_at timestamptz,
 close_due_at timestamptz)), '120/300', 'two trial accounts: reminder at 2 min and close at 5 min');
set local role anon;
select throws_ok($$select private.run_completion_clock()$$, '42501', null, 'anonymous callers cannot run the clock');
reset role;

select * from finish();
rollback;
