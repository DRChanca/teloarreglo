#!/usr/bin/env python3
"""Real Auth + RPC contract, using a verified test account. Never logs tokens."""
import argparse
import json
import os
import secrets
import subprocess
import unittest
import urllib.error
import urllib.request

from configuration import ROOT, configuration


class Api:
    def __init__(self, url, key):
        self.url, self.key = url.rstrip("/"), key

    def request(self, path, body=None, token=None, method="POST"):
        headers = {"apikey": self.key, "Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(
            self.url + path, data=json.dumps(body).encode() if body is not None else None,
            headers=headers, method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            return error.code, None  # Do not include bodies or credentials in failure output.


class IdentityContract(unittest.TestCase):
    def test_real_auth_and_protected_account(self):
        code, _ = api.request("/rest/v1/rpc/current_account", {})
        self.assertIn(code, (401, 403), "anonymous access must be denied")
        code, _ = api.request(
            "/auth/v1/token?grant_type=password",
            {"email": email, "password": secrets.token_urlsafe(32)},
        )
        self.assertEqual(code, 400, "incorrect credentials must not succeed")
        code, session = api.request(
            "/auth/v1/token?grant_type=password", {"email": email, "password": password},
        )
        self.assertEqual(code, 200, "verified test account must sign in")
        self.addCleanup(lambda: api.request("/auth/v1/logout?scope=local", token=session["access_token"]))
        code, account = api.request("/rest/v1/rpc/current_account", {}, session["access_token"])
        self.assertEqual(code, 200, "authenticated account RPC must succeed")
        self.assertEqual(account["user_id"], session["user"]["id"])
        self.assertTrue(session["user"]["email_confirmed_at"], "account must be verified")
        code, _ = api.request("/rest/v1/rpc/current_account", {"user_id": secrets.token_hex(16)}, session["access_token"])
        self.assertNotEqual(code, 200, "caller cannot choose another account")
        code, refreshed = api.request(
            "/auth/v1/token?grant_type=refresh_token", {"refresh_token": session["refresh_token"]},
        )
        self.assertEqual(code, 200, "saved refresh token must recover a session")
        session.update(refreshed)
        code, _ = api.request("/rest/v1/rpc/current_account", {}, session["access_token"])
        self.assertEqual(code, 200, "refreshed session must access protected RPC")
        code, _ = api.request("/auth/v1/logout?scope=local", token=session["access_token"])
        self.assertIn(code, (200, 204), "logout must be acknowledged by Auth")
        code, _ = api.request(
            "/auth/v1/token?grant_type=refresh_token", {"refresh_token": session["refresh_token"]},
        )
        self.assertNotEqual(code, 200, "logged-out session must not refresh")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--environment", choices=["pruebas", "presentacion"], default="pruebas")
    parser.add_argument("--local", action="store_true", help="create a disposable account in the local stack only")
    args = parser.parse_args()
    created_id = None
    admin = None
    if args.local:
        result = subprocess.check_output(
            [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True,
        )
        config = json.loads(result)
        # This path is deliberately restricted to the local CLI stack.
        url = config["API_URL"]
        if url not in ("http://127.0.0.1:54321", "http://localhost:54321"):
            raise SystemExit("Local contract requires the loopback Supabase stack")
        api = Api(url, config["PUBLISHABLE_KEY"])
        admin = Api(url, config["SERVICE_ROLE_KEY"])
        email, password = f"identity-{secrets.token_hex(6)}@example.org", secrets.token_urlsafe(32)
        status, created = admin.request(
            "/auth/v1/admin/users",
            {"email": email, "password": password, "email_confirm": True},
            config["SERVICE_ROLE_KEY"],
        )
        if status not in (200, 201):
            raise SystemExit(f"Could not create local verified fixture (HTTP {status})")
        created_id = created["id"]
    else:
        config = configuration()
        prefix = "TEST" if args.environment == "pruebas" else "PRESENTATION"
        required = [f"{prefix}_{suffix}" for suffix in ("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "TEST_EMAIL", "TEST_PASSWORD")]
        if any(not config.get(key) for key in required):
            raise SystemExit("Configuration missing; run bash scripts/setup-supabase.sh")
        api = Api(config[required[0]], config[required[1]])
        email, password = config[required[2]], config[required[3]]
    try:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(IdentityContract))
    finally:
        if created_id:
            admin.request(f"/auth/v1/admin/users/{created_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result.wasSuccessful() else 1)
