#!/usr/bin/env python3
"""Real Auth/PostgREST chat contract. Disposable fixtures on loopback ONLY."""
import argparse
import json
import os
import secrets
import subprocess
import unittest
import uuid

from configuration import ROOT
from contract_identity import Api


def sql(statement):
    subprocess.run(
        ["docker", "exec", "-i", "supabase_db_teloarreglo", "psql", "-U", "postgres",
         "-d", "postgres", "-X", "-q", "-v", "ON_ERROR_STOP=1"],
        input=statement, text=True, check=True, capture_output=True,
    )


class ChatContract(unittest.TestCase):
    def test_contact_reply_reconnect_retry_and_direct_permissions(self):
        profile = {"p_profile_id": profile_id}
        self.assertIn(api.request("/rest/v1/rpc/open_conversation", profile)[0], (401, 403))
        code, conversation = api.request("/rest/v1/rpc/open_conversation", profile, tokens[0])
        self.assertEqual(code, 200)
        self.assertEqual(conversation["professional_id"], users[1])
        self.assertNotIn("address", json.dumps(conversation))
        cid = conversation["id"]
        code, reopened = api.request("/rest/v1/rpc/open_conversation", profile, tokens[0])
        self.assertEqual((code, reopened["id"]), (200, cid))
        request = {"p_conversation_id": cid, "p_request_id": str(uuid.uuid4()), "p_body": "Hola, tengo una fuga"}
        code, first = api.request("/rest/v1/rpc/send_text", request, tokens[0])
        self.assertEqual(code, 200)
        code, retry = api.request("/rest/v1/rpc/send_text", request, tokens[0])
        self.assertEqual((code, retry["id"]), (200, first["id"]))
        self.assertEqual(first["author_id"], users[0])
        self.assertTrue(first["created_at"])
        code, inbox = api.request("/rest/v1/conversations?select=*", token=tokens[1], method="GET")
        self.assertEqual(code, 200)
        self.assertIn(cid, [item["id"] for item in inbox])
        reply = {"p_conversation_id": cid, "p_request_id": str(uuid.uuid4()), "p_body": "Bonjour"}
        code, response = api.request("/rest/v1/rpc/send_text", reply, tokens[1])
        self.assertEqual(code, 200)
        self.assertEqual(response["author_id"], users[1])
        # Recover a new session, not an in-memory message list.
        code, recovered = api.request("/auth/v1/token?grant_type=refresh_token",
                                     {"refresh_token": refresh_tokens[0]})
        self.assertEqual(code, 200)
        path = f"/rest/v1/chat_messages?conversation_id=eq.{cid}&order=created_at.asc,id.asc"
        code, history = api.request(path, token=recovered["access_token"], method="GET")
        self.assertEqual(code, 200)
        self.assertEqual([item["body"] for item in history], ["Hola, tengo una fuga", "Bonjour"])
        for token in (None, tokens[2]):
            code, rows = api.request(path, token=token, method="GET")
            self.assertTrue(code in (401, 403) or (code == 200 and rows == []))
            self.assertIn(api.request("/rest/v1/rpc/send_text", reply, token)[0], (401, 403))
        for method, body in (("PATCH", {"body": "Edited"}), ("DELETE", None),
                             ("POST", {"conversation_id": cid, "author_id": users[1], "body": "Forged"})):
            self.assertIn(api.request(f"/rest/v1/chat_messages?id=eq.{first['id']}",
                                     body, tokens[0], method)[0], (401, 403))
        self.assertIn(api.request("/rest/v1/conversations", conversation, tokens[2])[0], (401, 403))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Chat fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens, refresh_tokens = [], [], []
    profile_id = str(uuid.uuid4())
    try:
        for role in ("client", "professional", "administrator"):
            credentials = {"email": f"chat-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                      dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local chat fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local chat fixture")
            tokens.append(session["access_token"])
            refresh_tokens.append(session["refresh_token"])
        sql(f"""begin;
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Chat contract','Fixture',array['plumbing'],array['Repair'],'[]',array['es'],'000');
            insert into private.administrators(user_id) values ('{users[2]}');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ChatContract))
    finally:
        # Only this invocation's random fixture UUIDs; never prepared data.
        sql(f"""begin;
            delete from public.chat_messages where conversation_id in
              (select id from public.conversations where profile_id = '{profile_id}');
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result.wasSuccessful() else 1)
