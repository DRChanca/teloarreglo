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
from update_catalogue_details import update_sql


def local_sql(sql):
    return subprocess.run(
        ["docker", "exec", "-i", "supabase_db_teloarreglo", "psql", "-U", "postgres",
         "-d", "postgres", "-X", "-q", "-v", "ON_ERROR_STOP=1"],
        input=sql, text=True, check=True, capture_output=True,
    )


class CatalogueContract(unittest.TestCase):
    def test_cleaning_addition_loads_only_the_three_new_profiles_once(self):
        if not args.local:
            self.skipTest("loads are tested on loopback only")
        self.assertIn("cleaning", TRADES)
        cleaning = expected_ids[18:]
        ids = ",".join(f"'{pid}'" for pid in cleaning)
        # Arrange the hosted state before #36: the 18 original profiles only.
        local_sql(f"delete from public.approved_profiles where profile_id in ({ids}); "
                  f"delete from private.professional_profiles where id in ({ids});")
        local_sql(preparation_sql(None, namespace, start=18))
        code, rows = api.request("/rest/v1/rpc/catalogue", {"p_trade": "cleaning"})
        self.assertEqual(code, 200)
        self.assertEqual(sorted(r["profile_id"] for r in rows if r["profile_id"] in expected_ids), sorted(cleaning))
        self.assertTrue(all(r["business_address"] and len(r["prices"]) == 3 for r in rows
                            if r["profile_id"] in cleaning))
        with self.assertRaises(subprocess.CalledProcessError):
            local_sql(preparation_sql(None, namespace, start=18))

    def test_reviewed_update_preserves_prepared_data_and_aborts_on_concurrent_changes(self):
        if not args.local:
            self.skipTest("content updates are tested on loopback only")
        first, second = expected_ids[:2]
        # Arrange old-style fixtures only within this invocation's random UUIDs.
        local_sql(f"""update public.approved_profiles
            set business_address = null, prices = jsonb_build_array(prices->0)
            where profile_id in ('{first}', '{second}');
            update public.approved_profiles set description = 'Prepared content to preserve'
            where profile_id = '{first}';""")
        code, before = api.request("/rest/v1/rpc/catalogue", {})
        self.assertEqual(code, 200)
        sql = update_sql(before, namespace)
        # A later edit must abort the whole batch rather than overwrite it.
        local_sql(f"""update public.approved_profiles set services = services || array['Later prepared service']
            where profile_id = '{second}';""")
        with self.assertRaises(subprocess.CalledProcessError):
            local_sql(sql)
        code, first_detail = api.request("/rest/v1/rpc/catalogue", {"p_id": first})
        self.assertEqual(code, 200)
        self.assertIsNone(first_detail[0]["business_address"])
        self.assertEqual(len(first_detail[0]["prices"]), 1, "the earlier update must also have rolled back")
        code, current = api.request("/rest/v1/rpc/catalogue", {})
        self.assertEqual(code, 200)
        local_sql(update_sql(current, namespace))
        code, after = api.request("/rest/v1/rpc/catalogue", {"p_id": first})
        self.assertEqual(code, 200)
        self.assertEqual(after[0]["description"], "Prepared content to preserve")
        self.assertEqual(after[0]["prices"][0], first_detail[0]["prices"][0])
        self.assertEqual(len(after[0]["prices"]), 3)
        code, second_detail = api.request("/rest/v1/rpc/catalogue", {"p_id": second})
        self.assertEqual(code, 200)
        self.assertIn("Later prepared service", second_detail[0]["services"])
        self.assertEqual(len(second_detail[0]["prices"]), 3)

    def test_commercial_addresses_and_multiple_reference_services(self):
        if not args.local and not args.details:
            self.skipTest("run --details after the reviewed catalogue content update")
        code, detail = api.request("/rest/v1/rpc/catalogue", {"p_id": expected_ids[0]})
        self.assertEqual(code, 200)
        self.assertEqual(detail[0].get("business_address"), "Calle Gascona, local ficticio 01, Oviedo")
        self.assertEqual(len(detail[0]["prices"]), 3)
        visit = next(price for price in detail[0]["prices"] if price["service"] == "Visita de valoración")
        self.assertEqual(visit["amount_cents"], 2000)
        self.assertIn("aunque no se repare", visit["scope"])
        code, free_detail = api.request("/rest/v1/rpc/catalogue", {"p_id": expected_ids[7]})
        self.assertEqual(code, 200)
        free_visit = next(p for p in free_detail[0]["prices"] if p["service"] == "Visita de valoración")
        self.assertEqual(free_visit["amount_cents"], 0)
        self.assertIn("sin obligación de contratar", free_visit["scope"])
        code, rows = api.request("/rest/v1/rpc/catalogue", {})
        self.assertEqual(code, 200)
        for row in rows:
            if row["profile_id"] in expected_ids:
                self.assertTrue(row.get("business_address"))
                self.assertGreaterEqual(len(row["prices"]), 3)

    def test_public_catalogue_and_detail(self):
        code, rows = api.request("/rest/v1/rpc/catalogue", {})
        self.assertEqual(code, 200, "public catalogue RPC must exist")
        prepared = [row for row in rows if row["profile_id"] in expected_ids]
        self.assertEqual(len(prepared), 21)
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
    parser.add_argument("--details", action="store_true", help="also verify reviewed commercial addresses and expanded prices")
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
