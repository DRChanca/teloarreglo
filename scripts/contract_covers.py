#!/usr/bin/env python3
"""Real owner/admin/visitor + Storage contract (covers published without review). Writes ONLY to the loopback stack."""
import argparse
import hashlib
import json
import os
import secrets
import subprocess
import unittest
import urllib.error
import urllib.request
import uuid

from configuration import ROOT
from contract_identity import Api
from contract_catalogue import local_sql
from prepare_catalogue import literal


def binary(path, data=None, token=None, method="GET", mime="image/jpeg"):
    headers = {"apikey": config["PUBLISHABLE_KEY"], "Content-Type": mime}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(api.url + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, None


class CoverContract(unittest.TestCase):
    def test_owner_publishes_directly_with_private_storage(self):
        code, empty_state = api.request("/rest/v1/rpc/professional_cover", {"p_profile": profile_id}, tokens["owner"])
        self.assertEqual(code, 200)
        self.assertIsNone(empty_state, "a new owner has no prior cover request")
        request_id = str(uuid.uuid4())
        payload = {"p_profile": profile_id, "p_request": request_id, "p_sha256": hashlib.sha256(photo).hexdigest()}
        for token in (None, tokens["other"]):
            code, _ = api.request("/rest/v1/rpc/request_professional_cover", payload, token)
            self.assertIn(code, (401, 403))
        code, upload = api.request("/rest/v1/rpc/request_professional_cover", payload, tokens["owner"])
        self.assertEqual(code, 200)
        self.assertEqual(upload["status"], "uploading")
        path = upload["object_path"]
        object_paths.append(path)
        self.assertNotIn(users["owner"], path, "public image paths must not reveal Auth identity")
        object_url = "/storage/v1/object/professional-covers/" + path
        self.assertNotIn(binary(object_url, photo, tokens["other"], "POST")[0], (200, 201))
        self.assertNotIn(binary(object_url, b"x" * (2097153), tokens["owner"], "POST")[0], (200, 201))
        self.assertNotIn(binary(object_url, photo, tokens["owner"], "POST", "image/png")[0], (200, 201))
        self.assertIn(binary(object_url, photo, tokens["owner"], "POST")[0], (200, 201))
        self.assertNotIn(binary(object_url, photo, tokens["owner"], "PUT")[0], (200, 201))
        # Before submitting, the upload is readable only by its owner.
        self.assertNotEqual(api.request("/storage/v1/object/sign/professional-covers/" + path, {"expiresIn": 60})[0], 200)
        self.assertNotEqual(api.request("/storage/v1/object/sign/professional-covers/" + path, {"expiresIn": 60}, tokens["other"])[0], 200)
        self.assertEqual(api.request("/storage/v1/object/sign/professional-covers/" + path, {"expiresIn": 60}, tokens["owner"])[0], 200)
        self.assertIn(api.request("/rest/v1/rpc/submit_professional_cover", {"p_request": request_id}, tokens["other"])[0], (401, 403))
        # Submitting publishes at once: no administrative review (David, 2026-11-23).
        code, submitted = api.request("/rest/v1/rpc/submit_professional_cover", {"p_request": request_id}, tokens["owner"])
        self.assertEqual(code, 200)
        self.assertEqual(submitted["status"], "published")
        code, retried = api.request("/rest/v1/rpc/submit_professional_cover", {"p_request": request_id}, tokens["owner"])
        self.assertEqual((code, retried["status"]), (200, "published"))
        self.assertEqual(api.request("/rest/v1/rpc/catalogue", {"p_id": profile_id})[1][0]["cover_path"], path)
        code, state = api.request("/rest/v1/rpc/professional_cover", {"p_profile": profile_id}, tokens["owner"])
        self.assertEqual((code, state["status"]), (200, "published"))
        review = {"p_request": request_id, "p_approve": False, "p_reason": "No review remains"}
        for role in ("owner", "admin"):
            self.assertIn(api.request("/rest/v1/rpc/review_professional_cover", review, tokens[role])[0], (401, 403, 404))
        code, signed = api.request("/storage/v1/object/sign/professional-covers/" + path, {"expiresIn": 60})
        self.assertEqual(code, 200, "a visitor can read the published image")
        code, downloaded = binary("/storage/v1" + signed["signedURL"])
        self.assertEqual(code, 200)
        self.assertEqual(downloaded, photo)
        # Back to the trade image, also at once; the previous photo is no longer public.
        generic = {"p_profile": profile_id, "p_request": str(uuid.uuid4())}
        code, restored = api.request("/rest/v1/rpc/request_professional_cover", generic, tokens["owner"])
        self.assertEqual((code, restored["status"]), (200, "published"))
        self.assertIsNone(api.request("/rest/v1/rpc/catalogue", {"p_id": profile_id})[1][0]["cover_path"])
        self.assertNotEqual(api.request("/storage/v1/object/sign/professional-covers/" + path, {"expiresIn": 60})[0], 200)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("This contract is restricted to loopback")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens, object_paths = {}, {}, []
    profile_id = str(uuid.uuid4())
    photo = (ROOT / "supabase/tests/fixtures/cover.jpg").read_bytes()
    try:
        for role in ("owner", "other", "admin"):
            email, password = f"cover-{role}-{secrets.token_hex(6)}@example.org", secrets.token_urlsafe(32)
            code, user = admin.request("/auth/v1/admin/users", {
                "email": email, "password": password, "email_confirm": True,
                "user_metadata": {"is_administrator": True},
            }, config["SERVICE_ROLE_KEY"])
            if code not in (200, 201): raise SystemExit("Could not create local fixture")
            users[role] = user["id"]
            code, session = api.request("/auth/v1/token?grant_type=password", {"email": email, "password": password})
            if code != 200: raise SystemExit("Could not sign in local fixture")
            tokens[role] = session["access_token"]
        local_sql(f"""insert into private.administrators values ('{users["admin"]}');
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users["owner"]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Cover contract','',array['plumbing'],'{{}}','[]','{{es}}','+34 000 000 099');""")
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(CoverContract))
    finally:
        if object_paths:
            code, _ = admin.request("/storage/v1/object/professional-covers", {"prefixes": object_paths},
                config["SERVICE_ROLE_KEY"], method="DELETE")
            if code not in (200, 204): raise SystemExit("Could not clean this invocation's local images")
        local_sql(f"""begin;
            delete from private.professional_cover_decisions where request_id in
              (select id from private.professional_cover_requests where profile_id='{profile_id}');
            delete from private.professional_cover_requests where profile_id='{profile_id}';
            delete from public.approved_profiles where profile_id='{profile_id}';
            delete from private.professional_profiles where id='{profile_id}';
            delete from private.administrators where user_id={literal(users.get("admin"))}::uuid;
            commit;""" if users.get("admin") else "select 1;")
        for user_id in users.values():
            admin.request("/auth/v1/admin/users/" + user_id, token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result.wasSuccessful() else 1)
