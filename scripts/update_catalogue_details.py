#!/usr/bin/env python3
"""Read the existing public catalogue and PRINT a guarded update; never execute SQL."""
import argparse
import json
import uuid

from configuration import ROOT, configuration
from contract_identity import Api
from prepare_catalogue import NAMESPACE, literal, profiles, reference_prices


def json_literal(value):
    return literal(json.dumps(value, ensure_ascii=False)) + "::jsonb"


def text_array(values):
    return "array[" + ",".join(map(literal, values)) + "]::text[]"


def update_sql(current_rows, namespace=NAMESPACE, include_migration=False):
    existing = {p["profile_id"]: p for p in current_rows}
    changes = []
    for profile_id, prepared in profiles(namespace):
        old = existing.get(profile_id)
        if old is None or old["name"] != prepared["name"]:
            raise ValueError("A prepared profile is missing or has been renamed; review before updating")
        prices = list(old["prices"])
        names = {p["service"] for p in prices}
        for price in reference_prices(prepared)[1:]:
            if price["service"] not in names:
                prices.append(price)
                names.add(price["service"])
        services = list(old["services"])
        for price in reference_prices(prepared)[1:]:
            if price["service"] not in services:
                services.append(price["service"])
        address = prepared["business_address"]
        if old.get("business_address") not in (None, address):
            raise ValueError("A prepared profile already has a different business address; review before updating")
        if (old.get("business_address"), old["prices"], old["services"]) == (address, prices, services):
            continue
        old_address = "null" if old.get("business_address") is None else literal(old["business_address"])
        changes.append(f"""
 update public.approved_profiles
 set business_address = {literal(address)},
     prices = {json_literal(prices)},
     services = {text_array(services)},
     approved_at = now()
 where profile_id = {literal(profile_id)}
   and name = {literal(old["name"])}
   and business_address is not distinct from {old_address}
   and prices = {json_literal(old["prices"])}
   and services = {text_array(old["services"])};
 get diagnostics changed = row_count;
 if changed <> 1 then
   raise exception 'Catalogue content changed since preparation; regenerate the SQL without overwriting it';
 end if;
""")
    statements = ["begin;", "set local standard_conforming_strings = on;"]
    if include_migration:
        statements.append((ROOT / "supabase/migrations/20261120000200_public_business_address.sql").read_text())
    if changes:
        body = "declare changed integer;\nbegin\n" + "".join(changes) + "\nend;\n"
        delimiter = "$catalogue_" + uuid.uuid4().hex + "$"
        while delimiter in body:
            delimiter = "$catalogue_" + uuid.uuid4().hex + "$"
        statements.append(f"do {delimiter}\n{body}{delimiter};")
    else:
        statements.append("-- All requested catalogue details are already present; no content changes.")
    statements.append("commit;")
    return "\n".join(statements) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    config = configuration()
    url = config["TEST_SUPABASE_URL"]
    if config.get("PRESENTATION_SUPABASE_URL") != url:
        raise SystemExit("Project aliases differ; refusing an ambiguous update")
    code, rows = Api(url, config["TEST_SUPABASE_PUBLISHABLE_KEY"]).request("/rest/v1/rpc/catalogue", {})
    if code != 200 or not isinstance(rows, list) or not rows:
        raise SystemExit(f"Cannot read the existing catalogue (HTTP {code}); no SQL generated")
    print(update_sql(rows, include_migration=any("business_address" not in p for p in rows)), end="")
