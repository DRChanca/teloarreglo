-- Confirmation of proposals with a cost: simulated integrated payment or direct-payment agreement (#22).
-- Fixtures use the ...2201NN range. 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
begin;
create extension if not exists pgtap with schema extensions;
select plan(39);

insert into public.concejos(ine_code, name, boundary) values
 ('33992', 'Prueba Pagos', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000220101','payments-client@example.org',now()),
 ('00000000-0000-0000-0000-000000220102','payments-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000220103','payments-client2@example.org',now()),
 ('00000000-0000-0000-0000-000000220104','payments-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000220101','00000000-0000-0000-0000-000000220102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000220101','Payments plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33992","name":"Prueba Pagos"}]',array['es'],'000');

select columns_are('public', 'appointment_payments', array['appointment_id', 'proposal_id', 'client_id',
 'professional_id', 'request_id', 'mode', 'agreed_cents', 'paid_cents', 'pending_cents', 'refunded_cents',
 'delivered_cents', 'simulated', 'created_at'], 'payments keep amounts in cents and no card or bank data');

set local role authenticated;
-- Client A asks twice (a repair and a later free visit), client B asks for the same hour as A's repair.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000220101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000220101', 'A leak', '2030-01-15 09:00Z', '2030-01-15 10:00Z', 'Calle A 1', 43.05, -5.95);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000220101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000220103', 'A check', '2030-01-15 11:00Z', '2030-01-15 12:00Z', 'Calle A 1', 43.05, -5.95);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220103","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000220101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000220102', 'B leak', '2030-01-15 09:00Z', '2030-01-15 10:00Z', 'Calle B 2', 43.05, -5.95);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000220101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000220104', 'B visit', '2030-01-15 12:00Z', '2030-01-15 12:30Z', 'Calle B 2', 43.05, -5.95);

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220102","role":"authenticated"}',true);
select public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000220101'),
 1, '40000000-0000-0000-0000-000000220101', 'Repair A', '2030-01-15 09:00Z', 60, 8500, 3000, true, true);
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000220102'),
 1, '40000000-0000-0000-0000-000000220102', 'Repair B', '2030-01-15 09:30Z', 60, 6000, null, false, true);
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000220103'),
 1, '40000000-0000-0000-0000-000000220103', 'Free check A', '2030-01-15 11:00Z', 30, 0, null, false, true);
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000220104'),
 1, '40000000-0000-0000-0000-000000220104', 'Visit fee B', '2030-01-15 12:00Z', 30, 0, 2500, false, true);
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair A'),
 '50000000-0000-0000-0000-000000220199', 'integrated')$$,
 '42501', 'permission_denied', 'the professional cannot confirm on behalf of the client');

-- Client A pays the agreed total (simulated): accepted, confirmed, reserved and paid together.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220101","role":"authenticated"}',true);
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair B'),
 '50000000-0000-0000-0000-000000220198', 'integrated')$$,
 '42501', 'permission_denied', 'a client cannot confirm someone else''s proposal');
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair A'),
 '50000000-0000-0000-0000-000000220101', 'card')$$, '22023', 'invalid_payment', 'only integrated or direct modes exist');
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair A'),
 null, 'integrated')$$, '22023', 'invalid_payment', 'a retry key is required');
select is(public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair A'),
 '50000000-0000-0000-0000-000000220101', 'integrated') - 'created_at' - 'appointment_id' - 'proposal_id' - 'client_id'
 - 'professional_id' - 'request_id',
 '{"mode":"integrated","agreed_cents":8500,"paid_cents":8500,"pending_cents":8500,"refunded_cents":0,
   "delivered_cents":0,"simulated":true}'::jsonb,
 'integrated payment records the agreed total as paid and pending delivery, nothing delivered');
select is((select a.stage || '/' || p.status from public.appointments a join public.appointment_proposals p
 on p.appointment_id = a.id where p.scope = 'Repair A'), 'confirmed/accepted', 'paying confirms the appointment');
select is((select array_agg(m.kind || ':' || m.amount_cents) from public.payment_movements m
 join public.appointment_payments p using (appointment_id) where p.client_id = auth.uid()),
 array['paid:8500'], 'one simulated paid movement in cents');
select is(public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair A'),
 '50000000-0000-0000-0000-000000220101', 'integrated')->>'request_id', '50000000-0000-0000-0000-000000220101',
 'retrying the same key returns the same result');
select is(public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair A'),
 '50000000-0000-0000-0000-000000220109', 'integrated')->>'request_id', '50000000-0000-0000-0000-000000220101',
 'the same intention under a new key recovers the original result');
select is((select count(*) from public.payment_movements), 1::bigint, 'retries never charge twice');
select is((select count(*) from public.appointment_events where kind = 'accepted'), 1::bigint,
 'retries record a single notice');
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair A'),
 '50000000-0000-0000-0000-000000220101', 'direct')$$, '22023', 'retry_conflict', 'a key cannot be reused with another mode');
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Free check A'),
 '50000000-0000-0000-0000-000000220101', 'integrated')$$, '22023', 'retry_conflict',
 'a key cannot be reused for another proposal');
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair A'),
 '50000000-0000-0000-0000-000000220102', 'direct')$$, 'PT409', 'incompatible_state',
 'an appointment already paid cannot switch to direct payment');

-- Free visits keep their own path without payment.
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Free check A'),
 '50000000-0000-0000-0000-000000220103', 'integrated')$$, '22023', 'payment_not_required',
 'a free proposal is never charged');
select is(public.accept_proposal((select id from public.appointment_proposals where scope = 'Free check A'))->>'status',
 'accepted', 'the free acceptance still works');
select is((select count(*) from public.appointment_payments a join public.appointments b on b.id = a.appointment_id
 where b.request_id = '30000000-0000-0000-0000-000000220103'), 0::bigint, 'a free visit has no payment record');
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Repair B'))$$,
 '42501', 'permission_denied', 'accept_proposal keeps its permission check');

-- Client B's overlapping proposal: conflict, no charge, no partial effect.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220103","role":"authenticated"}',true);
select throws_ok($$select public.accept_proposal((select id from public.appointment_proposals where scope = 'Repair B'))$$,
 '22023', 'payment_required', 'a proposal with a cost still needs a payment mode');
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair B'),
 '50000000-0000-0000-0000-000000220104', 'integrated')$$, 'PT409', 'slot_unavailable', 'an overlapping payment is refused');
select is((select a.stage || '/' || p.status from public.appointments a join public.appointment_proposals p
 on p.appointment_id = a.id where p.scope = 'Repair B'), 'pending/pending', 'the conflict leaves request and proposal pending');
select is((select count(*) from public.appointment_payments), 0::bigint, 'the conflict charges nothing');
select is((select count(*) from public.payment_movements), 0::bigint, 'client B sees no movements');

-- The professional offers another hour; client B agrees to pay directly.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220102","role":"authenticated"}',true);
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000220102'),
 2, '40000000-0000-0000-0000-000000220105', 'Repair B later', '2030-01-15 10:00Z', 60, 6000, null, false, true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220103","role":"authenticated"}',true);
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair B'),
 '50000000-0000-0000-0000-000000220105', 'direct')$$, 'PT409', 'stale_version', 'a superseded version cannot be confirmed');
select is(public.confirm_proposal((select id from public.appointment_proposals where scope = 'Repair B later'),
 '50000000-0000-0000-0000-000000220106', 'direct') - 'created_at' - 'appointment_id' - 'proposal_id' - 'client_id'
 - 'professional_id' - 'request_id',
 '{"mode":"direct","agreed_cents":6000,"paid_cents":0,"pending_cents":0,"refunded_cents":0,
   "delivered_cents":0,"simulated":true}'::jsonb,
 'direct payment records the agreement without integrated money');
select is((select stage from public.appointments where request_id = '30000000-0000-0000-0000-000000220102'), 'confirmed',
 'the direct agreement confirms the appointment');
select is((select count(*) from public.payment_movements), 0::bigint, 'direct payment creates no integrated movement');

-- A visit fee alone ("if not repaired") is the amount paid up front.
select is(public.confirm_proposal((select id from public.appointment_proposals where scope = 'Visit fee B'),
 '50000000-0000-0000-0000-000000220107', 'integrated')->>'paid_cents', '2500', 'a visit fee without repair price is paid');

-- Reads: each client sees only its own; the professional sees both; nobody else.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220102","role":"authenticated"}',true);
select is((select count(*) from public.appointment_payments), 3::bigint, 'the professional sees the payments of its appointments');
select is((select sum(amount_cents) from public.payment_movements), 11000::bigint,
 'the professional sees the simulated movements');
select is(jsonb_array_length(public.my_agenda()->'bookings'), 4, 'paid, direct and free confirmations all reserve the agenda');
reset role;
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000220104');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220104","role":"authenticated"}',true);
select is((select count(*) from public.appointment_payments) + (select count(*) from public.payment_movements), 0::bigint,
 'a third account, even an administrator, reads no payments');

-- No direct writes.
select throws_ok($$insert into public.payment_movements(appointment_id, kind, amount_cents, actor_id)
 values ((select id from public.appointments limit 1), 'refunded', 100, auth.uid())$$, '42501', null, 'no direct movements');
select throws_ok($$update public.appointment_payments set delivered_cents = paid_cents$$, '42501', null,
 'no direct balance changes');
select throws_ok($$delete from public.payment_movements$$, '42501', null, 'no direct movement deletes');

-- Expired proposals are not charged.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000220101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000220105', 'A late', '2030-01-15 12:30Z', '2030-01-15 13:00Z', 'Calle A 1', 43.05, -5.95);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220102","role":"authenticated"}',true);
select public.propose_appointment((select id from public.appointments where request_id = '30000000-0000-0000-0000-000000220105'),
 1, '40000000-0000-0000-0000-000000220106', 'Expired', '2030-01-15 12:30Z', 30, 4000, null, false, true);
reset role;
update public.appointment_proposals set start_at = now() - interval '1 hour', end_at = now() - interval '30 minutes'
 where scope = 'Expired';
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000220101","role":"authenticated"}',true);
select throws_ok($$select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Expired'),
 '50000000-0000-0000-0000-000000220108', 'integrated')$$, 'PT409', 'proposal_expired', 'an expired proposal is not charged');
select is((select count(*) from public.payment_movements), 1::bigint, 'the refused payment left no movement');

reset role;
set local role anon;
select throws_ok($$select public.confirm_proposal('99999999-0000-0000-0000-000000000000', gen_random_uuid(), 'direct')$$,
 '42501', null, 'anonymous callers cannot confirm');
reset role;
select * from finish();
rollback;
