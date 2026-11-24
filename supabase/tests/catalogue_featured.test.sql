-- «Perfil destacado» in the main catalogue list (David 2026-11-24): each row carries `featured` from the
-- single rule private.is_featured, and featured profiles come first, then the rest in the usual order
-- (name, profile_id), whatever trade filter is active.
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data,raw_app_meta_data) values
 ('00000000-0000-0000-0000-000000004901','cf-a@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000004902','cf-b@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000004903','cf-c@example.org',now(),'{}','{}'),
 ('00000000-0000-0000-0000-000000004904','cf-d@example.org',now(),'{}','{}');
insert into private.professional_profiles(id,owner_id,review_status) values
 ('10000000-0000-0000-0000-000000004901','00000000-0000-0000-0000-000000004901','approved'),
 ('10000000-0000-0000-0000-000000004902','00000000-0000-0000-0000-000000004902','approved'),
 ('10000000-0000-0000-0000-000000004903','00000000-0000-0000-0000-000000004903','approved'),
 ('10000000-0000-0000-0000-000000004904','00000000-0000-0000-0000-000000004904','approved');
-- Alphabetically: Albañil A < Electricista B < Fontanero C < Zapatero D (painting).
insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone) values
 ('10000000-0000-0000-0000-000000004901','Albañil A','Ficticio',array['masonry','plumbing'],array['Muros'],
  '[{"code":"33044","name":"Oviedo"}]',array['es'],'+34 000 000 491'),
 ('10000000-0000-0000-0000-000000004902','Electricista B','Ficticio',array['electrical','plumbing'],array['Cuadros'],
  '[{"code":"33044","name":"Oviedo"}]',array['es'],'+34 000 000 492'),
 ('10000000-0000-0000-0000-000000004903','Fontanero C','Ficticio',array['plumbing'],array['Fugas'],
  '[{"code":"33044","name":"Oviedo"}]',array['es'],'+34 000 000 493'),
 ('10000000-0000-0000-0000-000000004904','Zapatero D','Ficticio',array['painting'],array['Paredes'],
  '[]',array['es'],'+34 000 000 494');
-- C is an active subscriber; D subscribes but has no approved coverage, so it is never featured.
insert into private.subscription_simulations(profile_id, active) values
 ('10000000-0000-0000-0000-000000004903', true),
 ('10000000-0000-0000-0000-000000004904', true);

set local role anon;
select is((select array_agg(c.name order by c.ordinality) from public.catalogue() with ordinality c),
 array['Fontanero C','Albañil A','Electricista B','Zapatero D'],
 'featured first, then the rest by name');
select is((select array_agg(c.name order by c.ordinality) from public.catalogue('plumbing') with ordinality c),
 array['Fontanero C','Albañil A','Electricista B'],
 'featured first also with a trade filter');
select is((select array_agg(c.featured order by c.ordinality) from public.catalogue() with ordinality c),
 array[true,false,false,false], 'each row says whether it is featured');
select is((select featured from public.catalogue(null,'10000000-0000-0000-0000-000000004904')), false,
 'a subscriber without approved coverage is not featured');
select is((select featured from public.catalogue(null,'10000000-0000-0000-0000-000000004903')), true,
 'the single-profile lookup carries the flag too');
select ok((select not (to_jsonb(c) ? 'private_address') and not (to_jsonb(c) ? 'owner_id')
 from public.catalogue(null,'10000000-0000-0000-0000-000000004903') c),
 'the catalogue still exposes no private columns');
select is((select array[business_lat::text, cover_path] from public.catalogue(null,'10000000-0000-0000-0000-000000004901')),
 array[null::text, null::text], 'public columns added earlier are still returned');

reset role;
update private.subscription_simulations set active = false where profile_id = '10000000-0000-0000-0000-000000004903';
set local role authenticated;
select is((select array_agg(c.name order by c.ordinality) from public.catalogue() with ordinality c),
 array['Albañil A','Electricista B','Fontanero C','Zapatero D'],
 'cancelling the simulation restores the usual order');
select is((select bool_or(c.featured) from public.catalogue() c), false, 'and removes the flag');

reset role;
select * from finish();
rollback;
