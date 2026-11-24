#!/usr/bin/env python3
"""Real Auth/PostgREST inbox and unread contract (#39). Disposable fixtures on loopback ONLY."""
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


class ChatUnreadContract(unittest.TestCase):
    def send(self, cid, body, token):
        code, _ = api.request("/rest/v1/rpc/send_text", {"p_conversation_id": cid,
                              "p_request_id": str(uuid.uuid4()), "p_body": body}, token)
        self.assertEqual(code, 200)

    def inbox(self, token):
        code, rows = api.request("/rest/v1/rpc/conversation_inbox", {}, token)
        self.assertEqual(code, 200)
        return rows

    def test_inbox_names_unread_counts_and_read_permissions(self):
        code, conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, tokens[0])
        self.assertEqual(code, 200)
        cid = conversation["id"]
        self.send(cid, "Hola", tokens[0])
        self.send(cid, "Se sale el agua", tokens[0])
        [row] = self.inbox(tokens[1])
        self.assertEqual((row["id"], row["client_name"], row["unread_count"]), (cid, "Lucía Contrato", 2))
        self.assertNotIn(emails[0], json.dumps(row))
        code, _ = api.request("/rest/v1/rpc/mark_conversation_read", {"p_conversation_id": cid}, tokens[1])
        self.assertIn(code, (200, 204))
        self.assertEqual(self.inbox(tokens[1])[0]["unread_count"], 0)
        [own] = self.inbox(tokens[0])
        self.assertEqual((own["name"], own["unread_count"]), ("Unread contract", 0))
        self.send(cid, "Voy mañana", tokens[1])
        self.assertEqual(self.inbox(tokens[0])[0]["unread_count"], 1)
        self.assertEqual(self.inbox(tokens[2]), [])
        for token in (None, tokens[2]):
            self.assertIn(api.request("/rest/v1/rpc/mark_conversation_read",
                                      {"p_conversation_id": cid}, token)[0], (401, 403))
        code, rows = api.request("/rest/v1/conversation_reads?select=*", token=tokens[0], method="GET")
        self.assertEqual((code, rows), (200, []))
        self.assertIn(api.request("/rest/v1/conversation_reads", {"conversation_id": cid, "user_id": users[0],
                      "last_read_at": "2999-01-01T00:00:00Z"}, tokens[0])[0], (401, 403))
        self.assertEqual(self.inbox(tokens[0])[0]["unread_count"], 1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Unread fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens, emails = [], [], []
    profile_id = str(uuid.uuid4())
    result = None
    try:
        for role, metadata in (("client", {"full_name": "Lucía Contrato"}), ("professional", {}), ("other", {})):
            credentials = {"email": f"unread-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                      dict(credentials, email_confirm=True, user_metadata=metadata),
                                      config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local unread fixture")
            users.append(user["id"])
            emails.append(credentials["email"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local unread fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Unread contract','Fixture',array['plumbing'],array['Repair'],'[]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ChatUnreadContract))
    finally:
        # Only this invocation's random fixture UUIDs; never prepared data.
        sql(f"""begin;
            delete from public.conversation_reads where conversation_id in
              (select id from public.conversations where profile_id = '{profile_id}');
            delete from public.chat_messages where conversation_id in
              (select id from public.conversations where profile_id = '{profile_id}');
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
