#!/usr/bin/env python3
"""Real Auth/PostgREST notices and push outbox contract (#32). Disposable fixtures on loopback ONLY.

The dispatcher is played by the local service role through the same RPCs the deliver-push Edge Function uses;
no request ever reaches FCM.
"""
import argparse
import json
import os
import secrets
import subprocess
import threading
import unittest
import uuid

from configuration import ROOT
from contract_identity import Api
from contract_chat import sql


class NoticesContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def claim(self):
        code, rows = admin.request("/rest/v1/rpc/claim_push_deliveries", {"p_limit": 50}, service_key)
        self.assertEqual(code, 200)
        return [r for r in rows if r["recipient_id"] in users]

    def test_notices_devices_and_dispatch(self):
        client, pro, other = tokens
        code, conversation = self.rpc("open_conversation", {"p_profile_id": profile_id}, client)
        self.assertEqual(code, 200)
        cid = conversation["id"]
        installation = str(uuid.uuid4())
        code, device = self.rpc("register_push_device",
                                {"p_installation_id": installation, "p_token": token_pro, "p_enabled": True}, pro)
        self.assertEqual((code, device["enabled"]), (200, True))
        for body in ("Grifo roto", "Calle Privada 3"):
            self.assertEqual(self.rpc("send_text", {"p_conversation_id": cid, "p_request_id": str(uuid.uuid4()),
                                                    "p_body": body}, client)[0], 200)

        # The recipient reads one grouped, unread notice without the body; others read nothing.
        code, notices = self.rpc("my_notices", {}, pro)
        self.assertEqual(code, 200)
        [notice] = notices
        self.assertEqual((notice["kind"], notice["count"], notice["read"]), ("message", 2, False))
        self.assertNotIn("Privada", json.dumps(notices))
        self.assertEqual(self.rpc("my_notices", {}, client), (200, []))
        self.assertEqual(self.rpc("my_notices", {}, other), (200, []))
        self.assertIn(self.rpc("my_notices", {}, None)[0], (401, 403))

        # Accounts cannot dispatch nor read the outbox; two dispatchers never get the same delivery.
        self.assertIn(self.rpc("claim_push_deliveries", {"p_limit": 5}, pro)[0], (401, 403, 404))
        for path in ("/rest/v1/push_devices", "/rest/v1/push_deliveries", "/rest/v1/notices"):
            self.assertIn(api.request(path, token=pro, method="GET")[0], (401, 403, 404))
        results = []
        threads = [threading.Thread(target=lambda: results.append(self.claim())) for _ in range(2)]
        [t.start() for t in threads]
        [t.join() for t in threads]
        claimed = [row for batch in results for row in batch]
        self.assertEqual(len(claimed), 2, "one delivery per message, each handed out once")
        self.assertEqual({r["delivery_id"] for r in claimed}.__len__(), 2)
        self.assertTrue(all(r["token"] == token_pro and r["recipient_id"] == users[1] for r in claimed))
        self.assertNotIn("Grifo", json.dumps(claimed))
        first, second = claimed
        for row, outcome in ((first, "sent"), (second, "invalid_token")):
            code, _ = admin.request("/rest/v1/rpc/report_push_delivery",
                                    {"p_delivery_id": row["delivery_id"], "p_outcome": outcome, "p_error": None},
                                    service_key)
            self.assertIn(code, (200, 204))

        # The invalid token stops pushes but not the in-app notices; a new token resumes them.
        self.rpc("send_text", {"p_conversation_id": cid, "p_request_id": str(uuid.uuid4()), "p_body": "¿Vienes?"},
                 client)
        self.assertEqual(self.claim(), [])
        self.assertEqual(self.rpc("my_notices", {}, pro)[1][0]["count"], 3)
        self.rpc("register_push_device", {"p_installation_id": installation, "p_token": token_pro + "-2",
                                          "p_enabled": True}, pro)
        self.rpc("send_text", {"p_conversation_id": cid, "p_request_id": str(uuid.uuid4()), "p_body": "Hola"}, client)
        [resumed] = self.claim()
        self.assertEqual(resumed["token"], token_pro + "-2")

        # Reading the conversation clears the notice, and the state persists for a new session.
        self.assertIn(self.rpc("mark_conversation_read", {"p_conversation_id": cid}, pro)[0], (200, 204))
        self.assertTrue(self.rpc("my_notices", {}, pro)[1][0]["read"])
        self.assertIn(self.rpc("unregister_push_device", {"p_installation_id": installation}, pro)[0], (200, 204))
        self.rpc("send_text", {"p_conversation_id": cid, "p_request_id": str(uuid.uuid4()), "p_body": "Más"}, client)
        self.assertEqual(self.claim(), [], "after signing out the device receives nothing")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Notice fixtures require loopback; remote writes are not supported")
    service_key = config["SERVICE_ROLE_KEY"]
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], service_key)
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    token_pro = f"contract-token-{secrets.token_hex(8)}"
    result = None
    try:
        for role in ("client", "professional", "other"):
            credentials = {"email": f"notices-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users", dict(credentials, email_confirm=True), service_key)
            if code not in (200, 201):
                raise SystemExit("Could not create local notices fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local notices fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Notices contract','Fixture',array['plumbing'],array['Repair'],'[]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(NoticesContract))
    finally:
        # Only this invocation's random fixtures; never prepared data.
        ids = ",".join(f"'{u}'" for u in users) or "null"
        sql(f"""begin;
            delete from private.push_deliveries where recipient_id in ({ids});
            delete from private.push_devices where user_id in ({ids});
            delete from private.notices where recipient_id in ({ids});
            delete from public.conversation_reads where conversation_id in
              (select id from public.conversations where profile_id = '{profile_id}');
            delete from public.chat_messages where conversation_id in
              (select id from public.conversations where profile_id = '{profile_id}');
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=service_key, method="DELETE")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
