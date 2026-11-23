-- Optional, explicitly approved commercial address only. Never copy private_address.
-- Existing profiles retain null until a separate reviewed content update.
alter table public.approved_profiles
    add column business_address text
    check (business_address is null or length(btrim(business_address)) between 1 and 240);
comment on column public.approved_profiles.business_address is
    'Approved public commercial address; never a private home or client address fallback.';
