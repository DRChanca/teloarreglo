-- #36: cleaning ("Limpieza") joins the catalogue trades. Additive: existing rows stay valid.
alter table public.approved_profiles drop constraint approved_profiles_trades_check;
alter table public.approved_profiles add constraint approved_profiles_trades_check
    check (cardinality(trades) > 0 and trades <@
        array['plumbing','electrical','carpentry','painting','masonry','general','cleaning']);
