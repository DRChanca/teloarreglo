-- Incidents and private evidence (#26). Fixtures use the ...2601NN range.
-- 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
begin;
create extension if not exists pgtap with schema extensions;
select plan(58);

insert into public.concejos(ine_code, name, boundary) values
 ('33926', 'Prueba Incidencias', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000260101','incident-client@example.org',now()),
 ('00000000-0000-0000-0000-000000260102','incident-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000260103','incident-other@example.org',now()),
 ('00000000-0000-0000-0000-000000260104','incident-admin@example.org',now());
insert into private.administrators(user_id) values ('00000000-0000-0000-0000-000000260104');
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000260101','00000000-0000-0000-0000-000000260102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000260101','Incident plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33926","name":"Prueba Incidencias"}]',array['es'],'000');

set local role authenticated;
-- Six requests: 1 integrated started (cancellation after the start), 2 integrated marked done (quality complaint),
-- 3 integrated completed (problem after the close), 4 integrated never started, 5 direct started, 6 free started.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000260101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000026010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle A 1', 43.05, -5.95) from generate_series(1, 6) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000026010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000026010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30,
 case when n = 6 then 0 else 8500 end, null, true, true) from generate_series(1, 6) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n),
 ('50000000-0000-0000-0000-00000026010' || n)::uuid, case when n = 5 then 'direct' else 'integrated' end)
 from generate_series(1, 6) n where n <> 6;
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope 6'));
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000260101') as started,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000260102') as done,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000260103') as completed,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000260104') as unstarted,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000260105') as direct,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000260106') as free;
grant select on ids to authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260102","role":"authenticated"}',true);
select public.start_work(id) from public.appointments where id in (select started from ids union all select done from ids
 union all select completed from ids union all select direct from ids union all select free from ids);
select public.mark_done((select done from ids)), public.mark_done((select completed from ids));
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select public.confirm_done((select completed from ids));

-- Opening: only participants, with the visibility notice acknowledged, on a started, done or completed appointment.
select throws_ok($$select public.open_incident((select started from ids), '70000000-0000-0000-0000-000000260101',
 'cancellation', 'Ya no quiero seguir', false)$$, '22023', 'visibility_not_acknowledged',
 'the visibility notice must be acknowledged before opening');
select throws_ok($$select public.open_incident((select started from ids), '70000000-0000-0000-0000-000000260101',
 'refund', 'Quiero mi dinero', true)$$, '22023', 'invalid_incident', 'unknown kinds are refused');
select throws_ok($$select public.open_incident((select started from ids), '70000000-0000-0000-0000-000000260101',
 'quality', '   ', true)$$, '22023', 'invalid_incident', 'a description is required');
select throws_ok($$select public.open_incident((select unstarted from ids), '70000000-0000-0000-0000-000000260101',
 'quality', 'Problema', true)$$, 'PT409', 'incompatible_state',
 'before the start there is no incident: the appointment is cancelled with a refund');
select throws_ok($$select public.open_incident((select done from ids), '70000000-0000-0000-0000-000000260101',
 'cancellation', 'Cancelar', true)$$, 'PT409', 'incompatible_state',
 'a cancellation request only exists while the job is started');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260103","role":"authenticated"}',true);
select throws_ok($$select public.open_incident((select started from ids), '70000000-0000-0000-0000-000000260109',
 'quality', 'Intruso', true)$$, '42501', 'permission_denied', 'a third account cannot open an incident');
select throws_ok($$select public.open_incident('10000000-0000-0000-0000-000000260199', '70000000-0000-0000-0000-000000260109',
 'quality', 'Nada', true)$$, '42501', 'permission_denied', 'an unknown appointment is not revealed');

-- Cancellation after the start: an incident, never an automatic refund; the stage and the balance are kept.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select is((select r->>'kind' || '/' || (r->>'performance') || '/' || (r->>'status') || '/' || (r->>'appointment_stage')
 || '/' || (r->>'blocks_delivery') || '/' || (r->>'pending_cents')
 from public.open_incident((select started from ids), '70000000-0000-0000-0000-000000260101', 'cancellation',
 ' Ya no quiero seguir ', true) r), 'cancellation/disputed/open/started/true/8500',
 'a cancellation after the start opens a blocking incident and keeps the stage and the pending balance');
select is((select stage from public.appointments where id = (select started from ids)), 'started', 'the stage is kept');
select is((select pending_cents || '/' || refunded_cents || '/' || delivered_cents from public.appointment_payments
 where appointment_id = (select started from ids)), '8500/0/0', 'no automatic refund or delivery');
select is((select count(*)::integer from public.payment_movements where appointment_id = (select started from ids)), 1,
 'no movement besides the payment');
select is(public.open_incident((select started from ids), '70000000-0000-0000-0000-000000260101', 'cancellation',
 'Ya no quiero seguir', true)->>'id', (select id::text from public.incidents where appointment_id = (select started from ids)),
 'retrying with the same key returns the same incident');
select throws_ok($$select public.open_incident((select started from ids), '70000000-0000-0000-0000-000000260101',
 'quality', 'Otra cosa', true)$$, '22023', 'retry_conflict', 'the same key cannot open different content');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select started from ids)
 and kind = 'incident_opened' and actor_id = '00000000-0000-0000-0000-000000260101'
 and recipient_id = '00000000-0000-0000-0000-000000260102'), 1, 'one notice tells the professional');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260102","role":"authenticated"}',true);
select throws_ok($$select public.open_incident((select started from ids), '70000000-0000-0000-0000-000000260102',
 'not_performed', 'Otra', true)$$, 'PT409', 'incident_open',
 'while one is open the other participant adds evidence to it instead of opening another');
select is((select kind || '/' || status from public.incidents where appointment_id = (select started from ids)),
 'cancellation/open', 'the professional sees the incident');

-- A previous incident blocks the delivery: the client's confirmation and the clock.
select public.mark_done((select started from ids));
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select throws_ok($$select public.confirm_done((select started from ids))$$, 'PT409', 'incident_blocking',
 'the client cannot confirm while the incident is open');
reset role;
update public.appointments set close_due_at = now() - interval '1 minute' where id = (select started from ids);
select throws_ok($$select private.complete_appointment((select started from ids), null, 'no_answer')$$, 'PT409',
 'incident_blocking', 'the clock cannot close while the incident is open');
select is((select stage || '/' || p.pending_cents || '/' || p.delivered_cents from public.appointments a
 join public.appointment_payments p on p.appointment_id = a.id where a.id = (select started from ids)),
 'done/8500/0', 'nothing is delivered while blocked');
set local role authenticated;

-- A quality complaint before the delivery: the service is acknowledged, the delivery still waits.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select is((select r->>'performance' || '/' || (r->>'appointment_stage') || '/' || (r->>'blocks_delivery')
 from public.open_incident((select done from ids), '70000000-0000-0000-0000-000000260103', 'quality',
 'Gotea un poco', true) r), 'acknowledged/done/true', 'a quality complaint acknowledges the service and blocks');
select throws_ok($$select public.confirm_done((select done from ids))$$, 'PT409', 'incident_blocking',
 'a quality complaint also stops the delivery until it is reviewed');

-- After the close: a communication without any automatic reversal.
select is((select r->>'performance' || '/' || (r->>'appointment_stage') || '/' || (r->>'blocks_delivery')
 || '/' || (r->>'delivered_cents') from public.open_incident((select completed from ids),
 '70000000-0000-0000-0000-000000260104', 'not_performed', 'No vino nadie', true) r),
 'disputed/completed/false/8500', 'a dispute after the close blocks nothing and records what was delivered');
select is((select stage || '/' || p.delivered_cents || '/' || p.refunded_cents from public.appointments a
 join public.appointment_payments p on p.appointment_id = a.id where a.id = (select completed from ids)),
 'completed/8500/0', 'delivered money is not reverted automatically');

-- Direct and free appointments: incidents without managed money; the professional may open one too.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260102","role":"authenticated"}',true);
select is((select (r->>'payment_mode') || '/' || coalesce(r->>'pending_cents', 'null') from public.open_incident((select direct from ids),
 '70000000-0000-0000-0000-000000260105', 'cancellation', 'El cliente no abre', true) r), 'direct/0',
 'direct: the professional opens an incident; no managed money');
select is((select coalesce(r->>'payment_mode', 'none') from public.open_incident((select free from ids),
 '70000000-0000-0000-0000-000000260106', 'quality', 'Faltaba material', true) r), 'none', 'free: no payment is invented');
select public.mark_done((select free from ids));
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select throws_ok($$select public.confirm_done((select free from ids))$$, 'PT409', 'incident_blocking',
 'an open incident also stops the completion of a free appointment');

-- Evidence: concrete messages of the appointment's conversation or notes, after the visibility notice.
-- Chat: a text from the client, two photos from the professional (only one will be contributed), and a message in
-- another conversation of the third account.
select public.send_text((select conversation_id from public.appointments where id = (select started from ids)),
 '80000000-0000-0000-0000-000000260101', 'Mensaje privado del chat');
select public.send_text((select conversation_id from public.appointments where id = (select started from ids)),
 '80000000-0000-0000-0000-000000260102', 'Otro mensaje que nadie aporta');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260102","role":"authenticated"}',true);
select public.request_chat_attachment((select conversation_id from public.appointments where id = (select started from ids)),
 ('80000000-0000-0000-0000-00000026011' || n)::uuid, 'photo', 'image/jpeg', repeat('a', 64)) from generate_series(1, 2) n;
reset role;
insert into storage.objects(bucket_id, name, metadata)
 select 'chat-attachments', c.id || '/80000000-0000-0000-0000-00000026011' || n || '.jpg', '{"mimetype":"image/jpeg","size":1234}'
 from public.appointments a join public.conversations c on c.id = a.conversation_id, generate_series(1, 2) n
 where a.id = (select started from ids);
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260102","role":"authenticated"}',true);
select public.submit_chat_attachment((select conversation_id from public.appointments where id = (select started from ids)),
 ('80000000-0000-0000-0000-00000026011' || n)::uuid) from generate_series(1, 2) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260103","role":"authenticated"}',true);
select public.send_text((public.open_conversation('10000000-0000-0000-0000-000000260101')->>'id')::uuid,
 '80000000-0000-0000-0000-000000260103', 'Conversación ajena');
reset role;
create temp table refs on commit drop as select
 (select id from public.incidents where appointment_id = (select started from ids)) as incident,
 (select id from public.chat_messages where request_id = '80000000-0000-0000-0000-000000260101') as text_message,
 (select id from public.chat_messages where request_id = '80000000-0000-0000-0000-000000260111') as photo,
 (select attachment_path from public.chat_messages where request_id = '80000000-0000-0000-0000-000000260111') as photo_path,
 (select attachment_path from public.chat_messages where request_id = '80000000-0000-0000-0000-000000260112') as kept_photo_path,
 (select id from public.chat_messages where request_id = '80000000-0000-0000-0000-000000260103') as foreign_message;
grant select on refs to authenticated;
set local role authenticated;

select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select throws_ok($$select public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260101',
 (select text_message from refs), null, false)$$, '22023', 'visibility_not_acknowledged',
 'nothing is contributed without the visibility notice');
select throws_ok($$select public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260101',
 (select text_message from refs), 'Y una nota', true)$$, '22023', 'invalid_evidence', 'either a message or a note');
select throws_ok($$select public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260101',
 null, '  ', true)$$, '22023', 'invalid_evidence', 'an empty note is refused');
select throws_ok($$select public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260101',
 (select foreign_message from refs), null, true)$$, '42501', 'permission_denied',
 'a message from another conversation cannot be contributed');
select is((select r->>'kind' || '/' || (r->>'message_body') || '/' || (r->>'message_author_id')
 from public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260101',
 (select text_message from refs), null, true) r),
 'message/Mensaje privado del chat/00000000-0000-0000-0000-000000260101', 'the client contributes a concrete message');
select is(public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260101',
 (select text_message from refs), null, true)->>'id',
 (select id::text from public.incident_evidence where request_id = '90000000-0000-0000-0000-000000260101'),
 'a retry with the same key returns the same evidence');
select throws_ok($$select public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260101',
 null, 'Otra cosa', true)$$, '22023', 'retry_conflict', 'the same key cannot contribute something else');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select started from ids)
 and kind = 'incident_evidence' and recipient_id = '00000000-0000-0000-0000-000000260102'), 1,
 'the other participant is told once');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260102","role":"authenticated"}',true);
select is((select r->>'attachment_kind' || '/' || (r->>'attachment_path' = (select photo_path from refs))
 from public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260102',
 (select photo from refs), null, true) r), 'photo/true', 'the professional contributes a concrete photo');
select is(public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260103',
 (select text_message from refs), null, true)->>'request_id', '90000000-0000-0000-0000-000000260101',
 'a message already contributed is not duplicated');
select is((select r->>'kind' || '/' || (r->>'note') from public.add_incident_evidence((select incident from refs),
 '90000000-0000-0000-0000-000000260104', null, ' Llegué y no había nadie ', true) r), 'note/Llegué y no había nadie',
 'the professional adds a note');
select is((select count(*)::integer from public.incident_evidence where incident_id = (select incident from refs)), 3,
 'both participants see the three pieces of evidence');
select throws_ok($$update public.incident_evidence set note = 'Cambiada'$$, '42501', null, 'evidence cannot be edited');
select throws_ok($$delete from public.incident_evidence$$, '42501', null, 'evidence cannot be deleted');
select throws_ok($$update public.incidents set description = 'Cambiada'$$, '42501', null, 'the incident cannot be edited');

-- Third accounts learn nothing; the administrator reads only the incident and what was contributed.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260103","role":"authenticated"}',true);
select is((select count(*)::integer from public.incidents), 0, 'a third account sees no incident');
select is((select count(*)::integer from public.incident_evidence), 0, 'a third account sees no evidence');
select throws_ok($$select public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260109',
 null, 'Intruso', true)$$, '42501', 'permission_denied', 'a third account cannot contribute');
select is(public.can_read_incident_evidence((select photo_path from refs)), false,
 'a third account cannot read a contributed photo');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260104","role":"authenticated"}',true);
select is((select count(*)::integer from public.incidents), 5, 'the administrator sees the incidents');
select is((select count(*)::integer from public.incident_evidence), 3, 'the administrator sees the contributed evidence');
select is((select count(*)::integer from public.chat_messages), 0, 'the administrator does not see the chat');
select is((select count(*)::integer from public.appointments), 0, 'the incident gives no access to the appointment row');
select is((select array_agg(name) from storage.objects where bucket_id = 'chat-attachments'),
 array[(select photo_path from refs)], 'the administrator reads only the contributed photo');
select is(public.can_read_chat_attachment((select kept_photo_path from refs)), false,
 'the administrator still has no access to chat attachments');
select throws_ok($$select public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260108',
 null, 'Nota del administrador', true)$$, '42501', 'permission_denied', 'the administrator does not contribute');
select throws_ok($$select public.open_incident((select done from ids), '70000000-0000-0000-0000-000000260108',
 'quality', 'Administrador', true)$$, '42501', 'permission_denied', 'the administrator does not open incidents');

-- While open only: a reviewed incident keeps its evidence and accepts no more.
reset role;
update public.incidents set status = 'resolved' where id = (select incident from refs);
select throws_ok($$update public.incident_evidence set note = 'Cambiada'$$, '42501', 'evidence_locked',
 'not even trusted SQL rewrites evidence');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000260101","role":"authenticated"}',true);
select throws_ok($$select public.add_incident_evidence((select incident from refs), '90000000-0000-0000-0000-000000260105',
 null, 'Tarde', true)$$, 'PT409', 'incident_closed', 'nothing is added once the incident is no longer open');
select is((select count(*)::integer from public.incident_evidence where incident_id = (select incident from refs)), 3,
 'the evidence is kept');
set local role anon;
select throws_ok($$select public.add_incident_evidence('10000000-0000-0000-0000-000000260199',
 '90000000-0000-0000-0000-000000260109', null, 'x', true)$$, '42501', null, 'anonymous callers cannot contribute');
select throws_ok($$select count(*) from public.incidents$$, '42501', null, 'anonymous callers cannot read incidents');

select * from finish();
rollback;
