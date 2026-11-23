-- «Comenzar trabajo» and cancellation before the start with a full simulated refund (#24).
-- Fixtures use the ...2401NN range. 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
begin;
create extension if not exists pgtap with schema extensions;
select plan(40);

insert into public.concejos(ine_code, name, boundary) values
 ('33924', 'Prueba Inicio', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000240101','start-client@example.org',now()),
 ('00000000-0000-0000-0000-000000240102','start-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000240103','start-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000240101','00000000-0000-0000-0000-000000240102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000240101','Start plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33924","name":"Prueba Inicio"}]',array['es'],'000');

set local role authenticated;
-- Five requests: integrated (to start), integrated (to cancel), direct, free, and one left pending.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000240101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000024010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle A 1', 43.05, -5.95) from generate_series(1, 5) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240102","role":"authenticated"}',true);
select public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000024010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000024010' || n)::uuid, 'Scope ' || n, ('2030-01-15 0' || (7 + n) || ':00Z')::timestamptz, 60,
 case when n = 4 then 0 else 8500 end, null, true, true) from generate_series(1, 5) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope 1'),
 '50000000-0000-0000-0000-000000240101', 'integrated');
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope 2'),
 '50000000-0000-0000-0000-000000240102', 'integrated');
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope 3'),
 '50000000-0000-0000-0000-000000240103', 'direct');
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope 4'));
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000240101') as started,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000240102') as integrated,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000240103') as direct,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000240104') as free,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000240105') as pending;
grant select on ids to authenticated;

-- Only its professional starts the work.
select throws_ok($$select public.start_work((select started from ids))$$, '42501', 'permission_denied',
 'the client cannot mark the work as started');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240103","role":"authenticated"}',true);
select throws_ok($$select public.start_work('10000000-0000-0000-0000-000000240199')$$, '42501', 'permission_denied',
 'an unknown appointment is not revealed');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240102","role":"authenticated"}',true);
select throws_ok($$select public.start_work((select pending from ids))$$, 'PT409', 'incompatible_state',
 'an appointment that is not confirmed cannot start');
select is(public.start_work((select started from ids))->>'stage', 'started', 'the professional starts the work');
select isnt((select started_at from public.appointments where id = (select started from ids)), null,
 'the server records when the work started');
select is(public.start_work((select started from ids))->>'started_at',
 (select to_jsonb(started_at)#>>'{}' from public.appointments where id = (select started from ids)),
 'starting again returns the same mark');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select started from ids)
 and kind = 'started' and recipient_id = '00000000-0000-0000-0000-000000240101'), 1,
 'one notice tells the client that the work started');
select is((select paid_cents || '/' || pending_cents || '/' || agreed_cents from public.appointment_payments
 where appointment_id = (select started from ids)), '8500/8500/8500', 'starting moves no money and adds no cost');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select started from ids)), 1,
 'starting adds no payment movement');

-- After the start there is no cancellation with an automatic refund and no change of conditions.
select throws_ok($$select public.cancel_appointment((select started from ids))$$, 'PT409', 'already_started',
 'the professional cannot cancel once the work has started');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240101","role":"authenticated"}',true);
select is((select stage || '/' || (started_at is not null) from public.appointments where id = (select started from ids)),
 'started/true', 'the client sees the server mark');
select throws_ok($$select public.cancel_appointment((select started from ids))$$, 'PT409', 'already_started',
 'the client cannot cancel once the work has started');
select throws_ok($$select public.propose_change((select started from ids), (select version from public.appointments
 where id = (select started from ids)), '60000000-0000-0000-0000-000000240101', 'More', '2030-01-15 08:00Z', 60, 9900,
 null, true, true, null, null, null, null, null)$$, 'PT409', 'incompatible_state', 'starting does not open new costs');
select is((select paid_cents || '/' || pending_cents || '/' || refunded_cents from public.appointment_payments
 where appointment_id = (select started from ids)), '8500/8500/0', 'nothing is refunded after the start');

-- Before the start either participant cancels. Integrated: the pending simulated amount is refunded in full,
-- the reservation is released, a pending change is closed and the other participant is told.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240102","role":"authenticated"}',true);
select public.propose_change((select integrated from ids), (select version from public.appointments
 where id = (select integrated from ids)), '60000000-0000-0000-0000-000000240102', 'Smaller', '2030-01-15 09:00Z', 60, 7000,
 null, true, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240101","role":"authenticated"}',true);
select public.accept_change((select id from public.appointment_changes where scope = 'Smaller'));
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240102","role":"authenticated"}',true);
select public.propose_change((select integrated from ids), (select version from public.appointments
 where id = (select integrated from ids)), '60000000-0000-0000-0000-000000240103', 'Bigger', '2030-01-15 09:00Z', 60, 9000,
 null, true, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240103","role":"authenticated"}',true);
select throws_ok($$select public.cancel_appointment((select integrated from ids))$$, '42501', 'permission_denied',
 'a third account cannot cancel someone else''s appointment');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240101","role":"authenticated"}',true);
select is(public.cancel_appointment((select integrated from ids))->>'stage', 'cancelled', 'the client cancels before the start');
select is((select (cancelled_at is not null) || '/' || cancelled_by from public.appointments where id = (select integrated from ids)),
 'true/00000000-0000-0000-0000-000000240101', 'the server records when and who cancelled');
select is((select paid_cents || '/' || pending_cents || '/' || refunded_cents || '/' || delivered_cents
 from public.appointment_payments where appointment_id = (select integrated from ids)), '8500/0/8500/0',
 'everything still pending is refunded (simulated), without penalty');
select is((select array_agg(kind || ':' || amount_cents || ':' || cancellation order by created_at) from public.payment_movements
 where appointment_id = (select integrated from ids)), array['paid:8500:false', 'refunded:1500:false', 'refunded:7000:true'],
 'one refund movement for the cancellation');
select is((select count(*)::integer from public.agenda_reservations where appointment_id = (select integrated from ids)), 0,
 'the reservation is released');
select is((select status from public.appointment_changes where scope = 'Bigger'), 'superseded',
 'the pending change is closed without being applied');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select integrated from ids)
 and kind = 'cancelled' and recipient_id = '00000000-0000-0000-0000-000000240102'), 1, 'the professional is told');
select is(public.cancel_appointment((select integrated from ids))->>'stage', 'cancelled', 'cancelling again is harmless');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240102","role":"authenticated"}',true);
select is(public.cancel_appointment((select integrated from ids))->>'cancelled_by', '00000000-0000-0000-0000-000000240101',
 'a late cancellation by the other participant returns the first one');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select integrated from ids)
 and cancellation), 1, 'retries never duplicate the refund');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select integrated from ids)
 and kind = 'cancelled'), 1, 'retries never duplicate the notice');
select throws_ok($$select public.start_work((select integrated from ids))$$, 'PT409', 'incompatible_state',
 'a cancelled appointment cannot start');
select throws_ok($$select public.propose_change((select integrated from ids), (select version from public.appointments
 where id = (select integrated from ids)), '60000000-0000-0000-0000-000000240104', 'Again', '2030-01-15 09:00Z', 60, 9000,
 null, true, true)$$, 'PT409', 'incompatible_state', 'a cancelled appointment cannot change');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240101","role":"authenticated"}',true);
select throws_ok($$select public.accept_change((select id from public.appointment_changes where scope = 'Bigger'))$$,
 'PT409', 'stale_version', 'the closed change can no longer be accepted');

-- Direct payment and free appointments: no refund is invented and no money appears.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240102","role":"authenticated"}',true);
select is(public.cancel_appointment((select direct from ids))->>'stage', 'cancelled', 'the professional cancels a direct one');
select is((select agreed_cents || '/' || paid_cents || '/' || refunded_cents from public.appointment_payments
 where appointment_id = (select direct from ids)), '8500/0/0', 'a direct payment is not refunded by the app');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select direct from ids)), 0,
 'no movement for a direct payment');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select direct from ids)
 and kind = 'cancelled' and recipient_id = '00000000-0000-0000-0000-000000240101'), 1, 'the client is told');
select is(public.cancel_appointment((select free from ids))->>'stage', 'cancelled', 'a free appointment can be cancelled');
select is((select count(*)::integer from public.appointment_payments where appointment_id = (select free from ids)), 0,
 'a free cancellation creates no payment');
select throws_ok($$select public.cancel_appointment((select pending from ids))$$, 'PT409', 'incompatible_state',
 'a request that was never confirmed is not cancelled here');
select is((select count(*)::integer from public.agenda_reservations where appointment_id in
 ((select direct from ids), (select free from ids))), 0, 'direct and free reservations are released too');

-- The released hour can be booked again: here, the pending request takes the 09:00 slot of the cancelled one.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240102","role":"authenticated"}',true);
select public.propose_appointment((select pending from ids), 2, '40000000-0000-0000-0000-000000240106', 'Scope 5b',
 '2030-01-15 09:00Z', 60, 8500, null, true, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000240101","role":"authenticated"}',true);
select is(public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope 5b'),
 '50000000-0000-0000-0000-000000240105', 'integrated')->>'mode', 'integrated', 'a released slot is free again');

-- Nobody writes the marks directly; anonymous callers cannot act.
select throws_ok($$update public.appointments set stage = 'cancelled' where id = (select started from ids)$$,
 '42501', null, 'participants cannot cancel by writing the table');
reset role;
set local role anon;
select throws_ok($$select public.cancel_appointment('10000000-0000-0000-0000-000000240199')$$, '42501', null,
 'anonymous callers cannot cancel');

select * from finish();
rollback;
