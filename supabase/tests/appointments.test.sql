-- Appointment requests and versioned proposals (#20). Fixtures use the ...2001NN range.
begin;
create extension if not exists pgtap with schema extensions;
select plan(37);

-- Synthetic concejo (not a real code) covered by the professional; a point well outside it.
insert into public.concejos(ine_code, name, boundary) values
 ('33996', 'Prueba Citas', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000200101','appointment-client@example.org',now()),
 ('00000000-0000-0000-0000-000000200102','appointment-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000200103','appointment-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000200101','00000000-0000-0000-0000-000000200102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000200101','Appointment plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33996","name":"Prueba Citas"}]',array['es'],'000');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000200101","role":"authenticated"}',true);
select ok(not (public.open_conversation('10000000-0000-0000-0000-000000200101') ? 'address'),
 'opening the chat carries no client address');

-- Request: pending, no price, exact address and pin shared only with the professional.
select is(public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000200101',
 'Leaking pipe under the sink', '2030-01-15 10:00Z', '2030-01-15 12:00Z', 'Calle Uría 1, 3ºB', 43.05, -5.95)->>'stage',
 'pending', 'client requests an appointment that stays pending');
select is((select version from public.appointments), 1, 'a new request starts at version 1');
select is((select client_id::text from public.appointments), '00000000-0000-0000-0000-000000200101',
 'client identity comes from the session');
select is(public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000200101',
 'Leaking pipe under the sink', '2030-01-15 10:00Z', '2030-01-15 12:00Z', 'Calle Uría 1, 3ºB', 43.05, -5.95)->>'id',
 (select id::text from public.appointments), 'retrying the same request returns the same appointment');
select is((select count(*) from public.appointments), 1::bigint, 'retry does not duplicate the request');
select throws_ok($$select public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000200101',
 'Other problem', '2030-01-15 10:00Z', '2030-01-15 12:00Z', 'Calle Uría 1, 3ºB', 43.05, -5.95)$$,
 '22023', 'retry_conflict', 'a request key cannot be reused for different content');
select lives_ok($$select public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000200102',
 'Second problem, same hour', '2030-01-15 10:00Z', '2030-01-15 12:00Z', 'Calle Uría 1, 3ºB', 43.05, -5.95)$$,
 'several pending requests may share the same hour');
select throws_ok($$select public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000200103',
 'Too far', '2030-01-15 10:00Z', '2030-01-15 12:00Z', 'Somewhere else', 40.40, -3.70)$$,
 '22023', 'out_of_coverage', 'the pin must be inside the professional coverage');
select throws_ok($$select public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000200104',
 'Past', '2020-01-15 10:00Z', '2020-01-15 12:00Z', 'Calle Uría 1, 3ºB', 43.05, -5.95)$$,
 '22023', 'invalid_appointment', 'a request cannot start in the past by server time');
select throws_ok($$select public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000200105',
 'Backwards', '2030-01-15 12:00Z', '2030-01-15 10:00Z', 'Calle Uría 1, 3ºB', 43.05, -5.95)$$,
 '22023', 'invalid_appointment', 'the requested interval must end after it starts');
select throws_ok($$select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 1, '40000000-0000-0000-0000-000000200100', 'Self proposal', '2030-01-15 10:00Z', 60, 0, null, false, false)$$,
 '42501', 'permission_denied', 'the client cannot propose conditions');

-- Professional reads the shared address and proposes.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000200102","role":"authenticated"}',true);
select is((select address from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 'Calle Uría 1, 3ºB', 'the professional reads the exact address once requested');
select throws_ok($$select public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000200106',
 'Pro request', '2030-01-15 10:00Z', '2030-01-15 12:00Z', 'Calle Uría 1, 3ºB', 43.05, -5.95)$$,
 '42501', 'permission_denied', 'only the conversation client requests');
select is(public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 1, '40000000-0000-0000-0000-000000200101', 'Replace trap, materials included', '2030-01-15 10:30Z', 90, 8500, 2000, true, true)->>'version',
 '1', 'the professional sends proposal version 1');
select is((select end_at from public.appointment_proposals), '2030-01-15 12:00Z'::timestamptz,
 'the proposed interval is start plus duration');
select is((select amount_cents from public.appointment_proposals), 8500, 'amounts are stored in EUR cents');
select is((select stage from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 'pending', 'a proposal does not confirm or reserve the appointment');
select lives_ok($$select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 1, '40000000-0000-0000-0000-000000200101', 'Replace trap, materials included', '2030-01-15 10:30Z', 90, 8500, 2000, true, true)$$,
 'retrying the same proposal is accepted');
select is((select count(*) from public.appointment_proposals), 1::bigint, 'retry does not duplicate the proposal');
select throws_ok($$select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 1, '40000000-0000-0000-0000-000000200102', 'Based on an old version', '2030-01-15 10:30Z', 90, 9000, null, true, true)$$,
 '40001', 'stale_version', 'a proposal based on an outdated version is rejected');
select lives_ok($$select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000200102'),
 1, '40000000-0000-0000-0000-000000200103', 'Free visit to assess', '2030-01-15 10:30Z', 30, 0, null, false, true)$$,
 'another pending proposal may use the same hour without reserving it');
select throws_ok($$select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000200102'),
 2, '40000000-0000-0000-0000-000000200104', 'Negative', '2030-01-15 10:30Z', 30, -1, null, false, true)$$,
 '22023', 'invalid_proposal', 'a negative amount is not a valid proposal');
select throws_ok($$select public.reject_proposal((select id from public.appointment_proposals where version = 1 limit 1))$$,
 '42501', 'permission_denied', 'the professional cannot reject on behalf of the client');
select throws_ok($$update public.appointments set stage = 'confirmed'$$, '42501', null, 'no direct state writes');
select throws_ok($$insert into public.appointment_proposals(appointment_id, author_id, version, request_id, scope, start_at, end_at,
 duration_minutes, amount_cents) select id, '00000000-0000-0000-0000-000000200102', 9, gen_random_uuid(), 'x',
 '2030-01-15 10:30Z', '2030-01-15 11:30Z', 60, 1 from public.appointments limit 1$$, '42501', null, 'no direct proposal writes');

-- Client rejects the first proposal; history is kept and a new version can be sent.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000200101","role":"authenticated"}',true);
select is(public.reject_proposal((select id from public.appointment_proposals where scope like 'Replace%'))->>'status',
 'rejected', 'the client rejects the first proposal');
select is(public.reject_proposal((select id from public.appointment_proposals where scope like 'Replace%'))->>'status',
 'rejected', 'rejecting again is idempotent');
select is((select stage from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 'pending', 'rejecting the first proposal leaves the appointment unconfirmed');
select is((select count(*) from public.appointment_events where recipient_id = auth.uid() and kind = 'proposed'), 2::bigint,
 'the client has a persisted notice for each proposal received');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000200102","role":"authenticated"}',true);
select is(public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 3, '40000000-0000-0000-0000-000000200105', 'Replace trap, labour only', '2030-01-16 09:00Z', 60, 6000, null, false, true)->>'version',
 '2', 'after a rejection the professional sends version 2');
select is((select count(*) from public.appointment_proposals p join public.appointments a on a.id = p.appointment_id
 where a.request_id = '30000000-0000-0000-0000-000000200101'), 2::bigint, 'the rejected version stays in the history');
select is(public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000200101'),
 4, '40000000-0000-0000-0000-000000200106', 'Replace trap, revised', '2030-01-16 09:00Z', 60, 5500, null, false, true)->>'version',
 '3', 'a pending proposal can be revised by a newer version');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000200101","role":"authenticated"}',true);
select throws_ok($$select public.reject_proposal((select id from public.appointment_proposals where version = 2
 and scope = 'Replace trap, labour only'))$$, '40001', 'stale_version', 'a superseded version cannot be decided');

-- Third account, including an administrator, cannot read or act on the appointment.
reset role;
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000200103');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000200103","role":"authenticated"}',true);
select is((select count(*) from public.appointments) + (select count(*) from public.appointment_proposals)
 + (select count(*) from public.appointment_events), 0::bigint, 'a third account reads no appointment, proposal or notice');
select throws_ok($$select public.propose_appointment('99999999-0000-0000-0000-000000000000', 1,
 '40000000-0000-0000-0000-000000200107', 'Intrusion', '2030-01-15 10:30Z', 60, 100, null, false, false)$$,
 '42501', 'permission_denied', 'a third account cannot propose on a foreign appointment');
reset role;
set local role anon;
select throws_ok($$select public.reject_proposal('99999999-0000-0000-0000-000000000000')$$,
 '42501', null, 'anonymous callers cannot use appointment operations');
reset role;
select * from finish();
rollback;
