-- Ventana de llegada, fixes of the verification of #72 (20261126080000_arrival_review_fixes.sql):
--   * «Dejar de compartir» holds until the professional chooses to share again (start_sharing_arrival), so a report
--     that arrives late is never shown;
--   * the circle beyond 2 km has the same centre for the same place: in another appointment, after stop and share
--     again and after the clock deletes the position; and it always contains the position;
--   * «leer llegada» carries the age of the position by the server clock (age_s);
--   * «estado de la ventana» carries the agreed start (start_at) and the stop mark.
-- Fixtures use the ...8601NN range. The address of every appointment is 'Calle Rejilla 1' at 43.05, -5.95.
begin;
create extension if not exists pgtap with schema extensions;
select plan(30);

insert into public.concejos(ine_code, name, boundary) values
 ('33984', 'Prueba Rejilla', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000860101','grid-client@example.org',now()),
 ('00000000-0000-0000-0000-000000860102','grid-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000860103','grid-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000860101','00000000-0000-0000-0000-000000860102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000860101','Grid plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33984","name":"Prueba Rejilla"}]',array['es'],'000');

-- Two confirmed appointments of the same client with the same professional: the recurring client of #72.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000860101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000860101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000086010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle Rejilla 1', 43.05, -5.95) from generate_series(1, 2) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000860102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000086010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000086010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30, 0, null, true, true) from generate_series(1, 2) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000860101","role":"authenticated"}',true);
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n)) from generate_series(1, 2) n;
reset role;
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000860101') as first,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000860102') as second;
grant select on ids to authenticated;

create function pg_temp.starts_in(p_appointment_id uuid, p_minutes integer) returns void language sql as $$
    update public.appointment_proposals set start_at = now() + make_interval(mins => p_minutes),
        end_at = now() + make_interval(mins => p_minutes + 30)
      where appointment_id = p_appointment_id and status = 'accepted'
$$;
create function pg_temp.as_user(p_suffix text) returns void language sql as $$
    select set_config('request.jwt.claims',
        '{"sub":"00000000-0000-0000-0000-0000008601' || p_suffix || '","role":"authenticated"}', true)
$$;
create function pg_temp.report(p_appointment_id uuid, p_lat double precision, p_lng double precision) returns text
language sql as $$
    select r->>'window' || '/' || (r->>'accepted') || coalesce('/stopped=' || (r->>'stopped'), '')
      from public.report_arrival_position(p_appointment_id, p_lat, p_lng, 15) r
$$;
-- The centre of the circle the client reads, as text.
create function pg_temp.centre(p_appointment_id uuid) returns text language sql as $$
    select r->>'precision' || ' ' || (r->>'latitude') || '/' || (r->>'longitude')
      from public.appointment_arrival(p_appointment_id) r
$$;
create function pg_temp.distance_m(lat1 double precision, lng1 double precision, lat2 double precision,
    lng2 double precision) returns double precision language sql as $$
    select extensions.st_distance(extensions.st_setsrid(extensions.st_makepoint(lng1, lat1), 4326)::extensions.geography,
        extensions.st_setsrid(extensions.st_makepoint(lng2, lat2), 4326)::extensions.geography)
$$;

-- The grid: the 500 m circle always contains the position, whatever the place and the professional.
select ok((select max(pg_temp.distance_m(p.lat, p.lng, c.lat, c.lng)) < 465
 from (select 36 + 8 * random() as lat, -9.5 + 13 * random() as lng,
              ('00000000-0000-0000-0000-' || lpad((n % 50)::text, 12, '0'))::uuid as pro
         from generate_series(1, 5000) n) p
 cross join lateral private.arrival_grid_center(p.pro, p.lat, p.lng) c),
 'the centre is always under 465 m from the position (5000 places in Spain, 50 professionals)');
select ok((select max(pg_temp.distance_m(p.lat, p.lng, c.lat, c.lng)) < 465
 from (select -70 + 140 * random() as lat, -180 + 360 * random() as lng from generate_series(1, 2000)) p
 cross join lateral private.arrival_grid_center('00000000-0000-0000-0000-000000860102', p.lat, p.lng) c),
 'also anywhere between 70° S and 70° N');
select ok((select min(pg_temp.distance_m(43.10 + n * 0.00001, -5.95, c.lat, c.lng)) > 0
 from generate_series(0, 20) n
 cross join lateral private.arrival_grid_center('00000000-0000-0000-0000-000000860102', 43.10 + n * 0.00001, -5.95) c),
 'the centre is never the position itself');
select is((select count(distinct c.lat || '/' || c.lng)::integer from generate_series(1, 20) n
 cross join lateral private.arrival_grid_center('00000000-0000-0000-0000-000000860102', 43.10, -5.95) c), 1,
 'the same place always gives the same centre');
select isnt((select c.lat || '/' || c.lng from private.arrival_grid_center('00000000-0000-0000-0000-000000860102', 43.10, -5.95) c),
 (select c.lat || '/' || c.lng from private.arrival_grid_center('00000000-0000-0000-0000-000000860103', 43.10, -5.95) c),
 'each professional has their own grid offset');

-- Same place in two different appointments of the same client: the same centre.
select pg_temp.starts_in((select first from ids), 30);
select pg_temp.starts_in((select second from ids), 40);
set local role authenticated;
select pg_temp.as_user('02');
select is(pg_temp.report((select first from ids), 43.10, -5.95), 'open/true', 'the professional shares from home');
select is(pg_temp.report((select second from ids), 43.10, -5.95), 'open/true', 'and from home again for the other appointment');
select pg_temp.as_user('01');
create temp table home_centre on commit drop as select pg_temp.centre((select first from ids)) as c;
select ok((select c like 'area %' from home_centre), 'beyond 2 km the client reads a circle');
select is(pg_temp.centre((select second from ids)), (select c from home_centre),
 'the same place in another appointment gives the same centre');
select ok((select pg_temp.distance_m((r->>'latitude')::float8, (r->>'longitude')::float8, 43.10, -5.95) < 465
 from public.appointment_arrival((select first from ids)) r), 'and the circle contains the real position');

-- «Leer llegada» carries the age of the position by the server clock.
select is((select (r->>'age_s')::float8 from public.appointment_arrival((select first from ids)) r), 0::float8,
 'a position just reported is 0 s old');
reset role;
update private.arrival_positions set reported_at = now() - interval '90 seconds' where appointment_id = (select first from ids);
set local role authenticated;
select pg_temp.as_user('01');
select is((select (r->>'age_s')::float8 from public.appointment_arrival((select first from ids)) r), 90::float8,
 'age_s is measured by the server clock');

-- «Dejar de compartir» holds: a report that arrives afterwards is refused and stored nowhere.
select pg_temp.as_user('02');
select is((select r->>'stopped' from public.stop_sharing_arrival((select first from ids)) r), 'true',
 '«dejar de compartir» answers with the stop mark');
select is(pg_temp.report((select first from ids), 43.10, -5.95), 'open/false/stopped=true',
 'a report arriving after the stop is refused and says why');
select is(pg_temp.report((select first from ids), 43.0505, -5.95), 'open/false/stopped=true',
 'also near the address of the appointment');
reset role;
select is((select count(*)::integer from private.arrival_positions where appointment_id = (select first from ids)), 0,
 'nothing is stored after the stop');
select is((select count(*)::integer from public.appointment_events where appointment_id = (select first from ids)
 and kind = 'professional_near'), 0, 'a refused position never triggers the proximity notice');
set local role authenticated;
select pg_temp.as_user('01');
select is(public.appointment_arrival((select first from ids)) - 'closes_at', '{"state": "not_sharing"}'::jsonb,
 'the client keeps reading «sin compartir»');
select pg_temp.as_user('02');
select is((select r->>'stopped' from public.arrival_sharing_window((select first from ids)) r), 'true',
 'the window state says the stop holds');
select is(pg_temp.report((select second from ids), 43.10, -5.95), 'open/true', 'the stop is per appointment');

-- Only the professional chooses to share again; then positions count and the centre is the same as before.
select pg_temp.as_user('01');
select throws_ok(format('select public.start_sharing_arrival(%L)', (select first from ids)), '42501', 'permission_denied',
 'the client cannot clear the stop');
select pg_temp.as_user('03');
select throws_ok(format('select public.start_sharing_arrival(%L)', (select first from ids)), '42501', 'permission_denied',
 'another account cannot clear the stop');
select pg_temp.as_user('02');
select is((select r - 'opens_at' - 'closes_at' - 'start_at' from public.start_sharing_arrival((select first from ids)) r),
 '{"window": "open"}'::jsonb, 'choosing to share again answers the window without the stop mark');
select is(pg_temp.report((select first from ids), 43.10, -5.95), 'open/true', 'positions count again');
select pg_temp.as_user('01');
select is(pg_temp.centre((select first from ids)), (select c from home_centre),
 'after stop and share again the same place gives the same centre');

-- After the clock deletes the position, the same place still gives the same centre.
reset role;
select pg_temp.starts_in((select first from ids), -60);
select private.run_arrival_clock();
select pg_temp.starts_in((select first from ids), 30);
set local role authenticated;
select pg_temp.as_user('02');
select pg_temp.report((select first from ids), 43.10, -5.95);
select pg_temp.as_user('01');
select is(pg_temp.centre((select first from ids)), (select c from home_centre),
 'after the clock deleted the position the same place gives the same centre');

-- «Estado de la ventana» carries the agreed start, and follows an accepted change that moves it.
select pg_temp.as_user('02');
select is((select (r->>'start_at')::timestamptz - now() from public.arrival_sharing_window((select first from ids)) r),
 interval '30 minutes', 'the window state carries the agreed start');
reset role;
select pg_temp.starts_in((select first from ids), 75);
set local role authenticated;
select pg_temp.as_user('02');
select is((select r->>'window' || ' ' || ((r->>'start_at')::timestamptz - now())
 from public.arrival_sharing_window((select first from ids)) r), 'not_yet_open 01:15:00',
 'a moved start is read at once, also before the window opens');

select throws_ok('select * from private.arrival_stops', '42501', null, 'no account reads the stop marks');
select throws_ok('select * from private.arrival_grid_secret', '42501', null, 'no account reads the grid secret');
reset role;

select * from finish();
rollback;
