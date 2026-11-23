#!/usr/bin/env python3
"""Real Auth + PostgREST + Storage contract for chat videos (#19). Loopback stack ONLY.

Reuses the #18 attachment boundary: same bucket, RPCs and policies.
"""
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


def binary(path, data=None, token=None, method="GET", mime="video/mp4"):
    headers = {"apikey": config["PUBLISHABLE_KEY"], "Content-Type": mime}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(api.url + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()  # Storage error bodies carry no credentials.


def reserve(conversation_id, request_id, kind, mime, data):
    return {"p_conversation_id": conversation_id, "p_request_id": request_id, "p_kind": kind,
            "p_mime": mime, "p_sha256": hashlib.sha256(data).hexdigest()}


class ChatVideoContract(unittest.TestCase):
    def conversation(self):
        code, conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id},
                                         tokens["client"])
        self.assertEqual(code, 200)
        return conversation["id"]

    def test_client_sends_a_real_mp4_before_any_reply_and_only_the_professional_can_play_it(self):
        client, professional, outsider = tokens["client"], tokens["professional"], tokens["outsider"]
        conversation_id = self.conversation()
        request_id = str(uuid.uuid4())
        body = reserve(conversation_id, request_id, "video", "video/mp4", video)
        for token in (None, outsider):
            self.assertIn(api.request("/rest/v1/rpc/request_chat_attachment", body, token)[0], (401, 403))
        code, upload = api.request("/rest/v1/rpc/request_chat_attachment", body, client)
        self.assertEqual((code, upload["status"]), (200, "uploading"))
        path = upload["object_path"]
        object_paths.append(path)
        self.assertTrue(path.endswith(".mp4"))
        self.assertNotIn(users["client"], path, "object paths must not reveal Auth identity")
        self.assertNotIn(binary(BUCKET + path, video, outsider, "POST")[0], (200, 201), "outsider cannot upload")
        code, reply = binary(BUCKET + path, b"x" * 20971521, client, "POST")
        self.assertNotIn(code, (200, 201), "Storage enforces 20 MiB")
        print(f"\n  oversize video -> HTTP {code} {reply[:160]!r}")
        code, reply = binary(BUCKET + path, video, client, "POST", "video/quicktime")
        self.assertNotIn(code, (200, 201), "Storage enforces MP4")
        print(f"  unsupported video type -> HTTP {code} {reply[:160]!r}")
        sign = "/storage/v1/object/sign/chat-attachments/" + path
        self.assertIn(binary(BUCKET + path, video, client, "POST")[0], (200, 201))
        self.assertNotEqual(api.request(sign, {"expiresIn": 60}, professional)[0], 200,
                            "an unconfirmed upload is not shared")
        submit = {"p_conversation_id": conversation_id, "p_request_id": request_id}
        code, message = api.request("/rest/v1/rpc/submit_chat_attachment", submit, client)
        self.assertEqual(code, 200)
        self.assertEqual((message["attachment_kind"], message["attachment_type"], message["attachment_size"]),
                         ("video", "video/mp4", len(video)))
        self.assertEqual(api.request("/rest/v1/rpc/submit_chat_attachment", submit, client)[1]["id"], message["id"])
        code, again = api.request("/rest/v1/rpc/request_chat_attachment", body, client)
        self.assertEqual((code, again["status"], again["message"]["id"]), (200, "sent", message["id"]))
        code, history = api.request(
            f"/rest/v1/chat_messages?conversation_id=eq.{conversation_id}&request_id=eq.{request_id}"
            "&select=id,attachment_kind",
            token=professional, method="GET")
        self.assertEqual((code, history), (200, [{"id": message["id"], "attachment_kind": "video"}]),
                         "the professional sees it without having replied, exactly once")
        code, signed = api.request(sign, {"expiresIn": 600}, professional)
        self.assertEqual(code, 200, "the other participant can open the video")
        code, downloaded = binary("/storage/v1" + signed["signedURL"])
        self.assertEqual((code, downloaded), (200, video), "the exact compressed bytes are played back")
        for token in (None, outsider, tokens["administrator"]):
            self.assertNotEqual(api.request(sign, {"expiresIn": 60}, token)[0], 200, "no third-party link")
            self.assertNotEqual(binary(BUCKET + path, token=token)[0], 200, "no third-party download")
        self.assertNotEqual(binary("/storage/v1/object/public/chat-attachments/" + path)[0], 200,
                            "no permanent public link")

    def test_a_video_near_the_limit_fits_but_a_large_file_cannot_become_a_photo(self):
        client = tokens["client"]
        conversation_id = self.conversation()
        large = b"\x00\x00\x00\x18ftypmp42" + secrets.token_bytes(5 * 1024 * 1024)
        request_id = str(uuid.uuid4())
        code, upload = api.request("/rest/v1/rpc/request_chat_attachment",
                                   reserve(conversation_id, request_id, "video", "video/mp4", large), client)
        self.assertEqual(code, 200)
        object_paths.append(upload["object_path"])
        self.assertIn(binary(BUCKET + upload["object_path"], large, client, "POST")[0], (200, 201),
                      "a 5 MiB video fits the widened bucket")
        code, message = api.request("/rest/v1/rpc/submit_chat_attachment",
                                    {"p_conversation_id": conversation_id, "p_request_id": request_id}, client)
        self.assertEqual((code, message["attachment_size"]), (200, len(large)))
        photo_id = str(uuid.uuid4())
        code, upload = api.request("/rest/v1/rpc/request_chat_attachment",
                                   reserve(conversation_id, photo_id, "photo", "image/jpeg", large), client)
        self.assertEqual(code, 200)
        object_paths.append(upload["object_path"])
        self.assertIn(binary(BUCKET + upload["object_path"], large, client, "POST", "image/jpeg")[0], (200, 201))
        code, _ = api.request("/rest/v1/rpc/submit_chat_attachment",
                              {"p_conversation_id": conversation_id, "p_request_id": photo_id}, client)
        self.assertEqual(code, 400, "photos keep their 2 MiB limit (attachment_too_large)")
        code, sent = api.request(
            f"/rest/v1/chat_messages?conversation_id=eq.{conversation_id}&request_id=eq.{photo_id}&select=id",
            token=client, method="GET")
        self.assertEqual((code, sent), (200, []), "the oversized photo never becomes a message")


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
    video = (ROOT / "supabase/tests/fixtures/video-1080p.mp4").read_bytes()
    result = None
    try:
        for role in ("client", "professional", "outsider", "administrator"):
            credentials = {"email": f"video-{role}-{secrets.token_hex(6)}@example.org",
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
            values ('{profile_id}','Video contract','Fixture',array['plumbing'],array['Repair'],'[]',array['es'],'000');
            insert into private.administrators(user_id) values ('{users["administrator"]}');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ChatVideoContract))
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
            delete from public.conversation_reads where conversation_id in
              (select id from public.conversations where profile_id = '{profile_id}');
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            delete from private.administrators where user_id::text = '{users.get("administrator", "")}';
            commit;""")
        for user_id in users.values():
            admin.request("/auth/v1/admin/users/" + user_id, token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result and result.wasSuccessful() else 1)
