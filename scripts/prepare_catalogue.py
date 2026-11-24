#!/usr/bin/env python3
"""Print a one-time, reviewable SQL load. Never connects to or resets a database."""
import argparse
import json
import uuid

from configuration import ROOT

NAMESPACE = uuid.UUID("c1771400-0000-4000-8000-000000000014")
TRADES = ("plumbing", "electrical", "carpentry", "painting", "masonry", "general", "cleaning")


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def profiles(namespace=NAMESPACE):
    rows = json.loads((ROOT / "supabase/catalogue/profiles.json").read_text())
    assert len(rows) == 21 and all(sum(p["trade"] == t for p in rows) == 3 for t in TRADES)
    details = {p["name"]: p for p in json.loads((ROOT / "supabase/catalogue/details.json").read_text())}
    if set(details) != {p["name"] for p in rows}:
        raise ValueError("Catalogue details must match the 21 prepared profiles")
    rows = [dict(p, **details[p["name"]]) for p in rows]
    return [(str(uuid.uuid5(namespace, str(i))), p) for i, p in enumerate(rows)]


def reference_prices(p):
    standard = {"includes": "Mano de obra e IVA",
                "excludes": "Materiales y desplazamiento; requieren acuerdo previo"}
    return [
        dict(standard, service=p["service"], scope=p["scope"], unit=p["unit"], amount_cents=p["amount_cents"]),
        {
            "service": "Visita de valoración", "unit": "job", "amount_cents": p["visit_cents"],
            "scope": "Visita de hasta 30 minutos para valorar el problema; " + (
                "se cobra aunque no se repare." if p["visit_cents"] else "sin obligación de contratar."
            ),
            "includes": "Valoración e IVA",
            "excludes": "Reparación, materiales y desplazamiento; requieren acuerdo previo",
        },
        dict(standard, **p["extra_price"]),
    ]


def public_services(p):
    return list(dict.fromkeys(p["services"] + [price["service"] for price in reference_prices(p)[1:]]))


def preparation_sql(owner_email, namespace=NAMESPACE, start=0):
    """start=18 prints only the cleaning profiles (#36) for a catalogue that already has the first 18."""
    rows = profiles(namespace)
    statements = ["begin;"] if start else ["begin;", """create temporary table catalogue_preparation_owner (
 id uuid not null
) on commit drop;""", f"""insert into pg_temp.catalogue_preparation_owner(id)
 values ((select id from auth.users where email = {literal(owner_email)}
   and email_confirmed_at is not null and deleted_at is null
   and (banned_until is null or banned_until <= now())));"""]
    for i, (profile_id, p) in enumerate(rows):
        if i < start:
            continue
        # First profile belongs to the verified account used in the demo.
        # The other fictional profiles are explicitly unclaimed, not fake Auth users.
        owner = "(select id from pg_temp.catalogue_preparation_owner)" if i == 0 else "null"
        statements.append(
            f"insert into private.professional_profiles(id, owner_id, review_status) "
            f"values ({literal(profile_id)}, {owner}, 'approved');"
        )
        price = reference_prices(p)
        values = [
            literal(profile_id), literal(p["name"]), literal(p["description"]),
            f"array[{literal(p['trade'])}]", "array[" + ",".join(map(literal, public_services(p))) + "]",
            literal(json.dumps(p["coverage"], ensure_ascii=False)) + "::jsonb",
            "array[" + ",".join(map(literal, p["languages"])) + "]",
            # Deliberately invalid numbers: never calls unrelated real people.
            literal(f"+34 000 000 {i + 1:03}"), f"array[{literal(p['trade'])}]",
            literal(json.dumps(price, ensure_ascii=False)) + "::jsonb",
            literal(p["business_address"]),
        ]
        statements.append(
            "insert into public.approved_profiles "
            "(profile_id,name,description,trades,services,coverage,languages,phone,images,prices,business_address) "
            f"values ({','.join(values)});"
        )
    statements.append("commit;")
    return "\n".join(statements)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner-email", help="existing verified demo professional account (initial load)")
    parser.add_argument("--only-cleaning", action="store_true",
                        help="print only the three cleaning profiles added by #36, all unclaimed")
    args = parser.parse_args()
    if not args.only_cleaning and not args.owner_email:
        parser.error("--owner-email is required for the initial load")
    print(preparation_sql(args.owner_email, start=18 if args.only_cleaning else 0))
