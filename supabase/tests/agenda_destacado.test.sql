-- Agenda without configured hours and early slot checks; "Destacado" for an active simulated subscription.
-- Fixtures use the ...2610NN range. 2030-01-15 is a Tuesday (ISO 2); Madrid is UTC+1 then.
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);

insert into public.concejos(ine_code, name, boundary) values
 ('33961', 'Prueba Destacado', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography),
 ('33962', 'Prueba Fuera', 'SRID=4326;MULTIPOLYGON(((-6.2 43.0,-6.1 43.0,-6.1 43.1,-6.2 43.1,-6.2 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000261001','agenda-destacado-client@example.org',now()),
 ('00000000-0000-0000-0000-000000261002','agenda-destacado-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000261003','agenda-destacado-pro2@example.org',now()),
 ('00000000-0000-0000-0000-000000261004','agenda-destacado-draft@example.org',now());
insert into private.professional_profiles(id,owner_id,review_status) values
 ('10000000-0000-0000-0000-000000261001','00000000-0000-0000-0000-000000261002','approved'),
 ('10000000-0000-0000-0000-000000261002','00000000-0000-0000-0000-000000261003','approved'),
 ('10000000-0000-0000-0000-000000261004','00000000-0000-0000-0000-000000261004','pending');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone) values
 ('10000000-0000-0000-0000-000000261001','Destacado plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33961","name":"Prueba Destacado"}]',array['es'],'000'),
 ('10000000-0000-0000-0000-000000261002','Free plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33961","name":"Prueba Destacado"}]',array['es'],'000');
insert into private.professional_locations(profile_id, location, h3_cell) values
 ('10000000-0000-0000-0000-000000261001', 'SRID=4326;POINT(-5.95 43.05)', '86392a00fffffff'),
 ('10000000-0000-0000-0000-000000261002', 'SRID=4326;POINT(-5.95 43.06)', '86392a00fffffff');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000261001","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000261001')->>'id')::uuid,
 '30000000-0000-0000-0000-000000261001', 'Leak', '2030-01-15 09:00Z', '2030-01-15 10:00Z', 'Calle A 1', 43.05, -5.95);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000261001')->>'id')::uuid,
 '30000000-0000-0000-0000-000000261002', 'Tap', '2030-01-15 09:00Z', '2030-01-15 10:00Z', 'Calle A 1', 43.05, -5.95);

-- 1) No availability configured at all: hours are not restricted; only the agenda's bookings are.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000261002","role":"authenticated"}',true);
select lives_ok($$select public.propose_appointment((select id from public.appointments
 where request_id = '30000000-0000-0000-0000-000000261001'), 1, '40000000-0000-0000-0000-000000261001',
 'Paid repair', '2030-01-15 08:00Z', 120, 10000, null, false, true)$$,
 'without configured hours the professional may propose any future time');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000261001","role":"authenticated"}',true);
select is(public.confirm_proposal((select id from public.appointment_proposals where scope = 'Paid repair'),
 '50000000-0000-0000-0000-000000261001', 'integrated')->>'paid_cents', '10000',
 'the client can pay and confirm when the professional configured no hours');
select is((select stage from public.appointments where request_id = '30000000-0000-0000-0000-000000261001'),
 'confirmed', 'the appointment is confirmed and reserved');

-- 2) Proposing over an existing booking fails at once, for the professional, with slot_unavailable.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000261002","role":"authenticated"}',true);
select throws_ok($$select public.propose_appointment((select id from public.appointments
 where request_id = '30000000-0000-0000-0000-000000261002'), 1, '40000000-0000-0000-0000-000000261002',
 'Overlap', '2030-01-15 09:00Z', 60, 0, null, false, true)$$,
 'PT409', 'slot_unavailable', 'a proposal over a confirmed booking is refused when it is sent');
select is((select count(*) from public.appointment_proposals p join public.appointments a on a.id = p.appointment_id
 where a.request_id = '30000000-0000-0000-0000-000000261002'), 0::bigint, 'the refused proposal leaves nothing behind');

-- 3) Once hours exist they apply, already when proposing, with a distinct outside_hours error.
select public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select throws_ok($$select public.propose_appointment((select id from public.appointments
 where request_id = '30000000-0000-0000-0000-000000261002'), 1, '40000000-0000-0000-0000-000000261002',
 'Evening', '2030-01-15 18:00Z', 60, 0, null, false, true)$$,
 'PT409', 'outside_hours', 'a proposal outside the configured hours is refused when it is sent');
select lives_ok($$select public.propose_appointment((select id from public.appointments
 where request_id = '30000000-0000-0000-0000-000000261002'), 1, '40000000-0000-0000-0000-000000261002',
 'Late morning', '2030-01-15 11:00Z', 60, 0, null, false, true)$$, 'a free slot inside the hours is accepted');

-- 4) Race safety: a block taken after the proposal still stops the confirmation, without a charge.
select public.add_agenda_block('60000000-0000-0000-0000-000000261001', '2030-01-15 11:30Z', '2030-01-15 11:45Z', 'Errand');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000261001","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Late morning'))$$,
 'PT409', 'slot_unavailable', 'confirmation re-checks bookings taken after the proposal');

-- 5) Hours changed after the proposal: the confirmation names the real reason.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000261002","role":"authenticated"}',true);
select public.remove_agenda_block((select id from public.agenda_reservations where reason = 'Errand'));
select public.save_agenda(60, '[{"weekday":3,"start":"09:00","end":"14:00"}]');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000261001","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Late morning'))$$,
 'PT409', 'outside_hours', 'confirmation outside the current hours says so instead of "not free"');

-- 6) A change that moves the confirmed appointment is checked when it is proposed.
select throws_ok($$select public.propose_change((select id from public.appointments
 where request_id = '30000000-0000-0000-0000-000000261001'),
 (select version from public.appointments where request_id = '30000000-0000-0000-0000-000000261001'),
 '70000000-0000-0000-0000-000000261001', 'Paid repair', '2030-01-15 18:00Z', 120, 10000, null, false, true)$$,
 'PT409', 'outside_hours', 'a change to a time outside the hours is refused when it is proposed');
select lives_ok($$select public.propose_change((select id from public.appointments
 where request_id = '30000000-0000-0000-0000-000000261001'),
 (select version from public.appointments where request_id = '30000000-0000-0000-0000-000000261001'),
 '70000000-0000-0000-0000-000000261002', 'Paid repair', '2030-01-16 09:00Z', 60, 10000, null, false, true)$$,
 'a change inside the hours may be proposed');

-- 7) Destacado: only an active subscription on an approved public profile, only where it serves.
reset role;
insert into private.subscription_simulations(profile_id, active) values
 ('10000000-0000-0000-0000-000000261001', true), ('10000000-0000-0000-0000-000000261004', true);
set local role anon;
select is(public.is_featured('10000000-0000-0000-0000-000000261001'), true, 'visitors see the subscriber as Destacado');
select is(public.is_featured('10000000-0000-0000-0000-000000261002'), false, 'a free profile is not Destacado');
select is(public.is_featured('10000000-0000-0000-0000-000000261004'), false,
 'an unreviewed profile is never Destacado, even when subscribed');
select is(public.is_featured(null), false, 'no profile is not Destacado');
select throws_ok($$select * from public.featured_candidates('33961', 'plumbing')$$, '42501', null,
 'featured locations are server-only');
reset role;
set local role service_role;
select is((select array_agg(profile_id) from public.featured_candidates('33961', 'plumbing')),
 array['10000000-0000-0000-0000-000000261001'::uuid], 'the subscriber is featured in a concejo of its approved coverage');
select is((select count(*) from public.featured_candidates('33962', null)), 0::bigint,
 'a subscriber is never featured in a concejo it does not serve');
select is((select count(*) from public.featured_candidates('33961', 'electrical')), 0::bigint,
 'a subscriber is never featured for a trade it does not offer');
reset role;
update private.subscription_simulations set active = false where profile_id = '10000000-0000-0000-0000-000000261001';
set local role service_role;
select is((select count(*) from public.featured_candidates('33961', 'plumbing')), 0::bigint,
 'cancelling the simulation removes the Destacado');
reset role;
select is(public.is_featured('10000000-0000-0000-0000-000000261001'), false, 'a cancelled subscriber loses the badge');

select * from finish();
rollback;
