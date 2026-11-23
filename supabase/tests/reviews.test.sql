-- Reviews of performed appointments (#28). Fixtures use the ...2801NN range.
-- 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
begin;
create extension if not exists pgtap with schema extensions;
select plan(45);

insert into public.concejos(ine_code, name, boundary) values
 ('33928', 'Prueba Resenas', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000280101','review-client@example.org',now()),
 ('00000000-0000-0000-0000-000000280102','review-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000280103','review-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000280101','00000000-0000-0000-0000-000000280102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000280101','Review plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33928","name":"Prueba Resenas"}]',array['es'],'000');

set local role authenticated;
-- Eight requests: 1 integrated confirmed by the client, 2 direct confirmed, 3 free closed by the clock,
-- 4 integrated done with a quality complaint, 5 integrated done with a dispute about whether it happened,
-- 6 integrated done, dispute later ruled «not performed», 7 started, 8 done without an answer yet.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000280101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000028010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle A 1', 43.05, -5.95) from generate_series(1, 8) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000028010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000028010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30,
 case when n = 3 then 0 else 8500 end, null, true, true) from generate_series(1, 8) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280101","role":"authenticated"}',true);
select public.confirm_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n),
 ('50000000-0000-0000-0000-00000028010' || n)::uuid, case when n = 2 then 'direct' else 'integrated' end)
 from generate_series(1, 8) n where n <> 3;
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope 3'));
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000280101') as confirmed,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000280102') as direct,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000280103') as silent,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000280104') as quality,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000280105') as disputed,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000280106') as ruled_out,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000280107') as started,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000280108') as waiting;
grant select on ids to authenticated, anon;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280102","role":"authenticated"}',true);
select public.start_work(id) from public.appointments where request_id::text like '30000000-0000-0000-0000-0000002801%';
select public.mark_done(id) from public.appointments where request_id::text like '30000000-0000-0000-0000-0000002801%'
 and id <> (select started from ids);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280101","role":"authenticated"}',true);
select public.confirm_done((select confirmed from ids)), public.confirm_done((select direct from ids));
select public.open_incident((select quality from ids), '70000000-0000-0000-0000-000000280104', 'quality',
 'Quedó una mancha', true);
select public.open_incident((select disputed from ids), '70000000-0000-0000-0000-000000280105', 'not_performed',
 'No vino nadie', true);
select public.open_incident((select ruled_out from ids), '70000000-0000-0000-0000-000000280106', 'not_performed',
 'No vino nadie', true);

-- The server clock (#27) closes the free appointment without an answer.
reset role;
update public.appointments set reminder_due_at = now() - interval '2 minutes', close_due_at = now() - interval '1 minute'
 where id = (select silent from ids);
select private.run_completion_clock();
select is((select count(*) from public.reviews where profile_id = '10000000-0000-0000-0000-000000280101'), 0::bigint,
 'closing without an answer never generates a review');

-- Permissions and validation.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280103","role":"authenticated"}',true);
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280109',
 5, 'Muy bien', true)$$, '42501', 'permission_denied', 'a third account cannot review someone else''s appointment');
select throws_ok($$select public.publish_review('10000000-0000-0000-0000-000000280199', '80000000-0000-0000-0000-000000280109',
 5, 'Muy bien', true)$$, '42501', 'permission_denied', 'an unknown appointment is not revealed');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280102","role":"authenticated"}',true);
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280108',
 1, 'Autoelogio', true)$$, '42501', 'permission_denied', 'the professional cannot review its own appointment');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280101","role":"authenticated"}',true);
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280101',
 5, 'Muy bien', false)$$, '22023', 'privacy_not_acknowledged', 'the public-content notice must be acknowledged');
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280101',
 0, 'Muy bien', true)$$, '22023', 'invalid_review', 'zero stars are refused');
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280101',
 6, 'Muy bien', true)$$, '22023', 'invalid_review', 'six stars are refused');
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280101',
 4, '   ', true)$$, '22023', 'invalid_review', 'a comment is required');
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280101',
 4, repeat('x', 2001), true)$$, '22023', 'invalid_review', 'comments longer than 2000 characters are refused');

-- States: only a performed appointment is reviewable.
select throws_ok($$select public.publish_review((select started from ids), '80000000-0000-0000-0000-000000280107',
 4, 'Pronto', true)$$, 'PT409', 'not_done', 'a job that is only started cannot be reviewed');
select throws_ok($$select public.publish_review((select waiting from ids), '80000000-0000-0000-0000-000000280108',
 4, 'Pronto', true)$$, 'PT409', 'awaiting_confirmation',
 'a job marked done is reviewed after the client confirms it (or acknowledges it in a quality complaint)');

-- One per appointment, idempotent by key.
select is((select r->>'stars' || '/' || (r->>'comment') || '/' || (r->>'profile_id')
 from public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280101', 5, ' Muy bien ', true) r),
 '5/Muy bien/10000000-0000-0000-0000-000000280101', 'the client publishes stars and a trimmed comment for the profile');
select is((select r->>'id' from public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280101',
 5, 'Muy bien', true) r), (select id::text from public.reviews where comment = 'Muy bien'),
 'retrying with the same key returns the same review');
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280101',
 3, 'Regular', true)$$, '22023', 'retry_conflict', 'the same key cannot carry another review');
select throws_ok($$select public.publish_review((select confirmed from ids), '80000000-0000-0000-0000-000000280111',
 3, 'Otra más', true)$$, 'PT409', 'review_exists', 'a second review of the same appointment is refused');
select is((select count(*) from public.reviews where comment in ('Muy bien', 'Regular', 'Otra más')), 1::bigint,
 'only one review exists for the appointment');
select is((select count(*) from public.appointment_events where appointment_id = (select confirmed from ids)
 and kind = 'review_published' and recipient_id = '00000000-0000-0000-0000-000000280102'), 1::bigint,
 'the professional is told once');

-- Direct payment and free appointments closed by the clock are reviewable too.
select is((select r->>'stars' from public.publish_review((select direct from ids), '80000000-0000-0000-0000-000000280102',
 4, 'Pagué en mano', true) r), '4', 'a direct-payment appointment is reviewable');
select is((select state from public.review_states((select conversation_id from public.appointments
 where id = (select silent from ids))) where appointment_id = (select silent from ids)), 'enabled',
 'a free appointment closed without an answer can be reviewed later');
select is((select r->>'stars' from public.publish_review((select silent from ids), '80000000-0000-0000-0000-000000280103',
 2, 'Tardó en venir', true) r), '2', 'the client reviews the silently closed appointment');

-- A quality complaint acknowledges the service: it does not block the review, even with the payment held.
select is((select state from public.review_states((select conversation_id from public.appointments
 where id = (select quality from ids))) where appointment_id = (select quality from ids)), 'enabled',
 'a quality complaint does not block the review');
select is((select r->>'stars' from public.publish_review((select quality from ids), '80000000-0000-0000-0000-000000280104',
 1, 'Quedó una mancha', true) r), '1', 'the client reviews while the quality complaint is open');
select is((select stage from public.appointments where id = (select quality from ids)), 'done',
 'reviewing neither confirms the job nor delivers the held payment');

-- A dispute about whether it happened keeps the review pending until the administrative decision.
select throws_ok($$select public.publish_review((select disputed from ids), '80000000-0000-0000-0000-000000280105',
 3, 'No sé', true)$$, 'PT409', 'performance_disputed', 'a dispute about whether it happened keeps the review pending');
reset role;
update public.incidents set status = 'resolved', performance_decision = 'missing_evidence'
 where appointment_id = (select disputed from ids);
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280101","role":"authenticated"}',true);
select throws_ok($$select public.publish_review((select disputed from ids), '80000000-0000-0000-0000-000000280105',
 3, 'No sé', true)$$, 'PT409', 'performance_disputed', 'missing evidence keeps the dispute about performance pending');
reset role;
update public.incidents set performance_decision = 'performed' where appointment_id = (select disputed from ids);
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280101","role":"authenticated"}',true);
select is((select r->>'stars' from public.publish_review((select disputed from ids), '80000000-0000-0000-0000-000000280105',
 3, 'Al final vino', true) r), '3', 'once performance is recorded the review is enabled, whatever happens to the money');
reset role;
-- The money went back to the client, yet the decision on performance is what counts (the refund is not deduced).
update public.incidents set status = 'resolved', performance_decision = 'not_performed'
 where appointment_id = (select ruled_out from ids);
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280101","role":"authenticated"}',true);
select throws_ok($$select public.publish_review((select ruled_out from ids), '80000000-0000-0000-0000-000000280106',
 1, 'Nunca vino', true)$$, 'PT409', 'not_performed', 'recorded as not performed: no review');

-- States as the client sees them.
select is((select string_agg(state, ',' order by a.request_id) from public.review_states((select conversation_id
 from public.appointments where id = (select confirmed from ids))) s join public.appointments a on a.id = s.appointment_id),
 'published,published,published,published,published,not_performed,not_done,awaiting_confirmation',
 'each appointment of the conversation states whether it can be reviewed');
select is((select review->>'comment' from public.review_states((select conversation_id from public.appointments
 where id = (select confirmed from ids))) where appointment_id = (select confirmed from ids)), 'Muy bien',
 'the published review is returned with its state');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280102","role":"authenticated"}',true);
select is((select review->>'stars' from public.review_states((select conversation_id from public.appointments
 where id = (select confirmed from ids))) where appointment_id = (select confirmed from ids)), '5',
 'the professional sees the review of its appointment');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280103","role":"authenticated"}',true);
select is((select count(*) from public.review_states((select conversation_id from public.appointments
 where id = (select confirmed from ids)))), 0::bigint, 'a third account learns nothing about the appointments');

-- No direct writes: reviews are neither forged, edited nor deleted outside publish_review.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000280102","role":"authenticated"}',true);
select throws_ok($$insert into public.reviews(profile_id, stars, comment)
 values ('10000000-0000-0000-0000-000000280101', 5, 'Falsa')$$, '42501', null, 'reviews cannot be inserted directly');
select throws_ok($$update public.reviews set stars = 5$$, '42501', null, 'the professional cannot edit reviews');
select throws_ok($$delete from public.reviews$$, '42501', null, 'the professional cannot delete reviews');
reset role;
select throws_ok($$update public.reviews set stars = 5 where comment = 'Tardó en venir'$$, '42501', 'review_locked',
 'a published review is not rewritten even with trusted SQL');

-- Public profile: stars, comment and date only; the aggregate is recomputed from real reviews.
select hasnt_column('public', 'reviews', 'appointment_id', 'public reviews do not link to the appointment');
select hasnt_column('public', 'reviews', 'author_id', 'public reviews do not name the client account');
select is((select rating::text || '/' || review_count from public.approved_profiles
 where profile_id = '10000000-0000-0000-0000-000000280101'), '3.00/5',
 'the profile shows the average and the number of real reviews');
set local role anon;
select is((select count(*) from public.reviews where profile_id = '10000000-0000-0000-0000-000000280101'), 5::bigint,
 'anyone reads the published reviews of a profile');
select is((select string_agg(stars::text, ',' order by stars) from public.reviews
 where profile_id = '10000000-0000-0000-0000-000000280101'), '1,2,3,4,5', 'with their stars');
select throws_ok($$select public.publish_review((select started from ids), '80000000-0000-0000-0000-000000280199',
 5, 'x', true)$$, '42501', null, 'anonymous callers cannot publish');
select throws_ok($$select * from public.review_states('10000000-0000-0000-0000-000000280199')$$, '42501', null,
 'anonymous callers cannot read review states');
reset role;
select is((select count(*)::int from information_schema.column_privileges where table_schema = 'public'
 and table_name = 'reviews' and grantee = 'anon' and privilege_type = 'SELECT'), 5,
 'anonymous readers get exactly the public columns');
select ok(not has_table_privilege('authenticated', 'private.review_sources', 'select'),
 'who wrote which review stays private');
select is((select count(*) from private.review_sources), 5::bigint, 'each review keeps its appointment and author');

select * from finish();
rollback;
