#!/usr/bin/env python3
"""Real Auth/PostgREST profile review contract (#29). Disposable fixtures on loopback ONLY.

Runs the SQL printed by prepare_profile_review.py, then exercises the same RPCs the panel
and Android call: a normal account is refused, the administrator rejects and approves,
the owner reads the result and the public catalogue only shows the approved version.
"""
import argparse
import json
import os
import secrets
import subprocess
import unittest

from configuration import ROOT
from contract_identity import Api
from prepare_profile_review import TEST_PROFILE, TEST_SUBMISSION, TEST_CONTENT, preparation_sql

CORRECTED = "c1772900-0000-4000-8000-000000000002"


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


class ProfileReviewContract(unittest.TestCase):
    def test_panel_review_reaches_owner_and_catalogue(self):
        owner, other, administrator = tokens
        catalogue = f"/rest/v1/approved_profiles?profile_id=eq.{TEST_PROFILE}&select=name,description"
        reject = {"p_submission": str(TEST_SUBMISSION), "p_approve": False, "p_reason": "Falta concretar la cobertura"}
        # Knowing the route or the request is not enough.
        for token in (None, other, owner):
            self.assertIn(rpc("admin_profile_submissions", {}, token)[0], (401, 403))
            self.assertIn(rpc("review_professional_profile", reject, token)[0], (401, 403))
        for path in ("/rest/v1/profile_submissions", "/rest/v1/administrators", "/rest/v1/notices"):
            self.assertNotEqual(api.request(path + "?select=*", token=other, method="GET")[0], 200)
        self.assertIn(api.request("/auth/v1/user", {"data": {"is_administrator": True}}, other, "PUT")[0], (200,))
        code, account = rpc("current_account", {}, other)
        self.assertEqual((code, account["is_administrator"]), (200, False))
        self.assertEqual(api.request(catalogue, method="GET"), (200, []))

        code, pending = rpc("admin_profile_submissions", {}, administrator)
        self.assertEqual(code, 200)
        mine = [s for s in pending if s["submission_id"] == str(TEST_SUBMISSION)]
        self.assertEqual(mine[0]["content"]["name"], TEST_CONTENT["name"])
        self.assertNotIn("owner", json.dumps(mine))

        self.assertTrue(panel("reject", TEST_SUBMISSION, reject["p_reason"]), "panel adapter must reject")
        self.assertEqual(rpc("review_professional_profile", reject, administrator)[0], 200)
        self.assertNotEqual(rpc("review_professional_profile", dict(reject, p_approve=True), administrator)[0], 200)
        self.assertEqual(api.request(catalogue, method="GET"), (200, []))
        code, review = rpc("my_profile_review", {}, owner)
        self.assertEqual((code, review["status"], review["reason"]), (200, "rejected", reject["p_reason"]))
        code, notices = rpc("my_notices", {}, owner)
        self.assertEqual([(n["kind"], n["payload"]["status"]) for n in notices], [("profile_review", "rejected")])
        self.assertEqual(rpc("my_notices", {}, other), (200, []))

        corrected = dict(TEST_CONTENT, description="Corregido tras la revisión")
        sql(f"select private.submit_profile_version('{TEST_PROFILE}','{CORRECTED}',"
            f"'{json.dumps(corrected, ensure_ascii=False)}'::jsonb);")
        approve = {"p_submission": CORRECTED, "p_approve": True, "p_reason": "Datos coherentes"}
        self.assertTrue(panel("approve", CORRECTED, approve["p_reason"]), "panel adapter must approve")
        code, approved = rpc("review_professional_profile", approve, administrator)
        self.assertEqual((code, approved["status"], approved["published"]), (200, "approved", True))
        self.assertEqual(api.request(catalogue, method="GET"),
                         (200, [{"name": TEST_CONTENT["name"], "description": "Corregido tras la revisión"}]))
        code, history = rpc("admin_profile_submissions", {"p_status": "all"}, administrator)
        decided = {s["submission_id"]: s["decision"] for s in history if s["profile_id"] == str(TEST_PROFILE)}
        self.assertEqual(decided[CORRECTED]["administrator_email"], emails[2])
        self.assertEqual(rpc("my_profile_review", {}, owner)[1]["status"], "approved")
        self.assertIn(api.request(catalogue, {"name": "Forged"}, other, "PATCH")[0], (401, 403))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Review fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens, emails, passwords = [], [], [], []
    result = None
    try:
        for role in ("owner", "other", "administrator"):
            credentials = {"email": f"review-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                      dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local review fixture")
            users.append(user["id"])
            emails.append(credentials["email"])
            passwords.append(credentials["password"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local review fixture")
            tokens.append(session["access_token"])
        sql(preparation_sql(admin_email=emails[2], owner_email=emails[0]))
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ProfileReviewContract))
    finally:
        # Local disposable fixtures only. Audit triggers are bypassed solely for this cleanup.
        sql(f"""begin;
            set local session_replication_role = replica;
            delete from private.notices where source_id in (select id from private.profile_submissions where profile_id = '{TEST_PROFILE}');
            delete from private.profile_decisions where profile_id = '{TEST_PROFILE}';
            delete from private.profile_submissions where profile_id = '{TEST_PROFILE}';
            delete from public.approved_profiles where profile_id = '{TEST_PROFILE}';
            delete from private.professional_profiles where id = '{TEST_PROFILE}';
            delete from private.administrators where user_id in ({','.join(f"'{u}'" for u in users) or 'null'});
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
