#!/usr/bin/env python3
"""Print reviewable SQL for the profile review demo (#29). Never connects to a database.

--admin-email grants administration to an EXISTING, verified account that owns no
professional profile. --owner-email creates the fictional test profile, pending review,
owned by an existing verified account without a profile. Either or both may be given.
Repeating it aborts the whole transaction instead of overwriting prepared data.
"""
import argparse
import json
import uuid

TEST_PROFILE = uuid.UUID("c1772900-0000-4000-8000-000000000029")
TEST_SUBMISSION = uuid.UUID("c1772900-0000-4000-8000-000000000001")
# Fictional data. The phone number is deliberately invalid (+34 000 ...).
TEST_CONTENT = {
    "name": "Perfil de prueba para revisión",
    "description": "Perfil ficticio preparado para comprobar la revisión desde el panel.",
    "trades": ["plumbing"],
    "services": ["Reparación de fugas"],
    "coverage": [{"code": "33044", "name": "Oviedo"}],
    "languages": ["es"],
    "phone": "+34 000 000 290",
    "images": ["plumbing"],
    "prices": [{"service": "Reparación de fugas", "scope": "Fuga accesible", "unit": "job",
                "amount_cents": 6000, "includes": "Mano de obra e IVA",
                "excludes": "Materiales y desplazamiento; requieren acuerdo previo"}],
}


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def verified_account(email):
    return (f"(select id from auth.users where email = {literal(email)} and email_confirmed_at is not null"
            " and deleted_at is null and (banned_until is null or banned_until <= now()))")


def preparation_sql(admin_email=None, owner_email=None):
    if not admin_email and not owner_email:
        raise ValueError("Give --admin-email, --owner-email or both")
    if admin_email and owner_email and admin_email.lower() == owner_email.lower():
        raise ValueError("Administration must use a separate account")
    out = ["begin;"]
    if admin_email:
        account = verified_account(admin_email)
        out.append(f"""do $$ begin
 if {account} is null then raise exception 'administrator account must exist and be verified'; end if;
 if exists (select 1 from private.professional_profiles where owner_id = {account}) then
  raise exception 'administrator account must not own a professional profile'; end if;
end $$;
insert into private.administrators(user_id) values ({account});""")
    if owner_email:
        account = verified_account(owner_email)
        out.append(f"""do $$ begin
 if {account} is null then raise exception 'owner account must exist and be verified'; end if;
 if exists (select 1 from private.administrators where user_id = {account}) then
  raise exception 'the test profile owner cannot be an administrator'; end if;
end $$;
insert into private.professional_profiles(id, owner_id, review_status)
 values ({literal(TEST_PROFILE)}, {account}, 'pending');
select private.submit_profile_version({literal(TEST_PROFILE)}, {literal(TEST_SUBMISSION)},
 {literal(json.dumps(TEST_CONTENT, ensure_ascii=False))}::jsonb);""")
    out.append("commit;")
    return "\n".join(out)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--admin-email", help="existing verified account, separate from any professional")
    parser.add_argument("--owner-email", help="existing verified account that will own the test profile")
    args = parser.parse_args()
    try:
        print(preparation_sql(args.admin_email, args.owner_email))
    except ValueError as error:
        raise SystemExit(str(error))
