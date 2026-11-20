#!/usr/bin/env python3
"""Real PostgREST contract. Remote mode is read-only; fixtures only on loopback."""
import argparse
import json
import os
import secrets
import subprocess
import unittest
import uuid

from configuration import ROOT, configuration
from contract_identity import Api
from prepare_catalogue import profiles, preparation_sql, TRADES


def local_sql(sql):
    return subprocess.run(
        ["docker", "exec", "-i", "supabase_db_teloarreglo", "psql", "-U", "postgres",
         "-d", "postgres", "-X", "-q", "-v", "ON_ERROR_STOP=1"],
        input=sql, text=True, check=True, capture_output=True,
    )


class CatalogueContract(unittest.TestCase):
    def test_public_catalogue_and_detail(self):
        code, rows = api.request("/rest/v1/rpc/catalogue", {})
        self.assertEqual(code, 200, "public catalogue RPC must exist")
        prepared = [row for row in rows if row["profile_id"] in expected_ids]
        self.assertEqual(len(prepared), 18)
        for trade in TRADES:
            code, filtered = api.request("/rest/v1/rpc/catalogue", {"p_trade": trade})
            self.assertEqual(code, 200)
            self.assertTrue(all(trade in row["trades"] for row in filtered))
            self.assertEqual(sum(row["profile_id"] in expected_ids for row in filtered), 3)
        code, detail = api.request("/rest/v1/rpc/catalogue", {"p_id": expected_ids[0]})
        self.assertEqual(code, 200)
        self.assertEqual(len(detail), 1)
        self.assertEqual(detail[0]["name"], "Fontanería Nalón")
        self.assertTrue(detail[0]["services"] and detail[0]["coverage"] and detail[0]["images"])
        self.assertEqual(detail[0]["prices"][0]["amount_cents"], 3500)
        for row in prepared:
            self.assertNotIn("owner_id", row)
            self.assertNotIn("private_address", row)
            self.assertNotIn("draft", row)
            self.assertNotIn("latitude", row)

    def test_unknown_or_pending_profile_is_not_public(self):
        code, rows = api.request("/rest/v1/rpc/catalogue", {"p_id": pending_id})
        self.assertEqual(code, 200)
        self.assertEqual(rows, [])

    def test_direct_private_data_is_inaccessible(self):
        code, _ = api.request("/rest/v1/professional_profiles?select=*", method="GET")
        self.assertIn(code, (401, 403, 404))

    def test_real_owner_capabilities_draft_and_denied_writes(self):
        if not args.local:
            self.skipTest("writes are tested on loopback only")
        code, account = api.request("/rest/v1/rpc/current_account", {}, owner_token)
        self.assertEqual(code, 200)
        self.assertTrue(account["can_hire"])
        self.assertEqual(account["professional_profile_id"], expected_ids[0])
        for token in (None, other_token):
            code, _ = api.request("/rest/v1/rpc/save_professional_draft",
                                  {"p_profile_id": expected_ids[0], "p_draft": {"name": "Forged"}}, token)
            self.assertIn(code, (401, 403))
        code, _ = api.request("/rest/v1/rpc/save_professional_draft",
                              {"p_profile_id": expected_ids[0], "p_draft": {"name": "Pending edit"}}, owner_token)
        self.assertIn(code, (200, 204))
        code, detail = api.request("/rest/v1/rpc/catalogue", {"p_id": expected_ids[0]})
        self.assertEqual(code, 200)
        self.assertEqual(detail[0]["name"], "Fontanería Nalón")
        for token in (None, owner_token, other_token):
            code, _ = api.request(f"/rest/v1/approved_profiles?profile_id=eq.{expected_ids[0]}",
                                  {"name": "Forged"}, token, method="PATCH")
            self.assertIn(code, (401, 403))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true")
    args = parser.parse_args()
    created = []
    namespace = uuid.uuid4() if args.local else None
    expected_ids = [pid for pid, _ in (profiles(namespace) if namespace else profiles())]
    pending_id = str(uuid.uuid4())
    admin = None
    try:
        if args.local:
            config = json.loads(subprocess.check_output(
                [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"],
                cwd=ROOT, text=True,
            ))
            if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
                raise SystemExit("Fixtures require the loopback stack")
            api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
            admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
            tokens = []
            owner_email = None
            for role in ("owner", "other"):
                email = f"catalogue-{role}-{secrets.token_hex(6)}@example.org"
                password = secrets.token_urlsafe(32)
                code, user = admin.request("/auth/v1/admin/users",
                    {"email": email, "password": password, "email_confirm": True}, config["SERVICE_ROLE_KEY"])
                if code not in (200, 201):
                    raise SystemExit("Could not create local verified fixture")
                created.append(user["id"])
                code, session = api.request("/auth/v1/token?grant_type=password", {"email": email, "password": password})
                if code != 200:
                    raise SystemExit("Could not sign in local fixture")
                tokens.append(session["access_token"])
                if role == "owner":
                    owner_email = email
            owner_token, other_token = tokens
            local_sql(preparation_sql(owner_email, namespace))
            local_sql(f"insert into private.professional_profiles(id, draft) values ('{pending_id}', '{{\"name\":\"Not approved\"}}');")
        else:
            config = configuration()
            api = Api(config["TEST_SUPABASE_URL"], config["TEST_SUPABASE_PUBLISHABLE_KEY"])
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(CatalogueContract))
    finally:
        if args.local and admin:
            # Delete only UUIDs belonging to this invocation; never reset or alter prepared fixtures.
            ids = ",".join(f"'{pid}'" for pid in expected_ids + [pending_id])
            local_sql(f"begin; delete from public.approved_profiles where profile_id in ({ids}); "
                      f"delete from private.professional_profiles where id in ({ids}); commit;")
            for user_id in created:
                admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result.wasSuccessful() else 1)
