#!/usr/bin/env python3
"""Real Auth/PostgREST contract for the private home address (#37). Disposable fixtures on loopback ONLY.

Same calls as the Android client (`rpc/home_address`, `rpc/set_home_address`). Only the owner reads
and writes their home; visitors and other accounts get nothing, and no direct table write works.
"""
import argparse
import json
import os
import secrets
import subprocess
import unittest

from configuration import ROOT
from contract_identity import Api
from contract_chat import sql

CONCEJO = "33938"  # Synthetic code, never a real concejo.


class HomeContract(unittest.TestCase):
    def test_owner_only_home_with_server_resolved_concejo(self):
        home = {"p_address": "Calle Contrato 1, 2ºB", "p_lat": 43.05, "p_lng": -5.95,
                "p_source": "places", "p_place_id": "contract-place"}
        self.assertIn(api.request("/rest/v1/rpc/set_home_address", home)[0], (401, 403))
        self.assertIn(api.request("/rest/v1/rpc/home_address", {})[0], (401, 403))

        code, rows = api.request("/rest/v1/rpc/home_address", {}, tokens[0])
        self.assertEqual((code, rows), (200, []), "a new account has no home")
        code, rows = api.request("/rest/v1/rpc/set_home_address", home, tokens[0])
        self.assertEqual(code, 200, rows)
        self.assertEqual((rows[0]["ine_code"], rows[0]["concejo_name"], rows[0]["source"]),
                         (CONCEJO, "Contrato casa", "places"))
        code, rows = api.request("/rest/v1/rpc/set_home_address", dict(home, p_address="  "), tokens[0])
        self.assertEqual(code, 400)  # invalid_home_address
        code, rows = api.request("/rest/v1/rpc/home_address", {}, tokens[0])
        self.assertEqual((code, rows[0]["address"], rows[0]["lat"], rows[0]["lng"]),
                         (200, "Calle Contrato 1, 2ºB", 43.05, -5.95))

        code, rows = api.request("/rest/v1/home_addresses?select=*", token=tokens[1], method="GET")
        self.assertEqual((code, rows), (200, []), "another account reads nothing")
        code, rows = api.request("/rest/v1/rpc/home_address", {}, tokens[1])
        self.assertEqual((code, rows), (200, []))
        self.assertIn(api.request("/rest/v1/home_addresses?select=*", method="GET")[0], (401, 403))
        for token in tokens:
            owned = f"/rest/v1/home_addresses?user_id=eq.{users[0]}"
            self.assertIn(api.request(owned, {"address": "Otra"}, token, "PATCH")[0], (401, 403))
            self.assertIn(api.request(owned, None, token, "DELETE")[0], (401, 403))
        self.assertIn(api.request("/rest/v1/home_addresses", {"user_id": users[1], "address": "Directa 1", "lat": 43.05,
                                                              "lng": -5.95, "source": "pin"}, tokens[1])[0], (401, 403))
        # Public surfaces never contain it.
        code, public = api.request("/rest/v1/rpc/catalogue", {})
        self.assertEqual(code, 200)
        self.assertNotIn("Calle Contrato", json.dumps(public))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Home fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    result = None
    try:
        for role in ("owner", "other"):
            credentials = {"email": f"home-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                      dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local home fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local home fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato casa',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(HomeContract))
    finally:
        # Only this invocation's accounts (their homes cascade) and the synthetic concejo.
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
        sql(f"delete from public.home_addresses where ine_code = '{CONCEJO}'; "
            f"delete from public.concejos where ine_code = '{CONCEJO}';")
    raise SystemExit(0 if result is not None and result.wasSuccessful() else 1)
