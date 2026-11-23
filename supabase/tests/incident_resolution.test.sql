-- Administrative resolution of incidents (#31). Fixtures use the ...3101NN range.
-- 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
begin;
create extension if not exists pgtap with schema extensions;
select plan(65);

insert into public.concejos(ine_code, name, boundary) values
 ('33931', 'Prueba Resolución', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000310101','resolution-client@example.org',now()),
 ('00000000-0000-0000-0000-000000310102','resolution-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000310103','resolution-other@example.org',now()),
 ('00000000-0000-0000-0000-000000310104','resolution-admin@example.org',now());
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000310104');
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000310101','00000000-0000-0000-0000-000000310102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000310101','Resolution plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33931","name":"Prueba Resolución"}]',array['es'],'000');

set local role authenticated;
-- Six jobs: 1 integrated done (quality), 2 integrated started (cancellation request), 3 integrated done
-- (not performed), 4 integrated completed (problem after the delivery), 5 direct started, 6 free done.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000310101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000031010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle Secreta 7', 43.05, -5.95) from generate_series(1, 6) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000031010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000031010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30,
 case when n = 6 then 0 else 8500 end, null, true, true) from generate_series(1, 6) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n),
 ('50000000-0000-0000-0000-00000031010' || n)::uuid, case when n = 5 then 'direct' else 'integrated' end)
 from generate_series(1, 6) n where n <> 6;
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope 6'));
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000310101') as quality,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000310102') as cancel,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000310103') as absent,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000310104') as closed,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000310105') as direct,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000310106') as free,
 (select conversation_id from public.appointments where request_id = '30000000-0000-0000-0000-000000310101') as conversation;
grant select on ids to authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310102","role":"authenticated"}',true);
select public.start_work(id) from public.appointments where request_id::text like '30000000-0000-0000-0000-0000003101%';
select public.mark_done(id) from public.appointments where id in
 (select quality from ids union all select absent from ids union all select closed from ids union all select free from ids);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310101","role":"authenticated"}',true);
select public.confirm_done((select closed from ids));
select public.send_text((select conversation from ids), '60000000-0000-0000-0000-000000310101', 'Mensaje aportado');
select public.send_text((select conversation from ids), '60000000-0000-0000-0000-000000310102', 'Charla privada no aportada');
select public.open_incident((select quality from ids), '70000000-0000-0000-0000-000000310101', 'quality', 'Gotea aún', true);
select public.open_incident((select cancel from ids), '70000000-0000-0000-0000-000000310102', 'cancellation', 'Paren', true);
select public.open_incident((select absent from ids), '70000000-0000-0000-0000-000000310103', 'not_performed', 'No vino', true);
select public.open_incident((select closed from ids), '70000000-0000-0000-0000-000000310104', 'not_performed', 'Tras cerrar', true);
select public.open_incident((select free from ids), '70000000-0000-0000-0000-000000310106', 'not_performed', 'Gratis', true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310102","role":"authenticated"}',true);
select public.open_incident((select direct from ids), '70000000-0000-0000-0000-000000310105', 'quality', 'Directo', true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310101","role":"authenticated"}',true);
create temp table inc on commit drop as select
 (select id from public.incidents where appointment_id = (select quality from ids)) as quality,
 (select id from public.incidents where appointment_id = (select cancel from ids)) as cancel,
 (select id from public.incidents where appointment_id = (select absent from ids)) as absent,
 (select id from public.incidents where appointment_id = (select closed from ids)) as closed,
 (select id from public.incidents where appointment_id = (select direct from ids)) as direct,
 (select id from public.incidents where appointment_id = (select free from ids)) as free;
grant select on inc to authenticated;
select public.add_incident_evidence((select quality from inc), '90000000-0000-0000-0000-000000310101',
 (select id from public.chat_messages where body = 'Mensaje aportado'), null, true);

-- Only administration reads the review and decides; knowing the RPC is not enough.
select throws_ok($$select public.admin_incidents('open')$$, '42501', 'permission_denied', 'a participant gets no review list');
select throws_ok($$select public.admin_settle_incident((select quality from inc), 'refund', null, 8500, 'Quiero mi dinero')$$,
 '42501', 'permission_denied', 'a participant cannot settle its own incident');
select throws_ok($$select public.admin_decide_incident_performance((select quality from inc), 'performed', 'Yo mismo')$$,
 '42501', 'permission_denied', 'a participant cannot decide on performance');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310103","role":"authenticated"}',true);
select throws_ok($$select public.admin_incidents('all')$$, '42501', 'permission_denied', 'a third account gets nothing');

-- The review shows the appointment, accepted conditions, movements and contributed evidence; never the place or chat.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310104","role":"authenticated"}',true);
create temp table review on commit drop as select r from jsonb_array_elements(public.admin_incidents('open')) r
 where (r->>'id')::uuid in (select quality from inc union all select cancel from inc union all select absent from inc
  union all select closed from inc union all select direct from inc union all select free from inc);
select is((select count(*)::integer from review), 6, 'the administrator lists the six open incidents');
select is((select r->'conditions'->>'scope' || '/' || (r->'conditions'->>'amount_cents') || '/' || (r->'payment'->>'pending_cents')
 || '/' || jsonb_array_length(r->'movements') || '/' || (r->'movements'->0->>'kind') || '/' || (r->'appointment'->>'stage')
 from review where r->>'id' = (select quality::text from inc)), 'Scope 1/8500/8500/1/paid/done',
 'accepted conditions, balance, movements and current stage are shown');
select is((select r->>'opened_by_role' || '/' || jsonb_array_length(r->'evidence') || '/' || (r->'evidence'->0->>'message_body')
 || '/' || (r->'evidence'->0->>'author_role') || '/' || (r->>'can_settle')
 from review where r->>'id' = (select quality::text from inc)), 'client/1/Mensaje aportado/client/true',
 'contributed evidence is shown with roles, and the pending balance can be settled');
select is((select count(*)::integer from review where r::text like '%Calle Secreta%' or r::text like '%43.05%'
 or r::text like '%Charla privada%'), 0, 'neither the exact place nor the rest of the chat is in the review');
select is((select string_agg(r->>'can_settle', ',' order by r->>'description') from review
 where r->>'id' in (select closed::text from inc union all select direct::text from inc union all select free::text from inc)),
 'false,false,false', 'after the delivery, direct and free there is no balance under the panel''s control');
select is((select count(*)::integer from public.chat_messages), 0, 'the administrator still cannot read the chat');
select is((select count(*)::integer from public.appointments), 0, 'nor the appointment rows directly');
select throws_ok($$select public.admin_incidents('bogus')$$, '22023', 'invalid_status', 'unknown list filters are refused');

-- Money: a reason is required, never more than the pending balance, and the balance must be the one reviewed.
select throws_ok($$select public.admin_settle_incident((select quality from inc), 'split', 3000, 8500, '  ')$$,
 '22023', 'reason_required', 'a reason is required');
select throws_ok($$select public.admin_settle_incident((select quality from inc), 'split', 8500, 8500, 'Reparto')$$,
 '22023', 'invalid_settlement', 'a partial refund is less than the pending balance');
select throws_ok($$select public.admin_settle_incident((select quality from inc), 'split', 9000, 8500, 'Reparto')$$,
 '22023', 'invalid_settlement', 'never more than the pending balance');
select throws_ok($$select public.admin_settle_incident((select quality from inc), 'split', 0, 8500, 'Reparto')$$,
 '22023', 'invalid_settlement', 'a partial refund is more than nothing');
select throws_ok($$select public.admin_settle_incident((select quality from inc), 'keep', null, 8500, 'Reparto')$$,
 '22023', 'invalid_settlement', 'only deliver, refund or split');
select throws_ok($$select public.admin_settle_incident((select quality from inc), 'split', 3000, 9999, 'Reparto')$$,
 'PT409', 'balance_changed', 'a balance changed since the review is not settled');
select throws_ok($$select public.admin_settle_incident('10000000-0000-0000-0000-000000310199', 'refund', null, 1, 'X')$$,
 '22023', 'incident_not_found', 'an unknown incident');

-- Partial refund and delivery of the rest, through the shared completion invariant.
select is((select r->>'settlement' || '/' || (r->>'settlement_refunded_cents') || '/' || (r->>'settlement_delivered_cents')
 || '/' || (r->>'status') from public.admin_settle_incident((select quality from inc), 'split', 3000, 8500,
 ' Trabajo incompleto ') r), 'split/3000/5500/open', 'a split refunds part and delivers the rest; performance is still open');
set local role postgres;
select is((select paid_cents || '/' || pending_cents || '/' || refunded_cents || '/' || delivered_cents
 from public.appointment_payments where appointment_id = (select quality from ids)), '8500/0/3000/5500', 'the balance adds up');
select is((select string_agg(kind || ':' || amount_cents || ':' || coalesce(actor_id::text, '-'), ',' order by created_at, kind)
 from public.payment_movements where appointment_id = (select quality from ids) and kind <> 'paid'),
 'refunded:3000:00000000-0000-0000-0000-000000310104,delivered:5500:00000000-0000-0000-0000-000000310104',
 'one refund and one delivery, recorded with the administrator as actor');
select is((select stage || '/' || completion from public.appointments where id = (select quality from ids)),
 'completed/administration', 'the appointment is closed by administration');
select is(private.delivery_blocked((select quality from ids)), false, 'a settled incident no longer blocks');
select is((select settlement_reason || '/' || settled_by || '/' || (settled_at is not null)
 from public.incidents where id = (select quality from inc)),
 'Trabajo incompleto/00000000-0000-0000-0000-000000310104/true', 'actor, date and reason are recorded');
set local role authenticated;
select is(public.admin_settle_incident((select quality from inc), 'split', 3000, 8500, 'Trabajo incompleto')->>'settlement',
 'split', 'repeating the same decision returns it');
select throws_ok($$select public.admin_settle_incident((select quality from inc), 'refund', null, 0, 'Otra idea')$$,
 'PT409', 'decision_already_recorded', 'an executed settlement cannot be changed');
set local role postgres;
select is((select count(*)::integer from public.payment_movements where appointment_id = (select quality from ids)), 3,
 'no duplicate movements');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310101","role":"authenticated"}',true);
select is(public.confirm_done((select quality from ids))->>'delivered_cents', '5500',
 'a late client confirmation returns the closed result without a second delivery');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310104","role":"authenticated"}',true);

-- Performance, separately: performed resolves this incident.
select throws_ok($$select public.admin_decide_incident_performance((select quality from inc), 'maybe', 'Duda')$$,
 '22023', 'invalid_decision', 'only performed, not performed or missing evidence');
select throws_ok($$select public.admin_decide_incident_performance((select quality from inc), 'performed', '')$$,
 '22023', 'reason_required', 'a reason is required for the performance decision');
select is((select r->>'performance_decision' || '/' || (r->>'status') || '/' || (r->>'resolved_at' is not null)
 from public.admin_decide_incident_performance((select quality from inc), 'performed', 'Las fotos muestran el trabajo') r),
 'performed/resolved/true', 'both decisions resolve the incident');
select throws_ok($$select public.admin_decide_incident_performance((select quality from inc), 'not_performed', 'Cambio')$$,
 'PT409', 'decision_already_recorded', 'an executed performance decision cannot be changed');
select is((select count(*)::integer from jsonb_array_elements(public.admin_incidents('resolved')) r where r->>'id' = (select quality::text from inc)
 and r->>'performance_decided_by_email' = 'resolution-admin@example.org' and r->>'settled_by_email' = 'resolution-admin@example.org'),
 1, 'the history shows who decided');
set local role postgres;
select throws_ok($$update public.incidents set settlement_reason = 'Reescrito' where id = (select quality from inc)$$,
 '42501', 'incident_resolution_locked', 'not even trusted SQL rewrites an executed resolution');
select throws_ok($$update public.incidents set status = 'open' where id = (select quality from inc)$$,
 '42501', 'incident_resolution_locked', 'a resolved incident is not reopened');
select is((select string_agg(recipient_id::text, ',' order by recipient_id) from public.appointment_events
 where appointment_id = (select quality from ids) and kind = 'incident_decision'),
 '00000000-0000-0000-0000-000000310101,00000000-0000-0000-0000-000000310101,'
 || '00000000-0000-0000-0000-000000310102,00000000-0000-0000-0000-000000310102',
 'both participants are told about each decision');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310101","role":"authenticated"}',true);
select is((select performance_decision || '/' || performance_reason || '/' || settlement || '/' || settlement_refunded_cents
 from public.incidents where id = (select quality from inc)), 'performed/Las fotos muestran el trabajo/split/3000',
 'the client reads the result and reasons');
select throws_ok($$select public.add_incident_evidence((select quality from inc), '90000000-0000-0000-0000-000000310109',
 null, 'Tarde', true)$$, 'PT409', 'incident_closed', 'no more evidence once resolved');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310104","role":"authenticated"}',true);

-- A full refund of a cancellation after the start does not prove the service did not happen.
select is((select r->>'settlement_refunded_cents' || '/' || (r->>'settlement_delivered_cents')
 from public.admin_settle_incident((select cancel from inc), 'refund', null, 8500, 'Se canceló a mitad') r), '8500/0',
 'a full refund');
set local role postgres;
select is((select pending_cents || '/' || refunded_cents || '/' || delivered_cents from public.appointment_payments
 where appointment_id = (select cancel from ids)), '0/8500/0', 'everything refunded, nothing delivered');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select cancel from ids)
 and kind = 'delivered'), 0, 'no delivery movement');
select is((select stage || '/' || completion || '/' || (done_at is null) from public.appointments
 where id = (select cancel from ids)), 'completed/administration/true', 'a started job is closed by administration');
set local role authenticated;
select is(public.admin_decide_incident_performance((select cancel from inc), 'performed', 'Se hizo media reparación')->>'status',
 'resolved', 'the refund and the performance decision are independent');

-- Performance first, money afterwards: the delivery stays blocked until the money is settled.
select is(public.admin_decide_incident_performance((select absent from inc), 'not_performed', 'Nadie acudió')->>'status',
 'open', 'with money still pending the incident stays open');
select throws_ok($$select private.complete_appointment((select absent from ids), null, 'no_answer')$$, '42501', null,
 'the clock invariant is not callable by accounts');
set local role postgres;
select is(private.delivery_blocked((select absent from ids)), true, 'and keeps blocking the delivery');
set local role authenticated;
select is((select r->>'status' || '/' || (r->>'settlement_delivered_cents')
 from public.admin_settle_incident((select absent from inc), 'deliver', null, 8500, 'Acuerdo entre las partes') r),
 'resolved/8500', 'delivering everything does not change the decision that it was not performed');
set local role postgres;
select is((select pending_cents || '/' || delivered_cents from public.appointment_payments
 where appointment_id = (select absent from ids)), '0/8500', 'delivered once');
select is((select performance_decision from public.incidents where id = (select absent from inc)), 'not_performed',
 'the performance decision is kept');
set local role authenticated;

-- After the delivery, direct and free: no money buttons, only the conclusion.
select throws_ok($$select public.admin_settle_incident((select closed from inc), 'refund', null, 0, 'Revertir')$$,
 'PT409', 'nothing_pending', 'delivered money cannot be refunded from a balance that no longer exists');
select is(public.admin_decide_incident_performance((select closed from inc), 'missing_evidence', 'Sin pruebas suficientes')->>'status',
 'resolved', 'an incident after the delivery is concluded');
set local role postgres;
select is((select pending_cents || '/' || refunded_cents || '/' || delivered_cents from public.appointment_payments
 where appointment_id = (select closed from ids)), '0/0/8500', 'the delivered money is untouched');
set local role authenticated;
select throws_ok($$select public.admin_settle_incident((select direct from inc), 'deliver', null, 0, 'Directo')$$,
 'PT409', 'nothing_pending', 'a direct payment is not handled by the panel');
select is(public.admin_decide_incident_performance((select direct from inc), 'performed', 'Reconocido')->>'status',
 'resolved', 'a direct incident is concluded');
set local role postgres;
select is((select stage from public.appointments where id = (select direct from ids)), 'started',
 'without money the stage is left to the participants');
set local role authenticated;
select throws_ok($$select public.admin_settle_incident((select free from inc), 'deliver', null, 0, 'Gratis')$$,
 'PT409', 'nothing_pending', 'a free visit has no simulated balance');
select is(public.admin_decide_incident_performance((select free from inc), 'performed', 'Hubo visita')->>'status',
 'resolved', 'a free incident is concluded');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310101","role":"authenticated"}',true);
select is((select r->>'stage' || '/' || (r->>'completion') from public.confirm_done((select free from ids)) r),
 'completed/administration', 'the resolution closed the job marked done, so the clock does not close it later as no_answer');

-- Nothing left open; the list filters.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310104","role":"authenticated"}',true);
select is((select count(*)::integer from jsonb_array_elements(public.admin_incidents('open')) r
 where (r->>'id')::uuid in (select quality from inc union all select cancel from inc union all select absent from inc
  union all select closed from inc union all select direct from inc union all select free from inc)), 0, 'nothing left open');
select is((select count(*)::integer from jsonb_array_elements(public.admin_incidents('all')) r
 where (r->>'id')::uuid in (select quality from inc union all select cancel from inc union all select absent from inc
  union all select closed from inc union all select direct from inc union all select free from inc)), 6, 'all in the history');

-- An administrator never resolves an incident of its own appointment.
set local role postgres;
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000310102');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310101","role":"authenticated"}',true);
select public.open_incident((select closed from ids), '70000000-0000-0000-0000-000000310107', 'quality', 'Otra', true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000310102","role":"authenticated"}',true);
select throws_ok($$select public.admin_decide_incident_performance((select id from public.incidents
 where request_id = '70000000-0000-0000-0000-000000310107'), 'performed', 'Lo mío')$$, '42501', 'permission_denied',
 'a dual-role administrator does not decide on its own appointment');
select is((select count(*)::integer from jsonb_array_elements(public.admin_incidents('all')) r
 where (r->>'id')::uuid in (select quality from inc)), 0, 'nor lists its own incidents');

set local role anon;
select throws_ok($$select public.admin_incidents('open')$$, '42501', null, 'anonymous callers get nothing');
select throws_ok($$select public.admin_settle_incident('10000000-0000-0000-0000-000000310199', 'refund', null, 0, 'x')$$, '42501', null,
 'anonymous callers cannot settle');

select * from finish();
rollback;
