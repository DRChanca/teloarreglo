-- The professional fills the agenda when presenting the profile, not after approval (#41). The agenda
-- travels with the request, is saved in the same transaction and still needs no review.
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data,raw_app_meta_data) values
 ('00000000-0000-0000-0000-000000410101','agenda-submit@example.org',now(),'{}','{}');

create temporary table t41(name text primary key, value jsonb);
grant all on t41 to authenticated;
insert into t41 values ('content', '{"name":"Fontanería Agenda","description":"Con horario","trades":["plumbing"],
 "services":[],"coverage":[{"code":"33044","name":"Oviedo"}],"languages":["es"],"phone":"+34 600 000 041",
 "images":[],"prices":[]}'),
 ('agenda', '{"default_duration_minutes":90,"windows":[{"weekday":1,"start":"09:00","end":"14:00"},
 {"weekday":3,"start":"16:00","end":"20:00"}]}');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000410101","role":"authenticated"}',true);
select ok(public.start_professional_profile()->>'profile_id' is not null,'the account starts its profile');

-- An invalid agenda rejects the whole request: nothing is presented.
select throws_ok($$select public.submit_professional_profile('20000000-0000-0000-0000-000000410101',
 (select value from t41 where name='content'),
 '{"default_duration_minutes":5,"windows":[]}')$$,'22023','invalid_agenda','an invalid agenda is refused');
select is(public.my_professional_profile()->'review'->>'status',null,'and the profile was not presented');

-- Presenting with the agenda saves both at once; the profile is pending, not published.
select is(public.submit_professional_profile('20000000-0000-0000-0000-000000410102',
 (select value from t41 where name='content'),(select value from t41 where name='agenda'))->>'status','pending',
 'the profile is presented with its agenda');
select is((public.my_agenda()->>'default_duration_minutes')::integer,90,'the usual duration is saved');
select is(public.my_agenda()->'windows',
 '[{"weekday":1,"start":"09:00","end":"14:00"},{"weekday":3,"start":"16:00","end":"20:00"}]'::jsonb,
 'the weekly hours are saved before any approval');
select is((public.my_professional_profile()->'review'->>'published')::boolean,false,'nothing is published yet');

-- Older clients can still present without an agenda; the saved one is kept.
select public.submit_professional_profile('20000000-0000-0000-0000-000000410103',
 (select value || '{"description":"Sin tocar horario"}' from t41 where name='content'));
select is(jsonb_array_length(public.my_agenda()->'windows'),2,'presenting without agenda keeps the saved hours');

select * from finish();
rollback;
