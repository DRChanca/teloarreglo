-- «Realizado» by the professional and manual confirmation by the client with the simulated delivery (#25).
-- Fixtures use the ...2501NN range. 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
begin;
create extension if not exists pgtap with schema extensions;
select plan(46);

insert into public.concejos(ine_code, name, boundary) values
 ('33921', 'Prueba Realizado', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000250101','done-client@example.org',now()),
 ('00000000-0000-0000-0000-000000250102','done-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000250103','done-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000250101','00000000-0000-0000-0000-000000250102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000250101','Done plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33921","name":"Prueba Realizado"}]',array['es'],'000');

set local role authenticated;
-- Six requests: 1 integrated (main flow), 2 direct, 3 free, 4 integrated with a blocking incident,
-- 5 integrated never started, 6 integrated closed by the shared invariant as the clock (#27) will.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000250101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000025010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle A 1', 43.05, -5.95) from generate_series(1, 6) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000025010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000025010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30,
 case when n = 3 then 0 else 8500 end, null, true, true) from generate_series(1, 6) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n),
 ('50000000-0000-0000-0000-00000025010' || n)::uuid, case when n = 2 then 'direct' else 'integrated' end)
 from generate_series(1, 6) n where n <> 3;
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope 3'));
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000250101') as integrated,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000250102') as direct,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000250103') as free,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000250104') as blocked,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000250105') as unstarted,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000250106') as clock;
grant select on ids to authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250102","role":"authenticated"}',true);
select public.start_work(id) from public.appointments
 where id in (select integrated from ids union all select direct from ids union all select free from ids
   union all select blocked from ids union all select clock from ids);

-- Only the professional of a started job marks it done.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250101","role":"authenticated"}',true);
select throws_ok($$select public.mark_done((select integrated from ids))$$, '42501', 'permission_denied',
 'the client cannot mark the job as done');
select throws_ok($$select public.confirm_done((select integrated from ids))$$, 'PT409', 'incompatible_state',
 'the client cannot confirm before the professional marks it done');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250103","role":"authenticated"}',true);
select throws_ok($$select public.mark_done((select integrated from ids))$$, '42501', 'permission_denied',
 'a third account cannot mark someone else''s job as done');
select throws_ok($$select public.mark_done('10000000-0000-0000-0000-000000250199')$$, '42501', 'permission_denied',
 'an unknown appointment is not revealed');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250102","role":"authenticated"}',true);
select throws_ok($$select public.mark_done((select unstarted from ids))$$, 'PT409', 'incompatible_state',
 'a job that has not started cannot be marked done');

-- A pending change of price during the job is closed by «Realizado»: the accepted proposal holds the terms.
select public.propose_change((select integrated from ids), (select version from public.appointments
 where id = (select integrated from ids)), '60000000-0000-0000-0000-000000250101', 'More work', '2030-01-15 08:00Z', 30,
 9900, null, true, true);
select is(public.mark_done((select integrated from ids))->>'stage', 'done', 'the professional marks the job as done');
select is((select done_at from public.appointments where id = (select integrated from ids)), now(),
 'the server instant of the mark is kept');
select is((select (extract(epoch from reminder_due_at - done_at) / 3600)::integer || '/' || (extract(epoch from close_due_at - done_at) / 3600)::integer
 from public.appointments where id = (select integrated from ids)), '24/48', 'the deadlines count from the mark: 24 h and 48 h');
select is((select status from public.appointment_changes where scope = 'More work'), 'superseded',
 'the pending change is closed without being applied');
select is((select paid_cents || '/' || pending_cents || '/' || delivered_cents from public.appointment_payments
 where appointment_id = (select integrated from ids)), '8500/8500/0', 'marking done delivers nothing yet');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select integrated from ids)), 1,
 'marking done adds no payment movement');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select integrated from ids)
 and kind = 'done' and recipient_id = '00000000-0000-0000-0000-000000250101'), 1, 'one notice tells the client');
select is(public.mark_done((select integrated from ids))->>'done_at',
 (select to_jsonb(done_at)#>>'{}' from public.appointments where id = (select integrated from ids)),
 'marking again returns the same mark');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select integrated from ids)
 and kind = 'done'), 1, 'a repeated mark sends no second notice');
select throws_ok($$select public.confirm_done((select integrated from ids))$$, '42501', 'permission_denied',
 'the professional cannot confirm their own job');
select throws_ok($$select public.propose_change((select integrated from ids), (select version from public.appointments
 where id = (select integrated from ids)), '60000000-0000-0000-0000-000000250102', 'Even more', '2030-01-15 08:00Z', 30,
 9900, null, true, true)$$, 'PT409', 'incompatible_state', 'no changes once marked done');
select throws_ok($$select public.cancel_appointment((select integrated from ids))$$, 'PT409', 'incompatible_state',
 'no cancellation once marked done');

-- Only the client confirms; third accounts neither read nor act.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250103","role":"authenticated"}',true);
select throws_ok($$select public.confirm_done((select integrated from ids))$$, '42501', 'permission_denied',
 'a third account cannot confirm');
select is((select count(*)::integer from public.appointments where id = (select integrated from ids)), 0,
 'a third account does not see the appointment');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250101","role":"authenticated"}',true);
select is((select stage || '/' || (close_due_at is not null) from public.appointments where id = (select integrated from ids)),
 'done/true', 'the client sees the mark and its deadline');
select is(public.confirm_done((select integrated from ids))->>'stage', 'completed', 'the client confirms the job');
select is((select completion || '/' || (completed_at = now()) from public.appointments where id = (select integrated from ids)),
 'client_confirmed/true', 'the server records how and when it was completed');
select is((select paid_cents || '/' || pending_cents || '/' || refunded_cents || '/' || delivered_cents
 from public.appointment_payments where appointment_id = (select integrated from ids)), '8500/0/0/8500',
 'everything pending is delivered to the professional (simulated)');
select is((select array_agg(kind || ':' || amount_cents order by created_at) from public.payment_movements
 where appointment_id = (select integrated from ids)), array['paid:8500', 'delivered:8500'], 'one delivery movement');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select integrated from ids)
 and kind = 'completed' and actor_id = '00000000-0000-0000-0000-000000250101'
 and recipient_id = '00000000-0000-0000-0000-000000250102'), 1, 'one notice tells the professional');
select is(public.confirm_done((select integrated from ids))->>'completed_at',
 (select to_jsonb(completed_at)#>>'{}' from public.appointments where id = (select integrated from ids)),
 'confirming again returns the same result');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select integrated from ids)
 and kind = 'delivered'), 1, 'a retry never delivers twice');
select is(public.confirm_done((select integrated from ids))->>'delivered_cents', '8500', 'the result tells what was delivered');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250102","role":"authenticated"}',true);
select is((select a.stage || '/' || a.completion || '/' || p.delivered_cents from public.appointments a
 join public.appointment_payments p on p.appointment_id = a.id where a.id = (select integrated from ids)),
 'completed/client_confirmed/8500', 'the professional sees the completion and the delivery');

-- Direct and free complete the same way without inventing managed money.
select public.mark_done((select direct from ids)), public.mark_done((select free from ids));
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250101","role":"authenticated"}',true);
select is(public.confirm_done((select direct from ids))->>'delivered_cents', '0', 'direct: nothing delivered by the app');
select is((select agreed_cents || '/' || paid_cents || '/' || delivered_cents from public.appointment_payments
 where appointment_id = (select direct from ids)), '8500/0/0', 'direct: the agreement is kept, no integrated money');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select direct from ids)), 0,
 'direct: no movement');
select is(public.confirm_done((select free from ids))->>'stage', 'completed', 'free: the client confirms');
select is((select count(*)::integer from public.appointment_payments where appointment_id = (select free from ids)), 0,
 'free: no payment is invented');

-- A blocking incident (#26 redefines private.delivery_blocked) stops the confirmation and the delivery.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250102","role":"authenticated"}',true);
select public.mark_done((select blocked from ids));
reset role;
create or replace function private.delivery_blocked(p_appointment_id uuid)
returns boolean language sql stable security definer set search_path = ''
as $$ select p_appointment_id = (select blocked from pg_temp.ids) $$;
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250101","role":"authenticated"}',true);
select throws_ok($$select public.confirm_done((select blocked from ids))$$, 'PT409', 'incident_blocking',
 'a blocking incident prevents the confirmation');
select is((select stage || '/' || p.pending_cents || '/' || p.delivered_cents from public.appointments a
 join public.appointment_payments p on p.appointment_id = a.id where a.id = (select blocked from ids)),
 'done/8500/0', 'nothing is delivered while blocked');

-- The shared invariant the clock (#27) and administration will call: never before the deadline, once only.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250102","role":"authenticated"}',true);
select public.mark_done((select clock from ids));
select throws_ok($$select private.complete_appointment((select clock from ids), null, 'no_answer')$$, '42501', null,
 'app accounts cannot call the shared invariant');
reset role;
select throws_ok($$select private.complete_appointment((select clock from ids), null, 'no_answer')$$, 'PT409', 'not_due',
 'the silent close never happens before the deadline');
update public.appointments set done_at = now() - interval '49 hours', reminder_due_at = now() - interval '25 hours',
 close_due_at = now() - interval '1 hour' where id = (select clock from ids);
select is(private.complete_appointment((select clock from ids), null, 'no_answer')->>'completion', 'no_answer',
 'after the deadline the appointment closes without the client''s answer');
select is(private.complete_appointment((select clock from ids), null, 'no_answer')->>'delivered_cents', '8500',
 'a repeated run returns the same result');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select clock from ids)
 and kind = 'delivered'), 1, 'repeated runs deliver once');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select clock from ids)
 and kind = 'completed' and actor_id is null), 2, 'both participants are told of the silent close');
select throws_ok($$insert into public.payment_movements(appointment_id, kind, amount_cents)
 values ((select integrated from ids), 'delivered', 1)$$, '23505', null, 'at most one delivery per appointment');

-- Nobody writes the marks or the money directly.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000250101","role":"authenticated"}',true);
select throws_ok($$update public.appointments set stage = 'completed' where id = (select unstarted from ids)$$, '42501', null,
 'no direct write of the stage');
select throws_ok($$update public.appointment_payments set delivered_cents = 1 where appointment_id = (select unstarted from ids)$$,
 '42501', null, 'no direct write of the balance');
set local role anon;
select throws_ok($$select public.confirm_done('10000000-0000-0000-0000-000000250199')$$, '42501', null,
 'anonymous callers cannot confirm');

select * from finish();
rollback;
