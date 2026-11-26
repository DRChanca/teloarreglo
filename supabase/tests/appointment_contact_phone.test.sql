-- Contact phone of the client in the place of an appointment (#79, spec #71). Fixtures use the ...7901NN range
-- and synthetic concejo 33979. 2030-01-15 is a Tuesday; Madrid is UTC+1 then (09:00–14:00 local = 08:00–13:00Z).
-- The client always reads it; the professional only while the appointment is confirmed or started.
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);

insert into public.concejos(ine_code, name, boundary) values
 ('33979', 'Prueba Telefono', 'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000790101','phone-client@example.org',now()),
 ('00000000-0000-0000-0000-000000790102','phone-pro@example.org',now()),
 ('00000000-0000-0000-0000-000000790103','phone-other@example.org',now());
insert into private.professional_profiles(id,owner_id) values
 ('10000000-0000-0000-0000-000000790101','00000000-0000-0000-0000-000000790102');
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
 values ('10000000-0000-0000-0000-000000790101','Phone plumber','Public',array['plumbing'],array['Repairs'],
  '[{"code":"33979","name":"Prueba Telefono"}]',array['es'],'000');

create function pg_temp.act(p uuid) returns void language sql as
 $$ select set_config('request.jwt.claims', json_build_object('sub', p, 'role', 'authenticated')::text, true) $$;
grant execute on function pg_temp.act(uuid) to authenticated;

set local role authenticated;
-- Two requests in one conversation: «kept» goes all the way to completed, «dropped» is cancelled once confirmed.
select pg_temp.act('00000000-0000-0000-0000-000000790101');
select public.set_contact_phone('612 34 56 78');
select public.request_appointment((public.open_conversation('10000000-0000-0000-0000-000000790101')->>'id')::uuid,
 ('30000000-0000-0000-0000-00000079010' || n)::uuid, 'Job ' || n, '2030-01-15 08:00Z', '2030-01-15 09:00Z',
 'Calle Secreta 7, 2ºA', 43.05, -5.95) from generate_series(1, 2) n;
reset role;
create temp table ids on commit drop as select
 (select conversation_id from public.appointments where request_id = '30000000-0000-0000-0000-000000790101') as conv,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000790101') as kept,
 (select id from public.appointments where request_id = '30000000-0000-0000-0000-000000790102') as dropped;
grant select on ids to authenticated;
create function pg_temp.phone(p_appointment uuid) returns text language sql as
 $$ select contact_phone from public.appointment_places((select conv from ids)) where appointment_id = p_appointment $$;
grant execute on function pg_temp.phone(uuid) to authenticated;
set local role authenticated;

-- The client always reads their own number; the professional nothing while pending.
select is(pg_temp.phone((select kept from ids)), '+34612345678', 'the client reads their phone on a pending request');
select pg_temp.act('00000000-0000-0000-0000-000000790102');
select is(pg_temp.phone((select kept from ids)), null, 'the professional does not read the phone of a pending request');
select public.save_agenda(60, '[{"weekday":2,"start":"09:00","end":"14:00"}]');
select public.propose_appointment((select kept from ids), 1, '40000000-0000-0000-0000-000000790101',
 'Free visit', '2030-01-15 10:00Z', 60, 0, null, false, true);
select public.propose_appointment((select dropped from ids), 1, '40000000-0000-0000-0000-000000790102',
 'Free check', '2030-01-15 11:00Z', 60, 0, null, false, true);
select is(pg_temp.phone((select kept from ids)), null, 'nor with a proposal waiting for the client');

-- Confirmation hands the number to the professional, together with the exact place.
select pg_temp.act('00000000-0000-0000-0000-000000790101');
select public.accept_proposal(id) from public.appointment_proposals where appointment_id in ((select kept from ids), (select dropped from ids));
select pg_temp.act('00000000-0000-0000-0000-000000790102');
select is(pg_temp.phone((select kept from ids)), '+34612345678', 'the professional reads the phone once confirmed');
select is((select exact from public.appointment_places((select conv from ids)) where appointment_id = (select kept from ids)), true,
 'the exact place is given as before');
select ok(public.my_notices()::text !~ '612345678', 'no notice carries the phone');
select ok(coalesce((select jsonb_agg(to_jsonb(m))::text from public.chat_messages m), '') !~ '612345678',
 'no chat message carries the phone');
select ok(coalesce((select jsonb_agg(to_jsonb(p))::text from public.appointment_proposals p), '') !~ '612345678',
 'proposals carry no phone');
select ok(public.conversation_inbox()::text !~ '612345678', 'the inbox carries no phone');
select is((select count(*) from public.contact_phones), 0::bigint, 'the professional cannot read the table itself');

-- Always the current number, never a copy.
select pg_temp.act('00000000-0000-0000-0000-000000790101');
select public.set_contact_phone('+44 7700 900123');
select pg_temp.act('00000000-0000-0000-0000-000000790102');
select is(pg_temp.phone((select kept from ids)), '+447700900123', 'a changed number reaches the confirmed appointment at once');

-- Started: still visible. Done or completed: gone, while the exact place stays.
select public.start_work((select kept from ids));
select is(pg_temp.phone((select kept from ids)), '+447700900123', 'the professional reads the phone while the job is started');
select public.mark_done((select kept from ids));
select is(pg_temp.phone((select kept from ids)), null, 'the professional stops reading it once the job is done');
select is((select exact from public.appointment_places((select conv from ids)) where appointment_id = (select kept from ids)), true,
 'the exact place rule is unchanged after the job');
select pg_temp.act('00000000-0000-0000-0000-000000790101');
select public.confirm_done((select kept from ids));
select is(pg_temp.phone((select kept from ids)), '+447700900123', 'the client still reads it on a completed job');
select pg_temp.act('00000000-0000-0000-0000-000000790102');
select is(pg_temp.phone((select kept from ids)), null, 'nor once the job is completed');

-- Cancelled: gone for the professional, still there for the client.
select is(pg_temp.phone((select dropped from ids)), '+447700900123', 'the professional reads it on the other confirmed appointment');
select public.cancel_appointment((select dropped from ids));
select is(pg_temp.phone((select dropped from ids)), null, 'the professional stops reading it once cancelled');
select pg_temp.act('00000000-0000-0000-0000-000000790101');
select is(pg_temp.phone((select dropped from ids)), '+447700900123', 'the client reads it on a cancelled appointment');

-- A third account reads nothing; without a phone the column is null.
select pg_temp.act('00000000-0000-0000-0000-000000790103');
select is((select count(*) from public.appointment_places((select conv from ids))), 0::bigint, 'a third account gets nothing');
reset role;
delete from public.contact_phones where user_id = '00000000-0000-0000-0000-000000790101';
set local role authenticated;
select pg_temp.act('00000000-0000-0000-0000-000000790101');
select is(pg_temp.phone((select kept from ids)), null, 'without a contact phone the column is null');
select * from finish();
rollback;
