-- Ventana de llegada (#82, ADR 0002): «informar posición», «dejar de compartir», «leer llegada» and the per-minute
-- arrival clock. Fixtures use the ...8201NN range. The address of every appointment is 'Calle A 1' at 43.05, -5.95.
-- Inside one transaction now() is fixed, so time is simulated by moving the agreed start of the appointment
-- (its accepted proposal) and the server mark of the last position relative to now().
begin;
create extension if not exists pgtap with schema extensions;
select plan(61);

insert into public.concejos(ine_code, name, boundary) values
 ('33982', 'Prueba Llegada', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000820101','arrival-client@example.org',now()),
 ('00000000-0000-0000-0000-000000820102','arrival-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000820103','arrival-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000820101','00000000-0000-0000-0000-000000820102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000820101','Arrival plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33982","name":"Prueba Llegada"}]',array['es'],'000');

-- Five requests of the same client: 1 main, 2 another appointment of the professional (to be started),
-- 3 to be cancelled, 4 a far-away second confirmed one, 5 left pending (never confirmed).
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000820101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000820101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000082010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle A 1', 43.05, -5.95) from generate_series(1, 5) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000820102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000082010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000082010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30, 0, null, true, true) from generate_series(1, 4) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000820101","role":"authenticated"}',true);
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n)) from generate_series(1, 4) n;
reset role;
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000820101') as main,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000820102') as other,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000820103') as cancelled,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000820104') as second,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000820105') as pending;
grant select on ids to authenticated;

-- Simulated time: the agreed start of an appointment is `minutes` from now.
create function pg_temp.starts_in(p_appointment_id uuid, p_minutes integer) returns void language sql as $$
    update public.appointment_proposals set start_at = now() + make_interval(mins => p_minutes),
        end_at = now() + make_interval(mins => p_minutes + 30)
      where appointment_id = p_appointment_id and status = 'accepted'
$$;
create function pg_temp.as_user(p_suffix text) returns void language sql as $$
    select set_config('request.jwt.claims',
        '{"sub":"00000000-0000-0000-0000-0000008201' || p_suffix || '","role":"authenticated"}', true)
$$;
-- What «informar posición» answers the professional's app, without the timestamps.
create function pg_temp.report(p_appointment_id uuid, p_lat double precision, p_lng double precision) returns text
language sql as $$
    select r->>'window' || '/' || (r->>'accepted') from public.report_arrival_position(p_appointment_id, p_lat, p_lng, 15) r
$$;

-- The window opens 60 min before the start.
select pg_temp.starts_in((select main from ids), 61);
set local role authenticated;
select pg_temp.as_user('02');
select is(pg_temp.report((select main from ids), 43.10, -5.95), 'not_yet_open/false', 'closed before −60 min: not yet open');
select is((select (r->>'opens_at')::timestamptz - now() from public.report_arrival_position((select main from ids), 43.10, -5.95, 15) r),
 interval '1 minute', 'a rejected position says when the window opens');
reset role;
select is((select count(*)::integer from private.arrival_positions), 0, 'nothing is stored before the window');
select pg_temp.starts_in((select main from ids), 60);
set local role authenticated;
select pg_temp.as_user('02');
select is(pg_temp.report((select main from ids), 43.10, -5.95), 'open/true', 'open at −60 min: accepted');
select is((select (r->>'closes_at')::timestamptz - now() from public.report_arrival_position((select main from ids), 43.10, -5.95, 15) r),
 interval '120 minutes', 'an accepted position says when the window closes (start + 60 min)');

-- One row per appointment, overwritten, with the server time.
reset role;
update private.arrival_positions set reported_at = now() - interval '1 minute';
set local role authenticated;
select pg_temp.as_user('02');
select is((select (r->>'reported_at')::timestamptz from public.report_arrival_position((select main from ids), 43.09, -5.95, 8) r),
 now(), 'the answer carries the server time of the position');
reset role;
select is((select array_agg(latitude || '/' || accuracy_m || '/' || (reported_at = now())) from private.arrival_positions),
 array['43.09/8/true'], 'one row per appointment, overwritten with the last position and the server time');

-- Another started appointment of the professional: the window opens only 30 min before.
select pg_temp.starts_in((select other from ids), -10);
set local role authenticated;
select pg_temp.as_user('02');
select public.start_work((select other from ids));
reset role;
select pg_temp.starts_in((select main from ids), 45);
set local role authenticated;
select pg_temp.as_user('02');
select is(pg_temp.report((select main from ids), 43.10, -5.95), 'not_yet_open/false',
 'with another appointment started, closed at −45 min');
select is((select (r->>'opens_at')::timestamptz - now() from public.report_arrival_position((select main from ids), 43.10, -5.95, 15) r),
 interval '15 minutes', 'and it opens at −30 min');
reset role;
select pg_temp.starts_in((select main from ids), 30);
set local role authenticated;
select pg_temp.as_user('02');
select is(pg_temp.report((select main from ids), 43.10, -5.95), 'open/true', 'with another appointment started, open at −30 min');
-- (Another appointment that starts while the window is open is the same instant rule: at −45 min it is closed above.)

-- Between the start and +60 min it stays open while the appointment is confirmed; at +60 min it closes for good.
reset role;
select pg_temp.starts_in((select main from ids), -59);
set local role authenticated;
select pg_temp.as_user('02');
select is(pg_temp.report((select main from ids), 43.10, -5.95), 'open/true', 'still open 59 min after the start if not started');
reset role;
select pg_temp.starts_in((select main from ids), -60);
set local role authenticated;
select pg_temp.as_user('02');
select is((select r - 'reported_at' from public.report_arrival_position((select main from ids), 43.10, -5.95, 15) r),
 '{"window": "closed", "accepted": false, "opens_at": null, "closes_at": null}'::jsonb, 'closed for good at +60 min');

-- Cancellation and «Comenzar trabajo» close it for good.
reset role;
select pg_temp.starts_in((select cancelled from ids), 20);
select pg_temp.starts_in((select main from ids), 20);
set local role authenticated;
select pg_temp.as_user('02');
select is(pg_temp.report((select cancelled from ids), 43.10, -5.95), 'open/true', 'open before the cancellation');
select public.cancel_appointment((select cancelled from ids));
select is(pg_temp.report((select cancelled from ids), 43.10, -5.95), 'closed/false', 'closed by the cancellation');
select public.start_work((select main from ids));
select is(pg_temp.report((select main from ids), 43.10, -5.95), 'closed/false', 'closed by «Comenzar trabajo»');

-- An appointment never confirmed, another professional or the client: rejected without storing anything.
reset role;
update public.appointment_proposals set start_at = now() + interval '10 minutes' where appointment_id = (select pending from ids);
set local role authenticated;
select pg_temp.as_user('02');
select is(pg_temp.report((select pending from ids), 43.10, -5.95), 'closed/false', 'an appointment not confirmed is rejected');
select pg_temp.as_user('03');
select throws_ok(format('select public.report_arrival_position(%L, 43.1, -5.95, 15)', (select second from ids)),
 '42501', 'permission_denied', 'another account cannot report a position');
select pg_temp.as_user('01');
select throws_ok(format('select public.report_arrival_position(%L, 43.1, -5.95, 15)', (select second from ids)),
 '42501', 'permission_denied', 'the client cannot report a position');
select pg_temp.as_user('02');
select throws_ok(format('select public.report_arrival_position(%L, 91, -5.95, 15)', (select second from ids)),
 '22023', 'invalid_position', 'an impossible position is rejected');
reset role;
select is((select count(*)::integer from private.arrival_positions where appointment_id in
 (select pending from ids union all select second from ids)), 0, 'rejected positions are not stored');

-- «Estado de la ventana» (professional): the window without sending any position, so that the app sends one only
-- while it is open (#85).
reset role;
select pg_temp.starts_in((select second from ids), 31);
set local role authenticated;
select pg_temp.as_user('02');
select is((select r - 'opens_at' - 'closes_at' from public.arrival_sharing_window((select second from ids)) r),
 '{"window": "not_yet_open"}'::jsonb, 'the window state answers «aún no abierta» with nothing else');
select is((select (r->>'opens_at')::timestamptz - now() from public.arrival_sharing_window((select second from ids)) r),
 interval '1 minute', 'and says when it opens (−30 min: main is started)');
reset role;
select pg_temp.starts_in((select second from ids), 30);
set local role authenticated;
select pg_temp.as_user('02');
select is((select r->>'window' || '/' || ((r->>'closes_at')::timestamptz - now())
 from public.arrival_sharing_window((select second from ids)) r), 'open/01:30:00', 'open, with its closing');
select is(public.arrival_sharing_window((select cancelled from ids)),
 '{"window": "closed", "opens_at": null, "closes_at": null}'::jsonb, 'a cancelled appointment reads closed');
select pg_temp.as_user('01');
select throws_ok(format('select public.arrival_sharing_window(%L)', (select second from ids)), '42501', 'permission_denied',
 'the client cannot ask the professional''s window state');
select pg_temp.as_user('03');
select throws_ok(format('select public.arrival_sharing_window(%L)', (select second from ids)), '42501', 'permission_denied',
 'another account cannot ask it');
reset role;
select is((select count(*)::integer from private.arrival_positions where appointment_id = (select second from ids)), 0,
 'asking the window state stores no position');

-- «Leer llegada» for the second appointment (main is now started, so its window is the 30 min one).
create function pg_temp.distance_m(lat1 double precision, lng1 double precision, lat2 double precision,
    lng2 double precision) returns double precision language sql as $$
    select extensions.st_distance(extensions.st_setsrid(extensions.st_makepoint(lng1, lat1), 4326)::extensions.geography,
        extensions.st_setsrid(extensions.st_makepoint(lng2, lat2), 4326)::extensions.geography)
$$;
select pg_temp.starts_in((select second from ids), 40);
set local role authenticated;
select pg_temp.as_user('01');
select is(public.appointment_arrival((select second from ids)), '{"state": "closed"}'::jsonb,
 'the client reads «cerrada» outside the window');
reset role;
select pg_temp.starts_in((select second from ids), 20);
set local role authenticated;
select pg_temp.as_user('01');
select is(public.appointment_arrival((select second from ids)) - 'closes_at', '{"state": "not_sharing"}'::jsonb,
 '«abierta sin compartir» without any position');

-- Beyond 2 km: a 500 m circle around a centre displaced at random, never the position itself.
select pg_temp.as_user('02');
select is(pg_temp.report((select second from ids), 43.10, -5.95), 'open/true', 'the professional shares 5.6 km away');
select pg_temp.as_user('01');
create temp table far_read on commit drop as select public.appointment_arrival((select second from ids)) as r;
select is((select r->>'state' || '/' || (r->>'precision') || '/' || (r->>'radius_m') from far_read), 'sharing/area/500',
 'beyond 2 km the client receives a 500 m circle');
select ok((select pg_temp.distance_m((r->>'latitude')::float8, (r->>'longitude')::float8, 43.10, -5.95) between 0.001 and 500
 from far_read), 'the circle contains the real position without being centred on it');
select ok((select r::text !~ '43\.1[,}]|"accuracy' from far_read), 'the exact position never leaves the server beyond 2 km');
select is((select (r->>'updated_at')::timestamptz from far_read), now(), 'the circle carries the time of the last update');
select is(public.appointment_arrival((select second from ids)), (select r from far_read),
 'repeating the read does not move the centre');
select ok((select bool_and(pg_temp.distance_m((a->>'latitude')::float8, (a->>'longitude')::float8, 43.10, -5.95) <= 375.001)
 from (select public.appointment_arrival((select second from ids)) as a) x), 'the centre lies within 375 m of the position');
-- Reporting again from the same place keeps the centre: many reports never average out to the position (#72).
select pg_temp.as_user('02');
select pg_temp.report((select second from ids), 43.10, -5.95) from generate_series(1, 5);
select pg_temp.as_user('01');
select is(public.appointment_arrival((select second from ids)) - 'updated_at', (select r - 'updated_at' from far_read),
 'repeated reports from the same position keep the centre');
-- The centre is kept while the new position stays within 375 m of it, and drawn again only beyond.
reset role;
update private.arrival_positions set area_lat = 43.1027, area_lng = -5.95 where appointment_id = (select second from ids);
set local role authenticated;
select pg_temp.as_user('02');
select pg_temp.report((select second from ids), 43.1001, -5.95);
select pg_temp.as_user('01');
select is((select (a->>'latitude')::float8 || '/' || (a->>'longitude')::float8
 from (select public.appointment_arrival((select second from ids)) as a) x), '43.1027/-5.95',
 'a nearby position (about 290 m from the centre) keeps the centre');
select pg_temp.as_user('02');
select pg_temp.report((select second from ids), 43.0990, -5.95);
select pg_temp.as_user('01');
create temp table moved_read on commit drop as select public.appointment_arrival((select second from ids)) as r;
select isnt((select r->>'latitude' from moved_read), '43.1027', 'beyond 375 m of the centre a new centre is drawn');
select ok((select pg_temp.distance_m((r->>'latitude')::float8, (r->>'longitude')::float8, 43.0990, -5.95) <= 375.001
 from moved_read), 'and the circle still contains the real position');
select pg_temp.as_user('02');
select pg_temp.report((select second from ids), 43.12, -5.95);
select pg_temp.as_user('01');
create temp table far_move on commit drop as select public.appointment_arrival((select second from ids)) as r;
select ok((select r->>'precision' = 'area' and pg_temp.distance_m((r->>'latitude')::float8, (r->>'longitude')::float8,
 43.12, -5.95) <= 375.001 from far_move), 'a far move redraws the centre around the new position');

-- A fix without accuracy is accepted; when present the accuracy is still checked.
select pg_temp.as_user('02');
select is((select r->>'window' || '/' || (r->>'accepted')
 from public.report_arrival_position((select second from ids), 43.0679, -5.95, null) r), 'open/true',
 'a position without accuracy is accepted');
select pg_temp.as_user('01');
select is(public.appointment_arrival((select second from ids)) - 'closes_at',
 jsonb_build_object('state', 'sharing', 'precision', 'exact', 'latitude', 43.0679, 'longitude', -5.95, 'accuracy_m', null,
  'updated_at', now()), 'the exact position without accuracy reads with a null accuracy');
select pg_temp.as_user('02');
select throws_ok(format('select public.report_arrival_position(%L, 43.0679, -5.95, -1)', (select second from ids)),
 '22023', 'invalid_position', 'a negative accuracy is rejected');
select throws_ok(format('select public.report_arrival_position(%L, 43.0679, -5.95, %L)', (select second from ids), 'NaN'),
 '22023', 'invalid_position', 'an accuracy that is not a number is rejected');

-- At 2 km or less: the exact position.
select pg_temp.as_user('02');
select pg_temp.report((select second from ids), 43.0679, -5.95);
select pg_temp.as_user('01');
select is(public.appointment_arrival((select second from ids)) - 'closes_at',
 jsonb_build_object('state', 'sharing', 'precision', 'exact', 'latitude', 43.0679, 'longitude', -5.95, 'accuracy_m', 15,
  'updated_at', now()), 'at 2 km or less the client receives the exact position and its time');

-- More than 2 min without positions: «abierta sin compartir».
reset role;
update private.arrival_positions set reported_at = now() - interval '2 minutes' where appointment_id = (select second from ids);
set local role authenticated;
select pg_temp.as_user('01');
select is(public.appointment_arrival((select second from ids))->>'state', 'sharing', 'a position of exactly 2 min still counts');
reset role;
update private.arrival_positions set reported_at = now() - interval '121 seconds' where appointment_id = (select second from ids);
set local role authenticated;
select pg_temp.as_user('01');
select is(public.appointment_arrival((select second from ids)) - 'closes_at', '{"state": "not_sharing"}'::jsonb,
 '«abierta sin compartir» after 2 min without positions');

-- Only the client of the appointment reads the arrival; nobody reads the positions table.
select pg_temp.as_user('02');
select throws_ok(format('select public.appointment_arrival(%L)', (select second from ids)), '42501', 'permission_denied',
 'the professional cannot read the arrival');
select pg_temp.as_user('03');
select throws_ok(format('select public.appointment_arrival(%L)', (select second from ids)), '42501', 'permission_denied',
 'another account cannot read the arrival');
select throws_ok('select * from private.arrival_positions', '42501', null, 'an account cannot read the positions table');
select throws_ok(format('select public.stop_sharing_arrival(%L)', (select second from ids)), '42501', 'permission_denied',
 'another account cannot stop the sharing');
reset role;
select set_config('test.second', (select second from ids)::text, true);
set local role anon;
select throws_ok(format('select public.appointment_arrival(%L)', current_setting('test.second')), '42501', null,
 'visitors cannot call «leer llegada»');
reset role;

-- «Dejar de compartir» deletes the position at once.
set local role authenticated;
select pg_temp.as_user('02');
select pg_temp.report((select second from ids), 43.0679, -5.95);
select is(public.stop_sharing_arrival((select second from ids)) - 'opens_at' - 'closes_at',
 '{"window": "open", "accepted": false, "reported_at": null}'::jsonb, '«dejar de compartir» answers with the window');
select pg_temp.as_user('01');
select is(public.appointment_arrival((select second from ids)) - 'closes_at', '{"state": "not_sharing"}'::jsonb,
 '«abierta sin compartir» after «dejar de compartir»');
reset role;
select is((select count(*)::integer from private.arrival_positions where appointment_id = (select second from ids)), 0,
 'the position is deleted at once');

-- The clock deletes the position of every closed window and keeps that of open ones.
set local role authenticated;
select pg_temp.as_user('02');
select pg_temp.report((select second from ids), 43.0679, -5.95);
reset role;
select is((select count(*)::integer from private.arrival_positions where appointment_id in
 (select main from ids union all select cancelled from ids)), 2,
 'the positions sent before «Comenzar trabajo» and before the cancellation are still there');
select is(private.run_arrival_clock(), '{"deleted": 2}'::jsonb, 'the clock deletes both closed windows');
select is((select array_agg(appointment_id) from private.arrival_positions), array[(select second from ids)],
 'only the position of the open window remains');
select pg_temp.starts_in((select second from ids), -60);
select is(private.run_arrival_clock(), '{"deleted": 1}'::jsonb, 'at +60 min the clock deletes it too');
select is((select count(*)::integer from private.arrival_positions), 0, 'no position outlives its window');

select * from finish();
rollback;
