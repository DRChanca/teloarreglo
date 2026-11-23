#!/usr/bin/env python3
"""Real Auth/PostgREST contract for the person's name (#40). Disposable fixtures on loopback ONLY."""
import argparse
import json
import os
import secrets
import subprocess
import unittest
import uuid

from configuration import ROOT
from contract_identity import Api
from contract_chat import sql


class AccountNamesContract(unittest.TestCase):
    def name_of(self, token):
        code, name = api.request("/rest/v1/rpc/my_account_name", {}, token)
        self.assertEqual(code, 200)
        return name

    def test_signup_and_google_names_reach_the_inbox_without_the_email(self):
        # Email signup sends full_name as Auth metadata (what the app does); Auth's trigger stores it.
        self.assertEqual(self.name_of(tokens["signup"]), "Marta Registro")
        # Google sign-in leaves name/full_name in the metadata: stored without asking.
        self.assertEqual(self.name_of(tokens["google"]), "Pedro Google")
        self.assertIsNone(self.name_of(tokens["professional"]))
        for body in ({"p_name": "   "}, {"p_name": "x" * 81}):
            self.assertEqual(api.request("/rest/v1/rpc/set_account_name", body, tokens["professional"])[0], 400)
        self.assertIn(api.request("/rest/v1/rpc/set_account_name", {"p_name": "Intruso"})[0], (401, 403))
        self.assertIn(api.request("/rest/v1/account_names", {"user_id": users["signup"], "name": "Hack"},
                                  tokens["professional"])[0], (401, 403))
        code, rows = api.request("/rest/v1/account_names?select=*", token=tokens["professional"], method="GET")
        self.assertEqual((code, rows), (200, []))

        code, conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id},
                                         tokens["signup"])
        self.assertEqual(code, 200)
        code, saved = api.request("/rest/v1/rpc/set_account_name", {"p_name": "  Marta Cambiada "}, tokens["signup"])
        self.assertEqual((code, saved), (200, "Marta Cambiada"))
        code, inbox = api.request("/rest/v1/rpc/conversation_inbox", {}, tokens["professional"])
        self.assertEqual(code, 200)
        [row] = inbox
        self.assertEqual((row["id"], row["client_name"]), (conversation["id"], "Marta Cambiada"))
        self.assertNotIn(emails["signup"], json.dumps(row))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Account name fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens, emails = {}, {}, {}
    profile_id = str(uuid.uuid4())
    result = None
    try:
        for role in ("signup", "google", "professional"):
            credentials = {"email": f"name-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            emails[role] = credentials["email"]
            if role == "signup":
                code, user = api.request("/auth/v1/signup", dict(credentials, data={"full_name": " Marta Registro "}))
                user = user.get("user", user) if user else None
                if code != 200 or not user:
                    raise SystemExit("Could not sign up local name fixture")
                users[role] = user["id"]
                code, _ = admin.request(f"/auth/v1/admin/users/{users[role]}", {"email_confirm": True},
                                        config["SERVICE_ROLE_KEY"], method="PUT")
            else:
                metadata = {"name": "Pedro Google"} if role == "google" else {}
                code, user = admin.request("/auth/v1/admin/users",
                                          dict(credentials, email_confirm=True, user_metadata=metadata),
                                          config["SERVICE_ROLE_KEY"])
                users[role] = user["id"] if user else None
            if code not in (200, 201):
                raise SystemExit("Could not create local name fixture")
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local name fixture")
            tokens[role] = session["access_token"]
        sql(f"""begin;
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users["professional"]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Name contract','Fixture',array['plumbing'],array['Repair'],'[]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(AccountNamesContract))
    finally:
        # Only this invocation's random fixture UUIDs; never prepared data.
        sql(f"""begin;
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            commit;""")
        for user_id in users.values():
            if user_id:
                admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
