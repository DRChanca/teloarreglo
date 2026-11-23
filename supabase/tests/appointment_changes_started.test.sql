-- Changes during the job (David, 2026-11-23): after «Comenzar trabajo» either participant may still propose and
-- accept a new price, scope or duration (extra work agreed on the spot) with the same money rules as #23, but never
-- a new start time or address. Starting keeps a pending change of price/scope/duration and closes one that moves the
-- start or the address. Fixtures use the ...1250NN range. 2030-01-15 is a Tuesday; Madrid is UTC+1 then.
begin;
create extension if not exists pgtap with schema extensions;
select plan(23);

insert into public.concejos(ine_code, name, boundary) values
 ('33926', 'Prueba En Curso', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000125001','running-client@example.org',now()),
 ('00000000-0000-0000-0000-000000125002','running-pro@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000125001','00000000-0000-0000-0000-000000125002');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone,prices)
 values ('10000000-0000-0000-0000-000000125001','Running plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33926","name":"Prueba En Curso"}]',array['es'],'000',
  '[{"service":"Fuga","scope":"Una hora","unit":"hour","amount_cents":3500,"includes":"","excludes":""}]');

set local role authenticated;
-- Three paid (integrated) jobs at 08:00, 10:00 and 12:00Z.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125001","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000125001')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000012500' || n)::uuid, 'Job ' || n, ('2030-01-15 ' || (6 + 2 * n) || ':00Z')::timestamptz,
 ('2030-01-15 ' || (7 + 2 * n) || ':00Z')::timestamptz, 'Calle A 1', 43.05, -5.95) from generate_series(1, 3) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125002","role":"authenticated"}',true);
select public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"16:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000012500' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000012500' || n)::uuid, 'Scope ' || n, ('2030-01-15 ' || (6 + 2 * n) || ':00Z')::timestamptz,
 60, 8500, null, true, true) from generate_series(1, 3) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125001","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n),
 ('50000000-0000-0000-0000-00000012500' || n)::uuid, 'integrated') from generate_series(1, 3) n;
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000125001') as one,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000125002') as two,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000125003') as three;
grant select on ids to authenticated;

-- Both participants read the reference prices kept with the request, whatever the profile publishes later.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125002","role":"authenticated"}',true);
select public.save_professional_offer('["Repairs"]',
 '[{"service":"Fuga","scope":"Una hora","unit":"hour","amount_cents":9900,"includes":"","excludes":""}]');
select is((select reference_prices->0->>'amount_cents' from public.appointments where id = (select one from ids)), '3500',
 'the professional reads the price kept with the request, not the new profile price');

-- Before the start: a pending change of price only (one), a pending change of time (two), a correction request (three).
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125002","role":"authenticated"}',true);
select public.propose_change((select one from ids), 3, '60000000-0000-0000-0000-000000125001',
 'Scope 1 and tap', '2030-01-15 08:00Z', 60, 9900, null, true, true);
select public.propose_change((select two from ids), 3, '60000000-0000-0000-0000-000000125002',
 'Scope 2', '2030-01-15 11:00Z', 60, 8500, null, true, true);
select public.request_address_correction((select three from ids), 3, '60000000-0000-0000-0000-000000125003', 'Portal?');
select public.start_work(id) from public.appointments where id in ((select one from ids), (select two from ids), (select three from ids));
select is((select status from public.appointment_changes where request_id = '60000000-0000-0000-0000-000000125001'), 'pending',
 'starting keeps a pending change of price, scope or duration');
select is((select status from public.appointment_changes where request_id = '60000000-0000-0000-0000-000000125002'), 'superseded',
 'starting closes a pending change that moves the start time');
select is((select status from public.appointment_changes where request_id = '60000000-0000-0000-0000-000000125003'), 'superseded',
 'starting closes a pending address correction');

-- The client accepts the kept change on the spot: the difference is paid (simulated) at acceptance.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125001","role":"authenticated"}',true);
select is(public.accept_change((select id from public.appointment_changes where request_id = '60000000-0000-0000-0000-000000125001'))->>'status',
 'accepted', 'the client accepts extra work after the start');
select is((select stage from public.appointments where id = (select one from ids)), 'started', 'the job stays started');
select is((select p.scope || '/' || p.amount_cents from public.appointment_proposals p
 where p.appointment_id = (select one from ids) and p.status = 'accepted'), 'Scope 1 and tap/9900', 'the new conditions are in force');
select is((select paid_cents || '/' || pending_cents || '/' || agreed_cents from public.appointment_payments
 where appointment_id = (select one from ids)), '9900/9900/9900', 'the increase is paid (simulated) at acceptance');
select is((select kind || ':' || amount_cents from public.payment_movements where appointment_id = (select one from ids)
 and change_id is not null), 'paid:1400', 'one movement for the difference');

-- During the job either participant proposes; the client too, and a longer duration moves the reservation end.
select is(public.propose_change((select one from ids), (select version from public.appointments where id = (select one from ids)),
 '60000000-0000-0000-0000-000000125004', 'Scope 1 and tap', '2030-01-15 08:00Z', 90, 9900, null, true, true)->>'status',
 'pending', 'the client proposes a longer job after the start');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125002","role":"authenticated"}',true);
select is(public.accept_change((select id from public.appointment_changes where request_id = '60000000-0000-0000-0000-000000125004'))->>'status',
 'accepted', 'the professional accepts the longer duration');
select is((select upper(during) - lower(during) from public.agenda_reservations where appointment_id = (select one from ids)),
 interval '90 minutes', 'the reservation follows the new duration');

-- A reduction refunds the difference (simulated) at acceptance.
select public.propose_change((select one from ids), (select version from public.appointments where id = (select one from ids)),
 '60000000-0000-0000-0000-000000125005', 'Scope 1 only', '2030-01-15 08:00Z', 90, 8000, null, true, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125001","role":"authenticated"}',true);
select is(public.accept_change((select id from public.appointment_changes where request_id = '60000000-0000-0000-0000-000000125005'))->>'status',
 'accepted', 'the client accepts a reduction after the start');
select is((select paid_cents || '/' || pending_cents || '/' || refunded_cents || '/' || agreed_cents from public.appointment_payments
 where appointment_id = (select one from ids)), '9900/8000/1900/8000', 'the reduction is refunded (simulated) at acceptance');

-- Once started, the start time and the address no longer change.
select throws_ok($$select public.propose_change((select one from ids), (select version from public.appointments
 where id = (select one from ids)), '60000000-0000-0000-0000-000000125006', 'Scope 1 only', '2030-01-15 09:00Z', 90, 8000,
 null, true, true)$$, 'PT409', 'already_started', 'a new start time is refused after the start');
select throws_ok($$select public.propose_change((select one from ids), (select version from public.appointments
 where id = (select one from ids)), '60000000-0000-0000-0000-000000125007', 'Scope 1 only', '2030-01-15 08:00Z', 90, 8000,
 null, true, true, 'Calle B 2', 43.06, -5.96)$$, 'PT409', 'already_started', 'a new address is refused after the start');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125002","role":"authenticated"}',true);
select throws_ok($$select public.request_address_correction((select one from ids), (select version from public.appointments
 where id = (select one from ids)), '60000000-0000-0000-0000-000000125008', 'Portal?')$$, 'PT409', 'already_started',
 'no address correction after the start');
select is((select count(*)::integer from public.appointment_changes where appointment_id = (select one from ids)
 and status = 'pending'), 0, 'refused proposals leave nothing pending');

-- Same rules as before the start: no change; a refund cannot exceed what is still pending.
select throws_ok($$select public.propose_change((select one from ids), (select version from public.appointments
 where id = (select one from ids)), '60000000-0000-0000-0000-000000125009', 'Scope 1 only', '2030-01-15 08:00Z', 90, 8000,
 null, true, true)$$, '22023', 'no_change', 'the same conditions are not a change');
select is(public.propose_change((select two from ids), (select version from public.appointments where id = (select two from ids)),
 '60000000-0000-0000-0000-000000125010', 'Scope 2 bigger', '2030-01-15 10:00Z', 60, 12000, null, true, true)->>'status',
 'pending', 'a change keeping the start time is proposed on another started job');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000125001","role":"authenticated"}',true);
select is(public.reject_change((select id from public.appointment_changes where request_id = '60000000-0000-0000-0000-000000125010'))->>'status',
 'rejected', 'a change during the job can be rejected');
select is((select p.scope || '/' || p.amount_cents from public.appointment_proposals p
 where p.appointment_id = (select two from ids) and p.status = 'accepted'), 'Scope 2/8500', 'rejecting keeps the conditions in force');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select two from ids)
 and kind = 'change_rejected' and recipient_id = '00000000-0000-0000-0000-000000125002'), 1, 'the author is told');

select * from finish();
rollback;
