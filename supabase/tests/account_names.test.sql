-- Person's name (#40). Fixtures use the ...4000NN range.
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

-- Google fills full_name or name in the Auth metadata; a signup form sends full_name.
insert into auth.users(id, email, email_confirmed_at, raw_user_meta_data) values
 ('00000000-0000-0000-0000-000000400001', 'name-client@example.org', now(), '{"full_name":"  Lucía Prueba  "}'),
 ('00000000-0000-0000-0000-000000400002', 'name-pro@example.org', now(), '{}'),
 ('00000000-0000-0000-0000-000000400003', 'name-other@example.org', now(), '{}'),
 ('00000000-0000-0000-0000-000000400004', 'name-google@example.org', now(), '{"name":"Pedro Google"}');
insert into private.professional_profiles(id, owner_id) values
 ('10000000-0000-0000-0000-000000400001', '00000000-0000-0000-0000-000000400002');
insert into public.approved_profiles(profile_id, name, description, trades, services, coverage, languages, phone)
 values ('10000000-0000-0000-0000-000000400001', 'Fontanería Nombre', 'Public', array['plumbing'], array['Repairs'], '[]', array['es'], '000');

create function pg_temp.act(p uuid) returns void language sql as
 $$ select set_config('request.jwt.claims', json_build_object('sub', p, 'role', 'authenticated')::text, true) $$;
create function pg_temp.inbox_name(p_client uuid) returns text language sql as
 $$ select e->>'client_name' from jsonb_array_elements(public.conversation_inbox()) e where e->>'client_id' = p_client::text $$;
grant execute on function pg_temp.act(uuid), pg_temp.inbox_name(uuid) to authenticated;

-- Visitors have no access at all.
set local role anon;
select throws_ok($$select public.my_account_name()$$, '42501', null, 'visitors cannot read a name');
select throws_ok($$select public.set_account_name('Intruso')$$, '42501', null, 'visitors cannot write a name');
select throws_ok($$select * from public.account_names$$, '42501', null, 'visitors cannot select the table');
reset role;

set local role authenticated;
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select throws_ok($$select public.set_account_name('Nadie')$$, '28000', 'authentication_required',
 'saving requires a signed-in account');

-- The name given at signup or by Google is stored without asking.
select pg_temp.act('00000000-0000-0000-0000-000000400001');
select is(public.my_account_name(), 'Lucía Prueba', 'signup full_name is stored trimmed');
select pg_temp.act('00000000-0000-0000-0000-000000400004');
select is(public.my_account_name(), 'Pedro Google', 'a Google name is stored without asking');

-- An account without a name gets null, never its email, and can save one.
select pg_temp.act('00000000-0000-0000-0000-000000400003');
select is(public.my_account_name(), null, 'an account without a name reads null, not its email');
select throws_ok($$select public.set_account_name('   ')$$, '22023', 'invalid_account_name', 'a blank name is rejected');
select throws_ok($$select public.set_account_name(repeat('a', 81))$$, '22023', 'invalid_account_name',
 'a name over 80 characters is rejected');
select is(public.set_account_name('  Ana Tercera  '), 'Ana Tercera', 'saving returns the trimmed name');
select is(public.set_account_name(repeat('b', 80)), repeat('b', 80), 'exactly 80 characters are accepted');

-- Only the owner writes; there are no direct writes and another account's row is invisible.
select throws_ok($$insert into public.account_names(user_id, name) values ('00000000-0000-0000-0000-000000400001', 'Hack')$$,
 '42501', null, 'no direct inserts');
select throws_ok($$update public.account_names set name = 'Hack'$$, '42501', null, 'no direct updates');
select is((select count(*) from public.account_names), 1::bigint, 'an account only sees its own row');
select is((select count(*) from public.account_names where user_id = '00000000-0000-0000-0000-000000400001'), 0::bigint,
 'another account''s name is not readable from the table');

-- The client changes the name; the other participant sees only the name in the inbox.
select pg_temp.act('00000000-0000-0000-0000-000000400001');
select public.open_conversation('10000000-0000-0000-0000-000000400001');
select is(public.set_account_name('Lucía Cambiada'), 'Lucía Cambiada', 'the owner can change the name');
select pg_temp.act('00000000-0000-0000-0000-000000400003');
select public.open_conversation('10000000-0000-0000-0000-000000400001');
select pg_temp.act('00000000-0000-0000-0000-000000400002');
select is(pg_temp.inbox_name('00000000-0000-0000-0000-000000400001'), 'Lucía Cambiada',
 'the professional inbox uses the saved name');
select is((select count(*) from jsonb_array_elements(public.conversation_inbox()) e
  where e::text like '%@example.org%'), 0::bigint, 'the inbox never carries a client email');
reset role;

-- Later Google metadata never overwrites a name the person chose.
update auth.users set raw_user_meta_data = '{"full_name":"Otro De Google"}'
 where id = '00000000-0000-0000-0000-000000400001';
select is((select name from public.account_names where user_id = '00000000-0000-0000-0000-000000400001'), 'Lucía Cambiada',
 'provider metadata does not overwrite a saved name');

select * from finish();
rollback;
