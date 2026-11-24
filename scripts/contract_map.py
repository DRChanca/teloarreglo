#!/usr/bin/env python3
"""Real PostgREST contract for the map and manual location (#17). Loopback only.

Anonymous visitors (publishable key, no session) must be able to list concejo anchors and read
an approved commercial point, and must not reach boundaries or professionals' private locations.
Fixtures use one synthetic concejo code and random UUIDs, deleted at the end; nothing else changes.
"""
import argparse
import json
import os
import subprocess
import unittest
import uuid

from configuration import ROOT
from contract_identity import Api

CONCEJO = "33995"


def local_sql(sql):
    return subprocess.run(
        ["docker", "exec", "-i", "supabase_db_teloarreglo", "psql", "-U", "postgres",
         "-d", "postgres", "-X", "-q", "-v", "ON_ERROR_STOP=1"],
        input=sql, text=True, check=True, capture_output=True,
    )


class MapContract(unittest.TestCase):
    def test_visitors_list_concejo_anchors_inside_each_concejo(self):
        code, rows = api.request("/rest/v1/rpc/concejo_anchors", {})
        self.assertEqual(code, 200)
        row = next(r for r in rows if r["ine_code"] == CONCEJO)
        self.assertEqual(set(row), {"ine_code", "name", "lat", "lng"}, "no boundary in the anchor")
        self.assertTrue(43.0 < row["lat"] < 43.1 and -6.0 < row["lng"] < -5.9)

    def test_visitors_read_the_approved_commercial_point(self):
        code, rows = api.request("/rest/v1/rpc/catalogue", {"p_id": mapped})
        self.assertEqual(code, 200)
        self.assertEqual((rows[0]["business_lat"], rows[0]["business_lng"]), (43.05, -5.95))
        self.assertNotIn("private_address", rows[0])

    def test_boundaries_and_private_locations_stay_closed(self):
        code, _ = api.request("/rest/v1/concejos?select=boundary", method="GET")
        self.assertIn(code, (401, 403))
        code, _ = api.request("/rest/v1/professional_locations?select=*", method="GET")
        self.assertIn(code, (401, 403, 404))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True, help="loopback stack only")
    args = parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("This contract runs only against the loopback stack")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    mapped = str(uuid.uuid4())
    result = None
    try:
        local_sql(f"""begin;
            insert into public.concejos(ine_code, name, boundary) values ('{CONCEJO}', 'Contrato Mapa',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id, private_address) values ('{mapped}', 'PRIVATE HOME');
            insert into public.approved_profiles(profile_id, name, description, trades, services, coverage, languages,
              phone, business_address, business_lat, business_lng)
            values ('{mapped}', 'Contrato mapa', '', array['plumbing'], array['Fugas'], '[]', array['es'],
              '+34 000 001 790', 'Calle ficticia 1, Contrato', 43.05, -5.95);
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(MapContract))
    finally:
        local_sql(f"""begin; delete from public.approved_profiles where profile_id = '{mapped}';
            delete from private.professional_profiles where id = '{mapped}';
            delete from public.concejos where ine_code = '{CONCEJO}'; commit;""")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
