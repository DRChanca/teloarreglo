-- Contact phone of each account (#74). Fixtures use the ...7400NN range.
begin;
create extension if not exists pgtap with schema extensions;
select plan(30);

insert into auth.users(id, email, email_confirmed_at) values
 ('00000000-0000-0000-0000-000000740001', 'phone-owner@example.org', now()),
 ('00000000-0000-0000-0000-000000740002', 'phone-other@example.org', now());

create function pg_temp.act(p uuid) returns void language sql as
 $$ select set_config('request.jwt.claims', json_build_object('sub', p, 'role', 'authenticated')::text, true) $$;
grant execute on function pg_temp.act(uuid) to authenticated;

-- Visitors have no access at all.
set local role anon;
select throws_ok($$select public.my_contact_phone()$$, '42501', null, 'visitors cannot read a contact phone');
select throws_ok($$select public.set_contact_phone('612345678')$$, '42501', null, 'visitors cannot write a contact phone');
select throws_ok($$select * from public.contact_phones$$, '42501', null, 'visitors cannot select the table');
reset role;

set local role authenticated;
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select throws_ok($$select public.my_contact_phone()$$, '28000', 'authentication_required', 'reading requires a signed-in account');
select throws_ok($$select public.set_contact_phone('612345678')$$, '28000', 'authentication_required',
 'saving requires a signed-in account');

-- An account without phone reads null: the app asks for it.
select pg_temp.act('00000000-0000-0000-0000-000000740001');
select is(public.my_contact_phone(), null, 'an account without contact phone reads null');

-- Accepted formats, stored in E.164.
select is(public.set_contact_phone('612345678'), '+34612345678', 'a Spanish 9-digit number gets +34');
select is(public.my_contact_phone(), '+34612345678', 'the owner reads the saved phone');
select is(public.set_contact_phone(' 612 34 56 78 '), '+34612345678', 'spaces are ignored');
select is(public.set_contact_phone('912-345-678'), '+34912345678', 'dashes are ignored');
select is(public.set_contact_phone('+34 699 000 111'), '+34699000111', 'a Spanish number with prefix is kept');
select is(public.set_contact_phone('+447911123456'), '+447911123456', 'an international number is stored as is');
select is(public.set_contact_phone('+1 (415) 555-2671'), '+14155552671', 'parentheses are ignored');
select is(public.my_contact_phone(), '+14155552671', 'saving again replaces the phone');

-- Rejected formats.
select throws_ok($$select public.set_contact_phone('61234567a')$$, '22023', 'invalid_contact_phone', 'letters are rejected');
select throws_ok($$select public.set_contact_phone('61234567')$$, '22023', 'invalid_contact_phone', '8 digits are rejected');
select throws_ok($$select public.set_contact_phone('6123456789')$$, '22023', 'invalid_contact_phone',
 '10 digits without + are rejected');
select throws_ok($$select public.set_contact_phone('512345678')$$, '22023', 'invalid_contact_phone',
 'a Spanish number must start with 6, 7, 8 or 9');
select throws_ok($$select public.set_contact_phone('+0612345678')$$, '22023', 'invalid_contact_phone',
 'a country code never starts with 0');
select throws_ok($$select public.set_contact_phone('+1234567')$$, '22023', 'invalid_contact_phone',
 'an international number that is too short is rejected');
select throws_ok($$select public.set_contact_phone('+1234567890123456')$$, '22023', 'invalid_contact_phone',
 'more than 15 digits is rejected');
select throws_ok($$select public.set_contact_phone('+34+612345678')$$, '22023', 'invalid_contact_phone',
 'a + inside the number is rejected');
select throws_ok($$select public.set_contact_phone('')$$, '22023', 'invalid_contact_phone', 'an empty phone is rejected');
select throws_ok($$select public.set_contact_phone(null)$$, '22023', 'invalid_contact_phone', 'a null phone is rejected');
select is(public.my_contact_phone(), '+14155552671', 'a rejected phone keeps the saved one');

-- Another account neither reads nor writes it; there are no direct writes.
select pg_temp.act('00000000-0000-0000-0000-000000740002');
select is(public.my_contact_phone(), null, 'another account only reads its own (missing) phone');
select is((select count(*) from public.contact_phones), 0::bigint, 'another account''s phone is not readable from the table');
select throws_ok($$insert into public.contact_phones(user_id, phone) values ('00000000-0000-0000-0000-000000740002', '+34612345678')$$,
 '42501', null, 'no direct inserts');
select throws_ok($$update public.contact_phones set phone = '+34600000000'$$, '42501', null, 'no direct updates');
select pg_temp.act('00000000-0000-0000-0000-000000740001');
select is(public.my_contact_phone(), '+14155552671', 'the other account left the owner''s phone untouched');

select * from finish();
rollback;
