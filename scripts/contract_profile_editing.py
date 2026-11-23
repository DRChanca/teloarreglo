#!/usr/bin/env python3
"""Real Auth/PostgREST contract for presenting and changing profiles (#30). Loopback ONLY.

Owner -> panel -> catalogue with disposable accounts: the owner starts and presents a profile
through the same RPCs Android calls, the panel's own adapter rejects and approves it, a later
public change stays pending while the approved version remains public, and a third account
cannot read or change any of it directly.
"""
import argparse
import json
import os
import secrets
import subprocess
import unittest
import uuid

from configuration import ROOT
from contract_identity import Api

CONTENT = {"name": "Fontanería Contrato 30", "description": "Primera versión", "trades": ["plumbing"],
           "services": ["Fugas"], "coverage": [{"code": "33044", "name": "Oviedo"}], "languages": ["es"],
           "phone": "+34 600 000 030", "images": [], "prices": [], "business_address": None}
PRIVATE = "Calle privada del contrato 30"


def sql(statement):
    subprocess.run(
        ["docker", "exec", "-i", "supabase_db_teloarreglo", "psql", "-U", "postgres",
         "-d", "postgres", "-X", "-q", "-v", "ON_ERROR_STOP=1"],
        input=statement, text=True, check=True, capture_output=True,
    )


def rpc(name, body, token=None):
    return api.request(f"/rest/v1/rpc/{name}", body, token)


def panel(decision, submission, reason):
    """Runs the panel's own Supabase adapter (panel/src/review.local.test.ts) as the administrator."""
    env = dict(os.environ, PANEL_LOCAL_URL=config["API_URL"], PANEL_LOCAL_KEY=config["PUBLISHABLE_KEY"],
               PANEL_ADMIN_EMAIL=emails[2], PANEL_ADMIN_PASSWORD=passwords[2],
               PANEL_OTHER_EMAIL=emails[1], PANEL_OTHER_PASSWORD=passwords[1],
               PANEL_SUBMISSION=str(submission), PANEL_DECISION=decision, PANEL_REASON=reason)
    run = subprocess.run(["npx", "--no-install", "vitest", "run", "src/review.local.test.ts"],
                         cwd=ROOT / "panel", env=env, text=True, capture_output=True)
    return run.returncode == 0 and "2 passed" in run.stdout


class ProfileEditingContract(unittest.TestCase):
    def test_owner_presents_corrects_and_changes_without_losing_the_public_version(self):
        owner, other, administrator = tokens
        self.assertEqual(rpc("my_professional_profile", {}, owner), (200, None))
        code, started = rpc("start_professional_profile", {}, owner)
        self.assertEqual(code, 200)
        profile = started["profile_id"]
        profiles.append(profile)
        self.assertEqual(rpc("current_account", {}, owner)[1]["professional_profile_id"], profile)
        self.assertEqual(rpc("save_professional_private_address", {"p_address": PRIVATE}, owner)[0], 200)
        catalogue = f"/rest/v1/approved_profiles?profile_id=eq.{profile}&select=description"

        first = str(uuid.uuid4())
        submit = {"p_submission": first, "p_content": CONTENT}
        for token in (None, other):  # Anyone else: no access, or no own profile to present.
            self.assertNotEqual(rpc("submit_professional_profile", submit, token)[0], 200)
        code, pending = rpc("submit_professional_profile", submit, owner)
        self.assertEqual((code, pending["status"]), (200, "pending"))
        self.assertEqual(rpc("submit_professional_profile", submit, owner)[1]["version"], pending["version"])
        self.assertEqual(api.request(catalogue, method="GET"), (200, []))
        self.assertEqual(rpc("my_professional_profile", {}, other), (200, None))
        for path in ("/rest/v1/professional_profiles", "/rest/v1/profile_submissions"):
            self.assertNotEqual(api.request(path + "?select=*", token=other, method="GET")[0], 200)

        self.assertTrue(panel("reject", first, "Falta Gijón"), "panel adapter must reject")
        code, mine = rpc("my_professional_profile", {}, owner)
        self.assertEqual((mine["review"]["status"], mine["review"]["reason"]), ("rejected", "Falta Gijón"))
        self.assertEqual(mine["editable"]["description"], "Primera versión")

        corrected = str(uuid.uuid4())
        content = dict(CONTENT, coverage=CONTENT["coverage"] + [{"code": "33024", "name": "Gijón"}])
        self.assertEqual(rpc("submit_professional_profile", {"p_submission": corrected, "p_content": content}, owner)[0], 200)
        self.assertTrue(panel("approve", corrected, "Correcto"), "panel adapter must approve")
        self.assertEqual(api.request(catalogue, method="GET"), (200, [{"description": "Primera versión"}]))

        change = str(uuid.uuid4())
        changed = dict(content, description="Cambio pendiente")
        self.assertEqual(rpc("submit_professional_profile", {"p_submission": change, "p_content": changed}, owner)[0], 200)
        self.assertEqual(api.request(catalogue, method="GET"), (200, [{"description": "Primera versión"}]))
        code, listed = rpc("admin_profile_submissions", {}, administrator)
        row = next(s for s in listed if s["submission_id"] == change)
        self.assertEqual((row["public_content"]["description"], row["content"]["description"]),
                         ("Primera versión", "Cambio pendiente"))
        self.assertNotIn(PRIVATE, json.dumps(listed, ensure_ascii=False))
        self.assertTrue(panel("reject", change, "Descripción poco clara"), "panel adapter must reject the change")
        self.assertEqual(api.request(catalogue, method="GET"), (200, [{"description": "Primera versión"}]))
        code, mine = rpc("my_professional_profile", {}, owner)
        self.assertEqual((mine["review"]["status"], mine["review"]["published"]), ("rejected", True))
        self.assertEqual(mine["public"]["description"], "Primera versión")
        public = api.request(f"/rest/v1/rpc/catalogue", {"p_id": profile})
        self.assertNotIn(PRIVATE, json.dumps(public, ensure_ascii=False))
        self.assertIn(api.request(catalogue, {"description": "Forged"}, other, "PATCH")[0], (401, 403))

        # Services and reference prices are published at once, without review (David, 2026-11-23).
        prices = [{"service": "Reparación de fuga", "scope": "Una hora", "unit": "hour", "amount_cents": 3500,
                   "includes": "Mano de obra e IVA", "excludes": "Materiales"}]
        offer = {"p_services": ["Fugas", "Grifería"], "p_prices": prices}
        for token in (None, other):
            self.assertNotEqual(rpc("save_professional_offer", offer, token)[0], 200)
        bad = dict(offer, p_prices=[dict(prices[0], unit="lightyear")])
        self.assertEqual(rpc("save_professional_offer", bad, owner)[0], 400)
        code, mine = rpc("save_professional_offer", offer, owner)
        self.assertEqual((code, mine["editable"]["prices"]), (200, prices))
        offered = f"/rest/v1/approved_profiles?profile_id=eq.{profile}&select=services,prices"
        self.assertEqual(api.request(offered, method="GET"), (200, [{"services": ["Fugas", "Grifería"], "prices": prices}]))
        code, listed = rpc("admin_profile_submissions", {}, administrator)
        self.assertEqual([s for s in listed if s["profile_id"] == profile], [], "a price change creates no submission")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Profile fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens, emails, passwords, profiles = [], [], [], [], []
    result = None
    try:
        for role in ("owner", "other", "administrator"):
            credentials = {"email": f"editing-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                      dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local profile fixture")
            users.append(user["id"])
            emails.append(credentials["email"])
            passwords.append(credentials["password"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local profile fixture")
            tokens.append(session["access_token"])
        sql(f"insert into private.administrators values ('{users[2]}');")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ProfileEditingContract))
    finally:
        # Local disposable fixtures only. Audit triggers are bypassed solely for this cleanup.
        ids = ",".join(f"'{p}'" for p in profiles) or "null"
        sql(f"""begin;
            set local session_replication_role = replica;
            delete from private.notices where source_id in (select id from private.profile_submissions where profile_id in ({ids}));
            delete from private.profile_decisions where profile_id in ({ids});
            delete from private.profile_submissions where profile_id in ({ids});
            delete from public.approved_profiles where profile_id in ({ids});
            delete from private.professional_profiles where id in ({ids});
            delete from private.administrators where user_id in ({','.join(f"'{u}'" for u in users) or 'null'});
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
