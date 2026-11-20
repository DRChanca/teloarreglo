begin;
create extension if not exists pgtap with schema extensions;
select plan(3);
insert into private.professional_profiles(id, private_address) values
 ('10000000-0000-0000-0000-000000000021', 'PRIVATE HOME'),
 ('10000000-0000-0000-0000-000000000022', 'ANOTHER PRIVATE HOME');
insert into public.approved_profiles
 (profile_id, name, description, trades, services, coverage, languages, phone, business_address)
values
 ('10000000-0000-0000-0000-000000000021', 'Commercial address', '', array['plumbing'],
  array['Fugas'], '[]', array['es'], '+34 000 000 021', 'Calle Gascona, local ficticio A, Oviedo'),
 ('10000000-0000-0000-0000-000000000022', 'No commercial premises', '', array['painting'],
  array['Pintura'], '[]', array['es'], '+34 000 000 022', null);
set local role anon;
select is((select business_address from public.catalogue(null, '10000000-0000-0000-0000-000000000021')),
 'Calle Gascona, local ficticio A, Oviedo', 'approved commercial address is public');
select is((select business_address from public.catalogue(null, '10000000-0000-0000-0000-000000000022')),
 null, 'private home is never used as fallback for an absent commercial address');
select ok((select not (to_jsonb(p) ? 'private_address') from public.catalogue(null, '10000000-0000-0000-0000-000000000021') p),
 'public profile has no private address field');
reset role;
select * from finish();
rollback;
