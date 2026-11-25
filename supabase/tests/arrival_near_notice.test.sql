-- Aviso «Tu profesional está cerca» (#83, ADR 0002): once per appointment, when «informar posición» accepts a position
-- at 100 m or less of the address of the appointment while the arrival window is open. Fixtures use the ...8301NN range.
-- The address of every appointment is 'Calle Cerca 1' at 43.05, -5.95: 43.0508 is ~89 m away, 43.0510 ~111 m.
begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

insert into public.concejos(ine_code, name, boundary) values
 ('33983', 'Prueba Cerca', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000830101','near-client@example.org',now()),
 ('00000000-0000-0000-0000-000000830102','near-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000830103','near-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000830101','00000000-0000-0000-0000-000000830102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000830101','Near plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33983","name":"Prueba Cerca"}]',array['es'],'000');

-- Two confirmed appointments of the same client: 1 approached while open, 2 already near when its window opens.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000830101","role":"authenticated"}',true);
select public.register_push_device('90000000-0000-0000-0000-000000830101', 'token-near-client', true);
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000830101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000083010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle Cerca 1', 43.05, -5.95) from generate_series(1, 2) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000830102","role":"authenticated"}',true);
select public.save_agenda(30, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select id from public.appointments where request_id = ('30000000-0000-0000-0000-00000083010' || n)::uuid),
 1, ('40000000-0000-0000-0000-00000083010' || n)::uuid, 'Scope ' || n,
 '2030-01-15 08:00Z'::timestamptz + (n - 1) * interval '30 minutes', 30, 0, null, true, true) from generate_series(1, 2) n;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000830101","role":"authenticated"}',true);
select public.accept_proposal((select id from public.appointment_proposals where scope = 'Scope ' || n)) from generate_series(1, 2) n;
reset role;
create temp table ids on commit drop as select
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000830101') as main,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000830102') as second;
grant select on ids to authenticated;

create function pg_temp.starts_in(p_appointment_id uuid, p_minutes integer) returns void language sql as $$
    update public.appointment_proposals set start_at = now() + make_interval(mins => p_minutes),
        end_at = now() + make_interval(mins => p_minutes + 30)
      where appointment_id = p_appointment_id and status = 'accepted'
$$;
create function pg_temp.as_user(p_suffix text) returns void language sql as $$
    select set_config('request.jwt.claims',
        '{"sub":"00000000-0000-0000-0000-0000008301' || p_suffix || '","role":"authenticated"}', true)
$$;
-- «Informar posición» as the professional; answers window/accepted.
create function pg_temp.report(p_appointment_id uuid, p_lat double precision) returns text language sql as $$
    select pg_temp.as_user('02');
    select r->>'window' || '/' || (r->>'accepted') from public.report_arrival_position(p_appointment_id, p_lat, -5.95, 10) r
$$;
-- Proximity notices of one appointment in the account's own notice list.
create function pg_temp.near_notices(p_suffix text, p_appointment_id uuid) returns integer language sql as $$
    select pg_temp.as_user(p_suffix);
    select count(*)::integer from jsonb_array_elements(public.my_notices()) n
     where n->'payload'->>'event' = 'professional_near' and n->'payload'->>'appointment_id' = p_appointment_id::text
$$;

-- A position rejected outside the window never notifies, even at the door.
select pg_temp.starts_in((select main from ids), 61);
set local role authenticated;
select is(pg_temp.report((select main from ids), 43.05), 'not_yet_open/false', 'a position before the window is rejected');
select is(pg_temp.near_notices('01', (select main from ids)), 0, 'a rejected position at the door creates no notice');

-- More than 100 m away with the window open: no notice.
reset role;
select pg_temp.starts_in((select main from ids), 30);
set local role authenticated;
select is(pg_temp.report((select main from ids), 43.0510), 'open/true', 'a position ~111 m away is accepted');
select is(pg_temp.near_notices('01', (select main from ids)), 0, 'more than 100 m away creates no notice');

-- At 100 m or less with the window open: one notice, for the client only.
select is(pg_temp.report((select main from ids), 43.0508), 'open/true', 'a position ~89 m away is accepted');
select is(pg_temp.near_notices('01', (select main from ids)), 1, 'the client is told the professional is near');
select is(pg_temp.near_notices('02', (select main from ids)), 0, 'the professional gets no notice');
select is(pg_temp.near_notices('03', (select main from ids)), 0, 'a third account gets no notice');
select pg_temp.as_user('01');
select is((select (n->'payload') - 'appointment_id' from jsonb_array_elements(public.my_notices()) n
  where n->'payload'->>'event' = 'professional_near'),
 jsonb_build_object('event', 'professional_near', 'conversation_id',
  (select conversation_id from public.appointments where id = (select main from ids))),
 'the notice leads to the conversation of the appointment');
select ok(public.my_notices()::text !~ '43\.05|-5\.95|Calle Cerca|Near plumber',
 'the notice carries no coordinates, address or names');

-- Once per appointment: another position inside, or leaving and coming back, never repeats it.
select pg_temp.report((select main from ids), 43.0501);
select pg_temp.report((select main from ids), 43.10);
select pg_temp.report((select main from ids), 43.05);
select is(pg_temp.near_notices('01', (select main from ids)), 1, 'staying near or leaving and coming back does not repeat it');

-- Already near when the window opens: the first accepted position notifies.
reset role;
select pg_temp.starts_in((select second from ids), 61);
set local role authenticated;
select is(pg_temp.report((select second from ids), 43.05), 'not_yet_open/false', 'at the door before the window: rejected');
select is(pg_temp.near_notices('01', (select second from ids)), 0, 'no notice before the window');
reset role;
select pg_temp.starts_in((select second from ids), 60);
set local role authenticated;
select is(pg_temp.report((select second from ids), 43.05), 'open/true', 'the window opens with the professional at the door');
select is(pg_temp.near_notices('01', (select second from ids)), 1, 'the first accepted position notifies');

-- The notice is pushed to the client's device.
reset role;
select is((select count(*)::integer from private.push_deliveries d join private.notices n on n.id = d.notice_id
  where n.payload->>'event' = 'professional_near' and d.recipient_id = '00000000-0000-0000-0000-000000830101'), 2,
 'each proximity notice is queued for push to the client');

select * from finish();
rollback;
