-- Approximate 400 m area until confirmation (#38). Fixtures use the ...3801NN range and synthetic concejo 33938.
-- The exact address and pin are 'Calle Secreta 7, 2ºA' at 43.0512345, -5.9487654: any of these strings in what
-- the professional can read before confirmation is a leak.
begin;
create extension if not exists pgtap with schema extensions;
select plan(48);

insert into public.concejos(ine_code, name, boundary) values
 ('33938', 'Prueba Zona', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000380101','area-client@example.org',now()),
 ('00000000-0000-0000-0000-000000380102','area-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000380103','area-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000380101','00000000-0000-0000-0000-000000380102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000380101','Area plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33938","name":"Prueba Zona"}]',array['es'],'000');

-- Geometry of the offset: uniform inside a 300 m disc, so the home is always inside the 400 m circle.
create temp table samples on commit drop as
 select extensions.st_distance(
   extensions.st_setsrid(extensions.st_makepoint(-5.95, 43.05), 4326)::extensions.geography,
   extensions.st_setsrid(extensions.st_makepoint((c).lng, (c).lat), 4326)::extensions.geography) as d,
   (c).lat - 43.05 as dlat, (c).lng + 5.95 as dlng
 from (select private.approximate_center(43.05, -5.95) as c from generate_series(1, 4000)) draws;
select ok((select max(d) from samples) <= 300.001, 'the offset never exceeds 300 m');
select ok((select min(d) from samples) < 300 and (select count(distinct (dlat, dlng)) from samples) > 3990,
 'the offset is random, not a fixed shift');
select ok((select avg(d * d) / 90000 from samples) between 0.45 and 0.55,
 'the mean squared offset is R²/2, as for points uniform over the disc (not bunched at the centre)');
select ok((select avg((d < 150)::int) from samples) between 0.2 and 0.3,
 'a quarter of the offsets fall within half the radius, as area-uniform sampling requires');
select ok((select bool_and(f between 0.2 and 0.3) from (
  select avg(((dlat > 0) = q.north and (dlng > 0) = q.east)::int) as f
  from samples, (values (true, true), (true, false), (false, true), (false, false)) q(north, east)
  group by q.north, q.east) quadrants), 'every direction is equally likely');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000380101","role":"authenticated"}',true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000380101')->>'id')::uuid,
 '30000000-0000-0000-0000-000000380101', 'Leak in the kitchen', '2030-01-15 10:00Z', '2030-01-15 11:00Z',
 'Calle Secreta 7, 2ºA', 43.0512345, -5.9487654);
select is((select count(*) from public.appointment_places((select id from public.conversations))), 1::bigint,
 'the client sees the place of their request');
select is((select exact from public.appointment_places((select id from public.conversations))), true,
 'the client always sees their own exact place');
select is((select address || ' ' || latitude || ' ' || longitude from public.appointment_places((select id from public.conversations))),
 'Calle Secreta 7, 2ºA 43.0512345 -5.9487654', 'the client reads the address and pin they shared');

-- Stored once when the request is created.
reset role;
create temp table first_area on commit drop as select conversation_id, area_lat, area_lng from public.appointments;
grant select on first_area to authenticated;
select ok((select extensions.st_distance(
   extensions.st_setsrid(extensions.st_makepoint(longitude, latitude), 4326)::extensions.geography,
   extensions.st_setsrid(extensions.st_makepoint(area_lng, area_lat), 4326)::extensions.geography) <= 300.001
 from public.appointments), 'the stored centre is at most 300 m from the home, which stays inside the 400 m circle');
set local role authenticated;
select is(public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000380101',
 'Leak in the kitchen', '2030-01-15 10:00Z', '2030-01-15 11:00Z', 'Calle Secreta 7, 2ºA', 43.0512345, -5.9487654)->>'area_lat',
 (select area_lat::text from first_area), 'retrying the request keeps the same centre');

-- The professional before confirmation: only the area, its radius and the concejo.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000380102","role":"authenticated"}',true);
select is((select count(*) from public.appointments), 1::bigint, 'the professional sees the request');
select throws_ok($$select address from public.appointments$$, '42501', null,
 'the professional cannot select the exact address from the table');
select throws_ok($$select latitude from public.appointments$$, '42501', null,
 'the professional cannot select the exact latitude from the table');
select throws_ok($$select longitude from public.appointments$$, '42501', null,
 'the professional cannot select the exact longitude from the table');
select throws_ok($$select * from public.appointments$$, '42501', null, 'select * cannot reach the hidden columns');
select throws_ok($$select to_jsonb(a) from public.appointments a$$, '42501', null, 'a whole-row read cannot reach them either');
select throws_ok($$select count(*) from public.appointments where address like 'Calle%'$$, '42501', null,
 'the address cannot be probed through a filter');
select throws_ok($$select count(*) from public.appointments where latitude > 43.05$$, '42501', null,
 'the pin cannot be probed through a filter');
select is((select area_radius_m from public.appointments), 400, 'the professional reads the 400 m radius');
select is((select (a.area_lat, a.area_lng) = (f.area_lat, f.area_lng) from public.appointments a, first_area f), true,
 'the professional reads the stored centre');
select is((select area_ine_code from public.appointments), '33938', 'the professional reads the concejo code');
select is((select exact::text || coalesce(address, '-') || coalesce(latitude::text, '-') || coalesce(longitude::text, '-')
 from public.appointment_places((select id from public.conversations))), 'false---',
 'the place function hides the address and pin from the professional');
select is((select concejo_name || ' ' || area_radius_m from public.appointment_places((select id from public.conversations))),
 'Prueba Zona 400', 'the place function gives the professional the area and concejo');
select throws_ok($$select public.request_appointment((select id from public.conversations), '30000000-0000-0000-0000-000000380101',
 'Leak in the kitchen', '2030-01-15 10:00Z', '2030-01-15 11:00Z', 'x', 43.0, -5.9)$$, '42501', 'permission_denied',
 'the professional cannot read the request back through the request function');
select ok(public.propose_appointment((select id from public.appointments), 1, '40000000-0000-0000-0000-000000380101',
 'Free visit', '2030-01-15 10:00Z', 60, 0, null, false, true)::text !~ 'Secreta|43\.0512|5\.9487',
 'the proposal result carries no exact place');
select ok(coalesce((select jsonb_agg(to_jsonb(p))::text from public.appointment_proposals p), '') !~ 'Secreta|43\.0512|5\.9487',
 'proposals carry no exact place');
select ok(coalesce((select jsonb_agg(to_jsonb(e))::text from public.appointment_events e), '') !~ 'Secreta|43\.0512|5\.9487',
 'notices carry no exact place');
select ok(coalesce((select jsonb_agg(to_jsonb(c))::text from public.conversations c), '') !~ 'Secreta|43\.0512|5\.9487',
 'the conversation carries no exact place');
select ok(coalesce((select jsonb_agg(to_jsonb(m))::text from public.chat_messages m), '') !~ 'Secreta|43\.0512|5\.9487',
 'no chat message is written with the exact place');
select ok(public.conversation_inbox()::text !~ 'Secreta|43\.0512|5\.9487', 'the inbox carries no exact place');
select ok(public.my_notices()::text !~ 'Secreta|43\.0512|5\.9487', 'account notices carry no exact place');
select is((select count(*) from public.home_addresses), 0::bigint, 'the professional reads no home address');

-- A third account and visitors read nothing.
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000380103","role":"authenticated"}',true);
select is((select count(*) from public.appointment_places((select conversation_id from first_area))), 0::bigint,
 'a third account gets no place');
select is((select count(*) from public.appointments), 0::bigint, 'a third account reads no appointment');
reset role;
set local role anon;
select throws_ok($$select * from public.appointment_places('99999999-0000-0000-0000-000000000000')$$, '42501', null,
 'visitors cannot call the place function');
reset role;

-- Confirmation reveals the exact place to the professional.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000380102","role":"authenticated"}',true);
select public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000380101","role":"authenticated"}',true);
select is(public.accept_proposal((select id from public.appointment_proposals))->>'status', 'accepted',
 'the client confirms the free visit');
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000380102","role":"authenticated"}',true);
select is((select exact from public.appointment_places((select id from public.conversations))), true,
 'after confirmation the professional sees the exact place');
select is((select address || ' ' || latitude || ' ' || longitude from public.appointment_places((select id from public.conversations))),
 'Calle Secreta 7, 2ºA 43.0512345 -5.9487654', 'after confirmation the professional reads the address and pin');
select ok((select location_shared_at is not null from public.appointments), 'the moment of sharing is recorded');
select is((select (a.area_lat, a.area_lng) = (f.area_lat, f.area_lng) from public.appointments a, first_area f), true,
 'confirmation does not move the centre');
select throws_ok($$select address from public.appointments$$, '42501', null,
 'even after confirmation the table column stays closed; the place function is the only way');
select ok(coalesce((select jsonb_agg(to_jsonb(e))::text from public.appointment_events e), '') !~ 'Secreta|43\.0512|5\.9487',
 'the confirmation notice carries no exact place');
select ok(public.my_agenda()::text !~ 'Secreta|43\.0512|5\.9487', 'the agenda carries no exact place');
select ok(coalesce((select jsonb_agg(to_jsonb(r))::text from public.agenda_reservations r), '') !~ 'Secreta|43\.0512|5\.9487',
 'reservations carry no exact place');

-- The centre never changes with ordinary updates, nor can the sharing moment be undone.
reset role;
update public.appointments set version = version + 1, area_lat = 0, area_lng = 0, location_shared_at = null;
select is((select (a.area_lat, a.area_lng) = (f.area_lat, f.area_lng) from public.appointments a, first_area f), true,
 'the centre cannot be overwritten');
select ok((select location_shared_at is not null from public.appointments), 'the sharing moment cannot be cleared');
-- A new exact point (a future accepted address change) gets a new centre around it.
update public.appointments set latitude = 43.08, longitude = -5.92;
select ok((select extensions.st_distance(
   extensions.st_setsrid(extensions.st_makepoint(-5.92, 43.08), 4326)::extensions.geography,
   extensions.st_setsrid(extensions.st_makepoint(area_lng, area_lat), 4326)::extensions.geography) <= 300.001
 from public.appointments), 'a moved home gets a new centre within 300 m of it');
select ok((select extensions.st_distance(
   extensions.st_setsrid(extensions.st_makepoint(-5.92, 43.08), 4326)::extensions.geography,
   extensions.st_setsrid(extensions.st_makepoint(f.area_lng, f.area_lat), 4326)::extensions.geography) > 300
 from first_area f), 'the old centre is not kept for a moved home');
select * from finish();
rollback;
