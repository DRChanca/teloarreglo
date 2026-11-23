-- A price agreed with a client never changes for that client (David, 2026-11-23).
-- - Client request (#20): the appointment keeps the reference prices in force when it is made.
-- - Business proposal (#20–#22): the proposal keeps its amount and visit fee.
-- Later profile edits (save_professional_offer, approved versions) never touch them. Payment (#22)
-- charges the amount stored in the accepted proposal, never the profile prices.
alter table public.appointments add column reference_prices jsonb not null default '[]'
    check (jsonb_typeof(reference_prices) = 'array');
-- Existing requests: prices were not editable by owners before this change, so the approved ones
-- are those in force when they were made.
update public.appointments a set reference_prices = p.prices
    from public.approved_profiles p where p.profile_id = a.profile_id;
grant select (reference_prices) on public.appointments to authenticated;

create function private.keep_appointment_prices()
returns trigger language plpgsql set search_path = '' as $$
begin
    if tg_op = 'INSERT' then
        new.reference_prices := coalesce(
            (select prices from public.approved_profiles where profile_id = new.profile_id), '[]'::jsonb);
    elsif new.reference_prices is distinct from old.reference_prices then
        raise exception using errcode = '42501', message = 'price_locked';
    end if;
    return new;
end;
$$;
create trigger appointments_kept_prices before insert or update on public.appointments
    for each row execute function private.keep_appointment_prices();

create function private.keep_proposal_amount()
returns trigger language plpgsql set search_path = '' as $$
begin
    -- A decided proposal (accepted, rejected, superseded) is history: its amount is what was agreed
    -- or offered. Profile edits never reach a proposal; no RPC rewrites an amount.
    if old.status <> 'pending' and (new.amount_cents is distinct from old.amount_cents
        or new.visit_fee_cents is distinct from old.visit_fee_cents) then
        raise exception using errcode = '42501', message = 'price_locked';
    end if;
    return new;
end;
$$;
create trigger appointment_proposals_kept_amount before update on public.appointment_proposals
    for each row execute function private.keep_proposal_amount();
revoke all on function private.keep_appointment_prices(), private.keep_proposal_amount()
    from public, anon, authenticated;
