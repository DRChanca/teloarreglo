#!/usr/bin/env python3
"""Print a one-time, reviewable SQL load. Never connects to or resets a database."""
import argparse
import json
import uuid

from configuration import ROOT

NAMESPACE = uuid.UUID("c1771400-0000-4000-8000-000000000014")
TRADES = ("plumbing", "electrical", "carpentry", "painting", "masonry", "general")


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def profiles(namespace=NAMESPACE):
    rows = json.loads((ROOT / "supabase/catalogue/profiles.json").read_text())
    assert len(rows) == 18 and all(sum(p["trade"] == t for p in rows) == 3 for t in TRADES)
    return [(str(uuid.uuid5(namespace, str(i))), p) for i, p in enumerate(rows)]


def preparation_sql(owner_email, namespace=NAMESPACE):
    rows = profiles(namespace)
    statements = ["begin;", """create temporary table catalogue_preparation_owner (
 id uuid not null
) on commit drop;""", f"""insert into pg_temp.catalogue_preparation_owner(id)
 values ((select id from auth.users where email = {literal(owner_email)}
   and email_confirmed_at is not null and deleted_at is null
   and (banned_until is null or banned_until <= now())));"""]
    for i, (profile_id, p) in enumerate(rows):
        # First profile belongs to the verified account used in the demo.
        # The other fictional profiles are explicitly unclaimed, not fake Auth users.
        owner = "(select id from pg_temp.catalogue_preparation_owner)" if i == 0 else "null"
        statements.append(
            f"insert into private.professional_profiles(id, owner_id, review_status) "
            f"values ({literal(profile_id)}, {owner}, 'approved');"
        )
        price = [{
            "service": p["service"], "scope": p["scope"], "unit": p["unit"], "amount_cents": p["amount_cents"],
            "includes": "Mano de obra e IVA", "excludes": "Materiales y desplazamiento; requieren acuerdo previo",
        }]
        values = [
            literal(profile_id), literal(p["name"]), literal(p["description"]),
            f"array[{literal(p['trade'])}]", "array[" + ",".join(map(literal, p["services"])) + "]",
            literal(json.dumps(p["coverage"], ensure_ascii=False)) + "::jsonb",
            "array[" + ",".join(map(literal, p["languages"])) + "]",
            # Deliberately invalid numbers: never calls unrelated real people.
            literal(f"+34 000 000 {i + 1:03}"), f"array[{literal(p['trade'])}]",
            literal(json.dumps(price, ensure_ascii=False)) + "::jsonb",
        ]
        statements.append(
            "insert into public.approved_profiles "
            "(profile_id,name,description,trades,services,coverage,languages,phone,images,prices) "
            f"values ({','.join(values)});"
        )
    statements.append("commit;")
    return "\n".join(statements)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner-email", required=True, help="existing verified demo professional account")
    args = parser.parse_args()
    print(preparation_sql(args.owner_email))
