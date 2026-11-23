#!/usr/bin/env python3
"""Real Auth + PostgREST + Storage contract for chat photos (#18). Loopback stack ONLY."""
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

BUCKET = "/storage/v1/object/chat-attachments/"


def binary(path, data=None, token=None, method="GET", mime="image/jpeg"):
    headers = {"apikey": config["PUBLISHABLE_KEY"], "Content-Type": mime}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(api.url + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()  # Storage error bodies carry no credentials.


class ChatPhotoContract(unittest.TestCase):
    def test_participants_exchange_private_photo_and_outsiders_cannot_read_it(self):
        client, professional, outsider = tokens["client"], tokens["professional"], tokens["outsider"]
        code, conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, client)
        self.assertEqual(code, 200)
        conversation_id = conversation["id"]
        request_id = str(uuid.uuid4())
        reserve = {"p_conversation_id": conversation_id, "p_request_id": request_id, "p_kind": "photo",
                   "p_mime": "image/jpeg", "p_sha256": hashlib.sha256(photo).hexdigest()}
        for token in (None, outsider):
            self.assertIn(api.request("/rest/v1/rpc/request_chat_attachment", reserve, token)[0], (401, 403))
        code, upload = api.request("/rest/v1/rpc/request_chat_attachment", reserve, client)
        self.assertEqual(code, 200)
        self.assertEqual(upload["status"], "uploading")
        path = upload["object_path"]
        object_paths.append(path)
        self.assertNotIn(users["client"], path, "object paths must not reveal Auth identity")
        self.assertNotIn(binary(BUCKET + path, photo, outsider, "POST")[0], (200, 201), "outsider cannot upload")
        self.assertNotIn(binary(BUCKET + path, photo, professional, "POST")[0], (200, 201), "peer cannot upload")
        code, body = binary(BUCKET + path, b"x" * 2097153, client, "POST")
        self.assertNotIn(code, (200, 201), "Storage enforces 2 MiB")
        print(f"\n  oversize upload -> HTTP {code} {body[:160]!r}")
        code, body = binary(BUCKET + path, photo, client, "POST", "image/gif")
        self.assertNotIn(code, (200, 201), "Storage enforces JPEG/PNG")
        print(f"  unsupported type -> HTTP {code} {body[:160]!r}")
        self.assertEqual(api.request("/rest/v1/rpc/submit_chat_attachment",
            {"p_conversation_id": conversation_id, "p_request_id": request_id}, client)[0], 400,
            "no message before the file is stored")
        self.assertIn(binary(BUCKET + path, photo, client, "POST")[0], (200, 201))
        code, body = binary(BUCKET + path, photo, client, "POST")
        self.assertNotIn(code, (200, 201), "uploads are immutable")
        print(f"  duplicate upload -> HTTP {code} {body[:160]!r}")
        self.assertNotIn(binary(BUCKET + path, photo, client, "PUT")[0], (200, 201), "no overwrite")
        sign = "/storage/v1/object/sign/chat-attachments/" + path
        self.assertNotEqual(api.request(sign, {"expiresIn": 60}, professional)[0], 200,
            "an unconfirmed upload is not shared")
        submit = {"p_conversation_id": conversation_id, "p_request_id": request_id}
        code, message = api.request("/rest/v1/rpc/submit_chat_attachment", submit, client)
        self.assertEqual(code, 200)
        self.assertEqual(message["attachment_kind"], "photo")
        self.assertEqual(message["attachment_size"], len(photo))
        self.assertEqual(api.request("/rest/v1/rpc/submit_chat_attachment", submit, client)[1]["id"], message["id"])
        code, again = api.request("/rest/v1/rpc/request_chat_attachment", reserve, client)
        self.assertEqual((code, again["status"], again["message"]["id"]), (200, "sent", message["id"]))
        self.assertNotIn(binary(BUCKET + path, photo, client, "POST")[0], (200, 201), "sent file stays immutable")
        code, history = api.request(
            f"/rest/v1/chat_messages?conversation_id=eq.{conversation_id}&select=id,attachment_path",
            token=professional, method="GET")
        self.assertEqual((code, history), (200, [{"id": message["id"], "attachment_path": path}]))
        code, signed = api.request(sign, {"expiresIn": 60}, professional)
        self.assertEqual(code, 200, "the other participant can open the photo")
        code, downloaded = binary("/storage/v1" + signed["signedURL"])
        self.assertEqual((code, downloaded), (200, photo))
        for token in (None, outsider, tokens["administrator"]):
            self.assertNotEqual(api.request(sign, {"expiresIn": 60}, token)[0], 200, "no third-party link")
            self.assertNotEqual(binary(BUCKET + path, token=token)[0], 200, "no third-party download")
        self.assertNotEqual(binary("/storage/v1/object/public/chat-attachments/" + path)[0], 200,
            "no permanent public link")


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
    result = None
    try:
        for role in ("client", "professional", "outsider", "administrator"):
            credentials = {"email": f"photo-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users", dict(credentials, email_confirm=True),
                                       config["SERVICE_ROLE_KEY"])
            if code not in (200, 201): raise SystemExit("Could not create local fixture")
            users[role] = user["id"]
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200: raise SystemExit("Could not sign in local fixture")
            tokens[role] = session["access_token"]
        local_sql(f"""begin;
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users["professional"]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Photo contract','Fixture',array['plumbing'],array['Repair'],'[]',array['es'],'000');
            insert into private.administrators(user_id) values ('{users["administrator"]}');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ChatPhotoContract))
    finally:
        # Only this invocation's random fixtures; never prepared data.
        if object_paths:
            admin.request("/storage/v1/object/chat-attachments", {"prefixes": object_paths},
                          config["SERVICE_ROLE_KEY"], method="DELETE")
        local_sql(f"""begin;
            delete from public.chat_messages where conversation_id in
              (select id from public.conversations where profile_id = '{profile_id}');
            delete from private.chat_uploads where conversation_id in
              (select id from public.conversations where profile_id = '{profile_id}');
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            delete from private.administrators where user_id::text = '{users.get("administrator", "")}';
            commit;""")
        for user_id in users.values():
            admin.request("/auth/v1/admin/users/" + user_id, token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
