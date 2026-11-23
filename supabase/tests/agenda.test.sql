-- Professional agenda and free-visit confirmation without overlaps (#21). Fixtures use the ...2101NN range.
-- 2030-01-15 is a Tuesday (ISO weekday 2); Madrid is UTC+1 in January, so 09:00–14:00 local = 08:00–13:00Z.
begin;
create extension if not exists pgtap with schema extensions;
select plan(41);

insert into public.concejos(ine_code, name, boundary) values
 ('33994', 'Prueba Agenda', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000210101','agenda-client@example.org',now()),
 ('00000000-0000-0000-0000-000000210102','agenda-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000210103','agenda-client2@example.org',now()),
 ('00000000-0000-0000-0000-000000210104','agenda-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000210101','00000000-0000-0000-0000-000000210102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000210101','Agenda plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33994","name":"Prueba Agenda"}]',array['es'],'000');

set local role authenticated;
-- Two clients ask for the same hour; neither request reserves it.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000210101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000210101', 'Client A leak', '2030-01-15 09:00Z', '2030-01-15 10:00Z', 'Calle A 1', 43.05, -5.95);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000210101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000210103', 'Client A second job', '2030-01-15 11:00Z', '2030-01-15 12:00Z', 'Calle A 1', 43.05, -5.95);
select throws_ok($$select public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"14:00"}]')$$,
 '42501', 'permission_denied', 'an account without a professional profile has no agenda');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210103","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000210101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000210102', 'Client B leak', '2030-01-15 09:00Z', '2030-01-15 10:00Z', 'Calle B 2', 43.05, -5.95);

-- The professional configures availability and usual duration.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210102","role":"authenticated"}',true);
select is(public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"14:00"}]')->>'default_duration_minutes',
 '60', 'the professional saves a usual duration');
select is(jsonb_array_length(public.my_agenda()->'windows'), 1, 'the weekly availability is stored');
select is(public.my_agenda()->>'timezone', 'Europe/Madrid', 'availability is expressed in Madrid local time');
select is(jsonb_array_length(public.save_agenda(60,
 '[{"weekday":2,"start":"09:00","end":"14:00"},{"weekday":3,"start":"16:00","end":"20:00"}]')->'windows'), 2,
 'saving replaces the whole weekly schedule');
select throws_ok($$select public.save_agenda(60, '[{"weekday":2,"start":"14:00","end":"09:00"}]')$$,
 '22023', 'invalid_agenda', 'a window must end after it starts');
select throws_ok($$select public.save_agenda(60, '[{"weekday":8,"start":"09:00","end":"10:00"}]')$$,
 '22023', 'invalid_agenda', 'weekdays are ISO 1–7');
select throws_ok($$select public.save_agenda(5, '[]')$$, '22023', 'invalid_agenda', 'the usual duration is 15–1440 minutes');
select is(jsonb_array_length(public.my_agenda()->'windows'), 2, 'an invalid save leaves the schedule untouched');

-- Free proposals on overlapping hours: both pending, nothing reserved yet.
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000210101'),
 1, '40000000-0000-0000-0000-000000210101', 'Free visit A', '2030-01-15 09:00Z', 60, 0, null, false, true);
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000210102'),
 1, '40000000-0000-0000-0000-000000210102', 'Free visit B', '2030-01-15 09:30Z', 60, 0, null, false, true);
select is(jsonb_array_length(public.my_agenda()->'bookings'), 0, 'pending proposals do not reserve the agenda');
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Free visit A'))$$,
 '42501', 'permission_denied', 'the professional cannot accept on behalf of the client');

-- Client A accepts: confirmed and reserved; retrying is idempotent.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210101","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Free visit B'))$$,
 '42501', 'permission_denied', 'a client cannot accept a proposal of someone else''s appointment');
select is(public.accept_proposal((select id from public.appointment_proposals where scope = 'Free visit A'))->>'status',
 'accepted', 'the client accepts a free proposal without payment');
select is((select stage from public.appointments where request_id = '30000000-0000-0000-0000-000000210101'),
 'confirmed', 'accepting confirms the appointment');
select is(public.accept_proposal((select id from public.appointment_proposals where scope = 'Free visit A'))->>'status',
 'accepted', 'accepting again returns the same result');
select is((select count(*) from public.appointment_events where kind = 'accepted'), 1::bigint,
 'a retried acceptance records a single notice');

-- Client B's overlapping proposal cannot be confirmed; nothing partial is left behind.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210103","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Free visit B'))$$,
 'PT409', 'slot_unavailable', 'an overlapping confirmation is refused');
select is((select stage || '/' || p.status from public.appointments a join public.appointment_proposals p on p.appointment_id = a.id
 where p.scope = 'Free visit B'), 'pending/pending', 'a refused confirmation leaves request and proposal pending');
select is((select count(*) from public.appointments a where a.stage = 'confirmed'), 0::bigint,
 'client B does not see client A''s booking');

-- The professional offers another hour, contiguous with the booking: [10:00, 11:00) touches [09:00, 10:00).
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210102","role":"authenticated"}',true);
select is(jsonb_array_length(public.my_agenda()->'bookings'), 1, 'the confirmed appointment occupies the agenda');
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000210102'),
 2, '40000000-0000-0000-0000-000000210103', 'Free visit B later', '2030-01-15 10:00Z', 60, 0, null, false, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210103","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Free visit B'))$$,
 'PT409', 'stale_version', 'a superseded proposal cannot be accepted');
select is(public.accept_proposal((select id from public.appointment_proposals where scope = 'Free visit B later'))->>'status',
 'accepted', 'contiguous appointments may touch');
select is((select stage from public.appointments), 'confirmed', 'client B sees the confirmation');

-- Agenda rules: outside availability, blocks, paid proposals, expired proposals.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210102","role":"authenticated"}',true);
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000210103'),
 1, '40000000-0000-0000-0000-000000210104', 'Too late', '2030-01-15 12:30Z', 60, 0, null, false, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210101","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Too late'))$$,
 'PT409', 'slot_unavailable', 'an interval leaving the availability window is refused');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210102","role":"authenticated"}',true);
select is(public.add_agenda_block('50000000-0000-0000-0000-000000210101', '2030-01-15 11:00Z', '2030-01-15 12:00Z',
 'Travel')->>'reason', 'Travel', 'the professional blocks time');
select is(public.add_agenda_block('50000000-0000-0000-0000-000000210101', '2030-01-15 11:00Z', '2030-01-15 12:00Z',
 'Travel')->>'id', (select id::text from public.agenda_reservations where kind = 'block'), 'retrying a block returns it');
select throws_ok($$select public.add_agenda_block('50000000-0000-0000-0000-000000210101', '2030-01-15 11:00Z',
 '2030-01-15 12:30Z', 'Travel')$$, '22023', 'retry_conflict', 'a block key cannot be reused for another interval');
select throws_ok($$select public.add_agenda_block('50000000-0000-0000-0000-000000210102', '2030-01-15 09:30Z',
 '2030-01-15 10:30Z', 'Lunch')$$, 'PT409', 'slot_unavailable', 'a block cannot cover a confirmed appointment');
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000210103'),
 2, '40000000-0000-0000-0000-000000210105', 'During block', '2030-01-15 11:30Z', 30, 0, null, false, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210101","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'During block'))$$,
 'PT409', 'slot_unavailable', 'a blocked interval cannot be confirmed');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210102","role":"authenticated"}',true);
select is(public.remove_agenda_block((select id from public.agenda_reservations where kind = 'block'))->>'removed',
 'true', 'the professional removes a block');
select is(public.remove_agenda_block('50000000-0000-0000-0000-000000210199')->>'removed', 'true',
 'removing an already removed block is idempotent');
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000210103'),
 3, '40000000-0000-0000-0000-000000210106', 'Paid repair', '2030-01-15 11:30Z', 30, 4000, null, false, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210101","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Paid repair'))$$,
 '22023', 'payment_required', 'a proposal with a cost is not confirmed without payment');
reset role;
update public.appointment_proposals set start_at = now() - interval '1 hour', end_at = now() - interval '30 minutes'
 where scope = 'Paid repair';
update public.appointment_proposals set amount_cents = 0 where scope = 'Paid repair';
set local role authenticated;
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Paid repair'))$$,
 'PT409', 'proposal_expired', 'a proposal whose start has passed cannot be accepted');
select is((select stage from public.appointments where request_id = '30000000-0000-0000-0000-000000210103'), 'pending',
 'refused confirmations leave the request open for another hour');

-- No direct writes to states, reservations or availability.
select throws_ok($$update public.appointments set stage = 'confirmed'$$, '42501', null, 'no direct stage writes');
select throws_ok($$update public.appointment_proposals set status = 'accepted'$$, '42501', null, 'no direct status writes');
select throws_ok($$insert into public.agenda_reservations(profile_id, professional_id, kind, during)
 values ('10000000-0000-0000-0000-000000210101', '00000000-0000-0000-0000-000000210102', 'block',
 '[2030-02-01 10:00Z,2030-02-01 11:00Z)')$$, '42501', null, 'no direct reservation writes');
select throws_ok($$delete from public.agenda_reservations$$, '42501', null, 'no direct reservation deletes');

-- Third account (even an administrator) reads no agenda and cannot block another professional's time.
reset role;
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000210104');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000210104","role":"authenticated"}',true);
select is((select count(*) from public.agenda_reservations) + (select count(*) from public.availability_windows)
 + (select count(*) from public.agenda_settings), 0::bigint, 'a third account reads no agenda data');
select throws_ok($$select public.add_agenda_block(gen_random_uuid(), '2030-02-01 10:00Z', '2030-02-01 11:00Z', 'x')$$,
 '42501', 'permission_denied', 'an account without a profile cannot block time');
reset role;
set local role anon;
select throws_ok($$select public.accept_proposal('99999999-0000-0000-0000-000000000000')$$,
 '42501', null, 'anonymous callers cannot accept proposals');
reset role;
select * from finish();
rollback;
