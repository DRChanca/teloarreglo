-- The search Edge Function calls resolve_concejo with the server key (service_role),
-- which 20261121000100 did not grant. Additive fix; no change to client privileges.
grant execute on function public.resolve_concejo(double precision, double precision, double precision) to service_role;
-- It also validates a manually chosen concejo by reading its code and name.
grant select (ine_code, name) on public.concejos to service_role;
