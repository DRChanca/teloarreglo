begin;
create extension if not exists pgtap with schema extensions;
select plan(6);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values ('00000000-0000-0000-0000-000000000012', 'identity-test@example.org', now(),
        '{"is_administrator":true,"role":"admin"}');

set local role anon;
select throws_ok(
    'select public.current_account()', '42501', null,
    'anonymous callers cannot consult an account'
);

set local role authenticated;
select throws_ok(
    'select public.current_account()', '28000', 'authentication_required',
    'a request without a session is rejected'
);
select set_config('request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000012","role":"authenticated"}', true);
select is(
    (public.current_account()->>'user_id'),
    '00000000-0000-0000-0000-000000000012',
    'account identity is derived from the session'
);
select is(
    (public.current_account()->>'is_administrator')::boolean, false,
    'editable user metadata cannot grant administration'
);
select throws_ok(
    'select * from private.administrators', '42501', null,
    'ordinary accounts cannot read the administration allowlist'
);
select throws_ok(
    $$insert into private.administrators(user_id)
      values ('00000000-0000-0000-0000-000000000012')$$,
    '42501', null,
    'ordinary accounts cannot grant themselves administration'
);

reset role;
select * from finish();
rollback;
