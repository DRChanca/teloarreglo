-- Versioned changes to a confirmed appointment that keep the previous agreement until accepted (#23).
-- Fixtures use the ...2301NN range. 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
begin;
create extension if not exists pgtap with schema extensions;
select plan(45);

insert into public.concejos(ine_code, name, boundary) values
 ('33923', 'Prueba Cambios', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000230101','changes-client@example.org',now()),
 ('00000000-0000-0000-0000-000000230102','changes-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000230103','changes-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000230101','00000000-0000-0000-0000-000000230102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000230101','Changes plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33923","name":"Prueba Cambios"}]',array['es'],'000');

set local role authenticated;
-- The client asks for a repair (paid, integrated) and a free check; the professional proposes both.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000230101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000230101', 'Leak', '2030-01-15 08:00Z', '2030-01-15 09:00Z', 'Calle A 1', 43.05, -5.95);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000230101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000230102', 'Check', '2030-01-15 11:00Z', '2030-01-15 12:00Z', 'Calle A 1', 43.05, -5.95);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230102","role":"authenticated"}',true);
select public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000230101'),
 1, '40000000-0000-0000-0000-000000230101', 'Repair', '2030-01-15 08:00Z', 60, 8500, null, true, true);
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000230102'),
 1, '40000000-0000-0000-0000-000000230102', 'Free check', '2030-01-15 11:00Z', 30, 0, null, false, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair'),
 '50000000-0000-0000-0000-000000230101', 'integrated');
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Free check'));
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000230101') as repair,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000230102') as checkup;
grant select on ids to authenticated;

-- The professional proposes a longer, dearer repair. Nothing in force changes while it is pending.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230102","role":"authenticated"}',true);
select throws_ok($$select public.propose_change((select repair from ids), 2, '60000000-0000-0000-0000-000000230199',
 'Repair and valve', '2030-01-15 08:00Z', 90, 12000, null, true, true)$$, 'PT409', 'stale_version',
 'a change must be based on the current version');
select is(public.propose_change((select repair from ids), 3, '60000000-0000-0000-0000-000000230101',
 'Repair and valve', '2030-01-15 08:00Z', 90, 12000, null, true, true)->>'status', 'pending',
 'the professional proposes a change to the confirmed appointment');
select is((select p.scope || '/' || p.amount_cents || '/' || p.duration_minutes from public.appointment_proposals p
 where p.appointment_id = (select repair from ids) and p.status = 'accepted'), 'Repair/8500/60',
 'while pending, the accepted conditions stay in force');
select is((select upper(during) - lower(during) from public.agenda_reservations where appointment_id = (select repair from ids)),
 interval '60 minutes', 'while pending, the reservation is unchanged');
select is((select paid_cents from public.appointment_payments where appointment_id = (select repair from ids)), 8500,
 'while pending, nothing is charged');
select throws_ok($$select public.accept_change((select id from public.appointment_changes where scope = 'Repair and valve'))$$,
 '42501', 'permission_denied', 'the author cannot accept its own change');

-- The client rejects: history and the previous agreement are kept.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select is(public.reject_change((select id from public.appointment_changes where scope = 'Repair and valve'))->>'status',
 'rejected', 'the other participant rejects the change');
select is(public.reject_change((select id from public.appointment_changes where scope = 'Repair and valve'))->>'status',
 'rejected', 'rejecting again is harmless');
select is((select stage || '/' || (select scope from public.appointment_proposals p where p.appointment_id = a.id
 and p.status = 'accepted') from public.appointments a where a.id = (select repair from ids)), 'confirmed/Repair',
 'after a rejection the appointment keeps its original conditions');
select throws_ok($$select public.accept_change((select id from public.appointment_changes where scope = 'Repair and valve'))$$,
 'PT409', 'stale_version', 'a rejected version cannot be accepted');

-- Two versions: only the current one can be accepted.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230102","role":"authenticated"}',true);
select public.propose_change((select repair from ids), (select version from public.appointments where id = (select repair from ids)),
 '60000000-0000-0000-0000-000000230102', 'Repair v2', '2030-01-15 08:00Z', 90, 11000, null, true, true);
select public.propose_change((select repair from ids), (select version from public.appointments where id = (select repair from ids)),
 '60000000-0000-0000-0000-000000230103', 'Repair v3', '2030-01-15 08:00Z', 90, 12000, null, true, true);
select is((select array_agg(version || ':' || status order by version) from public.appointment_changes
 where appointment_id = (select repair from ids)), array['1:rejected', '2:superseded', '3:pending'],
 'every version is kept with its outcome');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select throws_ok($$select public.accept_change((select id from public.appointment_changes where scope = 'Repair v2'))$$,
 'PT409', 'stale_version', 'accepting an old version never applies it');

-- Accepting an increase of an integrated payment simulates paying the difference, all at once.
select is(public.accept_change((select id from public.appointment_changes where scope = 'Repair v3'))->>'status', 'accepted',
 'the client accepts the current version');
select is((select scope || '/' || amount_cents || '/' || duration_minutes from public.appointment_proposals
 where appointment_id = (select repair from ids) and status = 'accepted'), 'Repair v3/12000/90',
 'the accepted change becomes the conditions in force');
select is((select status from public.appointment_proposals where scope = 'Repair'), 'replaced',
 'the previous agreement is kept in history as replaced');
select is((select paid_cents || '/' || pending_cents || '/' || refunded_cents || '/' || agreed_cents
 from public.appointment_payments where appointment_id = (select repair from ids)), '12000/12000/0/12000',
 'the difference is paid (simulated) before the extra work');
select is(public.accept_change((select id from public.appointment_changes where scope = 'Repair v3'))->>'status', 'accepted',
 'accepting again returns the same result');
select is((select array_agg(kind || ':' || amount_cents order by created_at) from public.payment_movements
 where appointment_id = (select repair from ids)), array['paid:8500', 'paid:3500'],
 'retries never repeat the difference');

-- Moving to an occupied hour fails and keeps the whole previous appointment.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230102","role":"authenticated"}',true);
select is((select upper(during) - lower(during) from public.agenda_reservations where appointment_id = (select repair from ids)),
 interval '90 minutes', 'the reservation follows the accepted duration');
select public.propose_change((select repair from ids), (select version from public.appointments where id = (select repair from ids)),
 '60000000-0000-0000-0000-000000230104', 'Repair later', '2030-01-15 10:45Z', 90, 12000, null, true, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select throws_ok($$select public.accept_change((select id from public.appointment_changes where scope = 'Repair later'))$$,
 'PT409', 'slot_unavailable', 'a change to an occupied hour is refused');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230102","role":"authenticated"}',true);
select is((select lower(during) || '/' || (select status from public.appointment_changes where scope = 'Repair later')
 from public.agenda_reservations where appointment_id = (select repair from ids)), '2030-01-15 08:00:00+00/pending',
 'the failed acceptance keeps the reservation and leaves the change pending');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select is(public.reject_change((select id from public.appointment_changes where scope = 'Repair later'))->>'status',
 'rejected', 'the client can then reject it');

-- A reduction accepted by the professional refunds the difference (simulated).
select throws_ok($$select public.propose_change((select repair from ids),
 (select version from public.appointments where id = (select repair from ids)), '60000000-0000-0000-0000-000000230198',
 'Repair v3', '2030-01-15 08:00Z', 90, 12000, null, true, true)$$, '22023', 'no_change',
 'a change identical to the conditions in force is refused');
select public.propose_change((select repair from ids), (select version from public.appointments where id = (select repair from ids)),
 '60000000-0000-0000-0000-000000230105', 'Repair smaller', '2030-01-15 08:00Z', 90, 7000, null, true, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230102","role":"authenticated"}',true);
select is(public.accept_change((select id from public.appointment_changes where scope = 'Repair smaller'))->>'status',
 'accepted', 'the professional accepts the client''s reduction');
select is((select paid_cents || '/' || pending_cents || '/' || refunded_cents || '/' || agreed_cents
 from public.appointment_payments where appointment_id = (select repair from ids)), '12000/7000/5000/7000',
 'the difference is refunded (simulated)');
select is((select array_agg(kind || ':' || amount_cents order by created_at) from public.payment_movements
 where appointment_id = (select repair from ids)), array['paid:8500', 'paid:3500', 'refunded:5000'],
 'one refund movement');

-- Address: the professional cannot replace it; it can ask for a correction, and the client changes it.
select throws_ok($$select public.propose_change((select repair from ids),
 (select version from public.appointments where id = (select repair from ids)), '60000000-0000-0000-0000-000000230197',
 'Repair smaller', '2030-01-15 08:00Z', 90, 7000, null, true, true, 'Calle Falsa 2', 43.06, -5.94)$$,
 '42501', 'address_owner_only', 'the professional never replaces the client''s address');
select is(public.request_address_correction((select repair from ids),
 (select version from public.appointments where id = (select repair from ids)), '60000000-0000-0000-0000-000000230106',
 'Falta el piso')->>'kind', 'address_correction', 'the professional asks the client to correct the address');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select throws_ok($$select public.accept_change((select id from public.appointment_changes where note = 'Falta el piso'))$$,
 '22023', 'invalid_change', 'a correction request has no conditions to accept');
select throws_ok($$select public.propose_change((select repair from ids),
 (select version from public.appointments where id = (select repair from ids)), '60000000-0000-0000-0000-000000230196',
 'Repair smaller', '2030-01-15 08:00Z', 90, 7000, null, true, true, 'Lejos 1', 43.5, -5.5)$$,
 '22023', 'out_of_coverage', 'a new address outside the coverage is refused');
select public.propose_change((select repair from ids), (select version from public.appointments where id = (select repair from ids)),
 '60000000-0000-0000-0000-000000230107', 'Repair smaller', '2030-01-15 08:00Z', 90, 7000, null, true, true,
 'Calle A 1, 3º', 43.06, -5.94, 'Corregido');
select is((select status from public.appointment_changes where note = 'Falta el piso'), 'superseded',
 'the client''s address change answers the correction request');
select is((select address from public.appointment_places((select conversation_id from public.appointments
 where id = (select repair from ids))) where appointment_id = (select repair from ids)), 'Calle A 1',
 'until accepted, the previous address stays in force');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230102","role":"authenticated"}',true);
select public.accept_change((select id from public.appointment_changes where note = 'Corregido'));
select is((select address from public.appointment_places((select conversation_id from public.appointments
 where id = (select repair from ids))) where appointment_id = (select repair from ids)), 'Calle A 1, 3º',
 'once accepted, the professional sees the corrected address');
select is((select previous_address from public.appointment_changes where note = 'Corregido'), 'Calle A 1',
 'history keeps the previous address');

-- A free visit that gains a cost: the client chooses direct payment; nothing integrated is invented.
select public.propose_change((select checkup from ids), (select version from public.appointments where id = (select checkup from ids)),
 '60000000-0000-0000-0000-000000230108', 'Check with part', '2030-01-15 11:00Z', 30, 4000, null, false, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select throws_ok($$select public.accept_change((select id from public.appointment_changes where scope = 'Check with part'))$$,
 '22023', 'invalid_payment', 'a new cost needs the client''s payment mode');
select is(public.accept_change((select id from public.appointment_changes where scope = 'Check with part'), 'direct')
 ->>'payment_mode', 'direct', 'the client accepts with direct payment');
select is((select mode || '/' || agreed_cents || '/' || paid_cents from public.appointment_payments
 where appointment_id = (select checkup from ids)), 'direct/4000/0', 'direct payment records the agreement only');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230102","role":"authenticated"}',true);
select public.propose_change((select checkup from ids), (select version from public.appointments where id = (select checkup from ids)),
 '60000000-0000-0000-0000-000000230109', 'Check cheaper', '2030-01-15 11:00Z', 30, 3000, null, false, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230101","role":"authenticated"}',true);
select public.accept_change((select id from public.appointment_changes where scope = 'Check cheaper'));
select is((select agreed_cents || '/' || (select count(*) from public.payment_movements m
 where m.appointment_id = (select checkup from ids)) from public.appointment_payments
 where appointment_id = (select checkup from ids)), '3000/0', 'a direct reduction moves no integrated money');

-- Only confirmed appointments change; only participants read or act; no direct writes.
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000230101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000230103', 'Pending', '2030-01-16 08:00Z', '2030-01-16 09:00Z', 'Calle A 1', 43.05, -5.95);
select throws_ok($$select public.propose_change((select id from public.appointments
 where request_id = '30000000-0000-0000-0000-000000230103'), 1, '60000000-0000-0000-0000-000000230195',
 'Too soon', '2030-01-16 08:00Z', 60, 0, null, false, true)$$, 'PT409', 'incompatible_state',
 'an unconfirmed request is negotiated with proposals, not changes');
select is((select count(*) from public.appointment_events where kind like 'change_%' or kind like 'address_%'),
 (select count(*) + count(*) filter (where status in ('accepted', 'rejected')) from public.appointment_changes),
 'every proposal and decision leaves a notice');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000230103","role":"authenticated"}',true);
select is((select count(*) from public.appointment_changes), 0::bigint, 'a third account reads no changes');
select throws_ok($$select public.propose_change((select repair from ids), 99, '60000000-0000-0000-0000-000000230194',
 'Mine', '2030-01-15 08:00Z', 60, 0, null, false, true)$$, '42501', 'permission_denied',
 'a third account cannot propose changes');
select throws_ok($$select public.reject_change((select id from public.appointment_changes limit 1))$$, '42501',
 'permission_denied', 'a third account cannot decide');
select throws_ok($$insert into public.appointment_changes(appointment_id, author_id, version, request_id, kind,
 base_proposal_id, note) values ((select repair from ids), auth.uid(), 99, gen_random_uuid(), 'address_correction',
 gen_random_uuid(), 'x')$$, '42501', null, 'no direct writes');
reset role;
set local role anon;
select throws_ok($$select public.accept_change('99999999-0000-0000-0000-000000000000')$$, '42501', null,
 'anonymous callers cannot accept changes');

reset role;
select * from finish();
rollback;
