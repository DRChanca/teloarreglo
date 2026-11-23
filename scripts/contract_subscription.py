#!/usr/bin/env python3
"""Real Auth/PostgREST contract of the simulated subscription (#33). Loopback fixtures ONLY.

Calls the same RPCs as Android: visitors and clients are refused, the professional tries and
cancels the simulation without any charge, and the public catalogue does not change.
"""
import argparse
import json
import os
import secrets
import subprocess
import unittest

from configuration import ROOT
from contract_identity import Api

PROFILE = "c1773300-0000-4000-8000-000000000001"
COVERAGE = [{"code": "33044", "name": "Oviedo"}]


def sql(statement):
    subprocess.run(
        ["docker", "exec", "-i", "supabase_db_teloarreglo", "psql", "-U", "postgres",
         "-d", "postgres", "-X", "-q", "-v", "ON_ERROR_STOP=1"],
        input=statement, text=True, check=True, capture_output=True,
    )


def rpc(name, body, token=None):
    return api.request(f"/rest/v1/rpc/{name}", body, token)


class SubscriptionContract(unittest.TestCase):
    def test_simulation_is_free_and_leaves_the_public_profile_untouched(self):
        professional, client = tokens
        public = f"/rest/v1/approved_profiles?profile_id=eq.{PROFILE}&select=name,coverage,rating,review_count"
        before = api.request(public, method="GET")
        self.assertEqual(before[0], 200)
        for name, body in (("my_subscription", {}), ("simulate_subscription", {"p_active": True})):
            self.assertIn(rpc(name, body)[0], (401, 403))
        self.assertEqual(rpc("my_subscription", {}, client), (200, None))
        self.assertIn(rpc("simulate_subscription", {"p_active": True}, client)[0], (401, 403))
        self.assertNotEqual(api.request("/rest/v1/subscription_simulations?select=*", token=professional, method="GET")[0], 200)

        code, offer = rpc("my_subscription", {}, professional)
        self.assertEqual((code, offer["price_cents"], offer["currency"], offer["period"]), (200, 500, "EUR", "month"))
        self.assertEqual((offer["simulated"], offer["charged"], offer["featured_zones"]), (False, False, COVERAGE))
        code, active = rpc("simulate_subscription", {"p_active": True}, professional)
        self.assertEqual((code, active["simulated"], active["charged"]), (200, True, False))
        self.assertEqual(rpc("simulate_subscription", {"p_active": True}, professional)[1], active)
        self.assertEqual(api.request(public, method="GET"), before)
        self.assertEqual(rpc("simulate_subscription", {"p_active": False}, professional)[1]["simulated"], False)
        self.assertEqual(api.request(public, method="GET"), before)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Subscription fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    result = None
    try:
        for role in ("professional", "client"):
            credentials = {"email": f"subscription-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                      dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local subscription fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local subscription fixture")
            tokens.append(session["access_token"])
        sql(f"""insert into private.professional_profiles(id, owner_id, review_status) values ('{PROFILE}', '{users[0]}', 'approved');
            insert into public.approved_profiles(profile_id, name, description, trades, services, coverage, languages, phone)
            values ('{PROFILE}', 'Suscripción de prueba', 'Ficticio', array['plumbing'], array['Fugas'],
                    '{json.dumps(COVERAGE)}', array['es'], '+34 000 000 033');""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(SubscriptionContract))
    finally:
        sql(f"""delete from private.subscription_simulations where profile_id = '{PROFILE}';
            delete from public.approved_profiles where profile_id = '{PROFILE}';
            delete from private.professional_profiles where id = '{PROFILE}';""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
