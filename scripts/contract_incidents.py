#!/usr/bin/env python3
"""Real Auth/PostgREST/Storage contract for incidents and private evidence (#26): an incident opened after the start
keeps the stage and the simulated balance and blocks the delivery; an incident and a confirmation sent at the same
instant end in one coherent result; both participants contribute concrete messages and photos after the visibility
notice and read them; the administrator reads only the contributed evidence and file, never the chat; third and
anonymous accounts learn nothing. Disposable fixtures on loopback ONLY; money is simulated."""
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
from contract_chat import sql
from contract_agenda import simultaneously

CONCEJO = "33926"  # Synthetic code, never a real concejo.
DAY = "2030-01-15"  # Tuesday; Madrid is UTC+1 then, so the window 08:00–20:00 local is 07:00–19:00Z.
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
        return error.code, b""


class IncidentContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def started(self, hour, mode="integrated", amount=8000):
        start, end = f"{DAY}T{hour:02d}:00:00Z", f"{DAY}T{hour + 1:02d}:00:00Z"
        code, appointment = self.rpc("request_appointment", {
            "p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()), "p_problem": "Fuga",
            "p_start": start, "p_end": end, "p_address": "Calle Ficticia 1", "p_lat": 43.05, "p_lng": -5.95}, client)
        self.assertEqual(code, 200, appointment)
        code, proposal = self.rpc("propose_appointment", {
            "p_appointment_id": appointment["id"], "p_expected_version": 1, "p_request_id": str(uuid.uuid4()),
            "p_scope": "Cambiar sifón", "p_start": start, "p_duration_minutes": 60, "p_amount_cents": amount,
            "p_visit_fee_cents": None, "p_materials_included": True, "p_travel_included": True}, professional)
        self.assertEqual(code, 200, proposal)
        if amount:
            code, body = self.rpc("confirm_proposal", {"p_proposal_id": proposal["id"], "p_request_id": str(uuid.uuid4()),
                                                       "p_mode": mode}, client)
        else:
            code, body = self.rpc("accept_proposal", {"p_proposal_id": proposal["id"]}, client)
        self.assertEqual(code, 200, body)
        self.assertEqual(self.rpc("start_work", {"p_appointment_id": appointment["id"]}, professional)[0], 200)
        return appointment["id"]

    def get(self, path, token):
        code, rows = api.request("/rest/v1/" + path, token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows

    def open(self, appointment_id, token, kind="cancellation", text="Ya no puedo seguir", acknowledged=True, key=None):
        return self.rpc("open_incident", {"p_appointment_id": appointment_id, "p_request_id": key or str(uuid.uuid4()),
                                          "p_kind": kind, "p_description": text,
                                          "p_visibility_acknowledged": acknowledged}, token)

    def contribute(self, incident_id, token, message_id=None, note=None, acknowledged=True):
        return self.rpc("add_incident_evidence", {"p_incident_id": incident_id, "p_request_id": str(uuid.uuid4()),
                                                  "p_message_id": message_id, "p_note": note,
                                                  "p_visibility_acknowledged": acknowledged}, token)

    def photo_message(self, token):
        request_id = str(uuid.uuid4())
        reserve = {"p_conversation_id": conversation, "p_request_id": request_id, "p_kind": "photo",
                   "p_mime": "image/jpeg", "p_sha256": hashlib.sha256(photo).hexdigest()}
        code, upload = self.rpc("request_chat_attachment", reserve, token)
        self.assertEqual(code, 200)
        object_paths.append(upload["object_path"])
        self.assertIn(binary(BUCKET + upload["object_path"], photo, token, "POST")[0], (200, 201))
        code, message = self.rpc("submit_chat_attachment", {"p_conversation_id": conversation, "p_request_id": request_id},
                                 token)
        self.assertEqual(code, 200)
        return message

    def test_cancellation_after_start_with_private_evidence(self):
        appt = self.started(7)
        # Without the visibility notice nothing is opened; third and anonymous accounts cannot open.
        self.assertEqual(self.open(appt, client, acknowledged=False)[0], 400)
        for token in (None, outsider, administrator):
            self.assertIn(self.open(appt, token)[0], (401, 403))

        # Both participants at the same instant: one incident, the other is told one is open.
        opened = simultaneously(lambda: self.open(appt, client), lambda: self.open(appt, professional, "not_performed"))
        self.assertEqual(sorted(code for code, _ in opened), [200, 409], opened)
        incident = next(body for code, body in opened if code == 200)
        self.assertEqual((incident["status"], incident["appointment_stage"], incident["blocks_delivery"]),
                         ("open", "started", True))
        for token in (client, professional):
            row = self.get(f"appointments?select=stage,appointment_payments(pending_cents,refunded_cents)&id=eq.{appt}", token)[0]
            self.assertEqual((row["stage"], row["appointment_payments"]["pending_cents"],
                              row["appointment_payments"]["refunded_cents"]), ("started", 8000, 0),
                             "no automatic refund or delivery")
            self.assertEqual(len(self.get(f"incidents?appointment_id=eq.{appt}", token)), 1)

        # Evidence: a text and a photo contributed; another text and another photo stay private to the chat.
        code, shared_text = self.rpc("send_text", {"p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()),
                                                   "p_body": "Me fui a las 10"}, professional)
        self.assertEqual(code, 200)
        self.assertEqual(self.rpc("send_text", {"p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()),
                                                "p_body": "Mensaje que no se aporta"}, client)[0], 200)
        shared_photo, kept_photo = self.photo_message(client), self.photo_message(client)
        self.assertEqual(self.contribute(incident["id"], client, shared_photo["id"], acknowledged=False)[0], 400)
        self.assertEqual(self.contribute(incident["id"], client, shared_photo["id"])[0], 200)
        self.assertEqual(self.contribute(incident["id"], professional, shared_text["id"])[0], 200)
        self.assertEqual(self.contribute(incident["id"], professional, note="Adjunto la hora de salida")[0], 200)
        self.assertIn(self.contribute(incident["id"], outsider, note="Intruso")[0], (401, 403))
        self.assertIn(self.contribute(incident["id"], administrator, note="Revisión")[0], (401, 403))
        for token in (client, professional):
            evidence = self.get(f"incident_evidence?incident_id=eq.{incident['id']}&order=created_at", token)
            self.assertEqual([e["kind"] for e in evidence], ["message", "message", "note"])
            # What the app reads: the incidents embedded in the appointment list.
            row = self.get(f"appointments?select=id,incidents(*,incident_evidence(*))&id=eq.{appt}", token)[0]
            self.assertEqual(len(row["incidents"][0]["incident_evidence"]), 3)
        # Append-only through the API too.
        for token in (client, professional):
            self.assertIn(api.request(f"/rest/v1/incident_evidence?incident_id=eq.{incident['id']}",
                                      {"note": "Cambiada"}, token, "PATCH")[0], (401, 403))

        # The administrator reads the incident, its evidence and only the contributed file; never the chat.
        self.assertEqual(len(self.get(f"incidents?id=eq.{incident['id']}", administrator)), 1)
        self.assertEqual(len(self.get(f"incident_evidence?incident_id=eq.{incident['id']}", administrator)), 3)
        self.assertEqual(self.get(f"chat_messages?conversation_id=eq.{conversation}", administrator), [])
        sign = "/storage/v1/object/sign/chat-attachments/"
        code, signed = api.request(sign + shared_photo["attachment_path"], {"expiresIn": 60}, administrator)
        self.assertEqual(code, 200)
        self.assertEqual(binary("/storage/v1" + signed["signedURL"]), (200, photo))
        self.assertNotEqual(api.request(sign + kept_photo["attachment_path"], {"expiresIn": 60}, administrator)[0], 200,
                            "a file not contributed stays private")
        # Third accounts: nothing.
        self.assertEqual(self.get(f"incidents?id=eq.{incident['id']}", outsider), [])
        self.assertEqual(self.get(f"incident_evidence?incident_id=eq.{incident['id']}", outsider), [])
        self.assertNotEqual(api.request(sign + shared_photo["attachment_path"], {"expiresIn": 60}, outsider)[0], 200)
        self.assertIn(api.request("/rest/v1/incidents", token=None, method="GET")[0], (401, 403))

        # The blocking incident stops the delivery once the job is marked done.
        self.assertEqual(self.rpc("mark_done", {"p_appointment_id": appt}, professional)[0], 200)
        self.assertEqual(self.rpc("confirm_done", {"p_appointment_id": appt}, client)[0], 409)
        row = self.get(f"appointment_payments?appointment_id=eq.{appt}", client)[0]
        self.assertEqual((row["pending_cents"], row["delivered_cents"]), (8000, 0))

    def test_incident_and_confirmation_at_the_same_instant(self):
        for hour in (9, 11, 13):
            appt = self.started(hour)
            self.assertEqual(self.rpc("mark_done", {"p_appointment_id": appt}, professional)[0], 200)
            (incident_code, incident), (confirm_code, _) = simultaneously(
                lambda: self.open(appt, client, "quality", "Gotea"),
                lambda: self.rpc("confirm_done", {"p_appointment_id": appt}, client))
            self.assertEqual(incident_code, 200)
            payment = self.get(f"appointment_payments?appointment_id=eq.{appt}", client)[0]
            if confirm_code == 200:
                # The confirmation came first: the incident is a communication after the close and blocks nothing.
                self.assertEqual((incident["appointment_stage"], incident["blocks_delivery"]), ("completed", False))
                self.assertEqual((payment["pending_cents"], payment["delivered_cents"]), (0, 8000))
            else:
                # The incident came first: it was already confirmed, so the confirmation cannot ignore it.
                self.assertEqual(confirm_code, 409)
                self.assertEqual((incident["appointment_stage"], incident["blocks_delivery"]), ("done", True))
                self.assertEqual((payment["pending_cents"], payment["delivered_cents"]), (8000, 0))
            movements = self.get(f"payment_movements?appointment_id=eq.{appt}&kind=eq.delivered", client)
            self.assertLessEqual(len(movements), 1)

    def test_direct_and_free_hold_no_managed_money(self):
        for hour, mode, amount in ((15, "direct", 8000), (17, "integrated", 0)):
            appt = self.started(hour, mode, amount)
            code, incident = self.open(appt, professional, "not_performed", "No estaba el cliente")
            self.assertEqual(code, 200)
            self.assertEqual(incident["delivered_cents"] in (None, 0), True)
            self.assertEqual(self.get(f"payment_movements?appointment_id=eq.{appt}", client), [])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Incident fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens, object_paths = [], [], []
    profile_id = str(uuid.uuid4())
    photo = (ROOT / "supabase/tests/fixtures/cover.jpg").read_bytes()
    conversation = None
    result = None
    try:
        for role in ("client", "professional", "other", "admin"):
            credentials = {"email": f"incident-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local incident fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local incident fixture")
            tokens.append(session["access_token"])
        client, professional, outsider, administrator = tokens
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato incidencias',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Incident contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato incidencias"}}]',array['es'],'000');
            insert into private.administrators(user_id) values ('{users[3]}');
            notify pgrst, 'reload schema';
            commit;""")
        conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, client)[1]["id"]
        code, agenda = api.request("/rest/v1/rpc/save_agenda", {"p_default_duration_minutes": 60,
                                   "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}, professional)
        if code != 200:
            raise SystemExit("Could not prepare the local agenda")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(IncidentContract))
    finally:
        # Only this invocation's random fixture UUIDs and synthetic concejo; never prepared data. The append-only
        # triggers are skipped for this cleanup transaction only.
        if object_paths:
            admin.request("/storage/v1/object/chat-attachments", {"prefixes": object_paths},
                          config["SERVICE_ROLE_KEY"], method="DELETE")
        mine = f"(select id from public.appointments where profile_id = '{profile_id}')"
        conversations = f"(select id from public.conversations where profile_id = '{profile_id}')"
        sql(f"""begin;
            set local session_replication_role = replica;
            delete from public.incident_evidence where incident_id in
              (select id from public.incidents where appointment_id in {mine});
            delete from public.incidents where appointment_id in {mine};
            delete from public.payment_movements where appointment_id in {mine};
            delete from public.appointment_payments where appointment_id in {mine};
            delete from public.agenda_reservations where profile_id = '{profile_id}';
            delete from public.availability_windows where profile_id = '{profile_id}';
            delete from public.agenda_settings where profile_id = '{profile_id}';
            delete from public.appointment_events where appointment_id in {mine};
            delete from public.appointment_changes where appointment_id in {mine};
            delete from public.appointment_proposals where appointment_id in {mine};
            delete from public.appointments where profile_id = '{profile_id}';
            delete from public.conversation_reads where conversation_id in {conversations};
            delete from public.chat_messages where conversation_id in {conversations};
            delete from private.chat_uploads where conversation_id in {conversations};
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            delete from private.administrators where user_id::text = '{users[3] if len(users) > 3 else ''}';
            delete from public.concejos where ine_code = '{CONCEJO}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result is not None and result.wasSuccessful() else 1)
