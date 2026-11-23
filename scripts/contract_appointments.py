#!/usr/bin/env python3
"""Real Auth/PostgREST appointment request/proposal contract (#20). Disposable fixtures on loopback ONLY."""
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

CONCEJO = "33995"  # Synthetic code, never a real concejo.


class AppointmentContract(unittest.TestCase):
    def test_request_propose_reject_repropose_and_privacy(self):
        code, conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, tokens[0])
        self.assertEqual(code, 200)
        self.assertNotIn("address", json.dumps(conversation))
        cid = conversation["id"]
        request = {"p_conversation_id": cid, "p_request_id": str(uuid.uuid4()), "p_problem": "Fuga bajo el fregadero",
                   "p_start": "2030-01-15T10:00:00Z", "p_end": "2030-01-15T12:00:00Z",
                   "p_address": "Calle Ficticia 1, 3ºB", "p_lat": 43.05, "p_lng": -5.95}
        self.assertIn(api.request("/rest/v1/rpc/request_appointment", request)[0], (401, 403))
        code, appointment = api.request("/rest/v1/rpc/request_appointment", request, tokens[0])
        self.assertEqual(code, 200, appointment)
        self.assertEqual((appointment["stage"], appointment["version"], appointment["client_id"]), ("pending", 1, users[0]))
        code, retry = api.request("/rest/v1/rpc/request_appointment", request, tokens[0])
        self.assertEqual((code, retry["id"]), (200, appointment["id"]))
        # Error bodies are not printed by Api; status codes identify the stable error class.
        code, _ = api.request("/rest/v1/rpc/request_appointment", dict(request, p_problem="Otra"), tokens[0])
        self.assertEqual(code, 400)  # retry_conflict
        self.assertIn(api.request("/rest/v1/rpc/request_appointment", dict(request, p_request_id=str(uuid.uuid4())),
                                  tokens[2])[0], (401, 403))

        proposal = {"p_appointment_id": appointment["id"], "p_expected_version": 1, "p_request_id": str(uuid.uuid4()),
                    "p_scope": "Cambio de sifón con materiales", "p_start": "2030-01-15T10:30:00Z",
                    "p_duration_minutes": 90, "p_amount_cents": 8500, "p_visit_fee_cents": 2000,
                    "p_materials_included": True, "p_travel_included": True}
        self.assertIn(api.request("/rest/v1/rpc/propose_appointment", proposal, tokens[0])[0], (401, 403))
        code, first = api.request("/rest/v1/rpc/propose_appointment", proposal, tokens[1])
        self.assertEqual((code, first["version"], first["status"]), (200, 1, "pending"), first)
        self.assertEqual(api.request("/rest/v1/rpc/propose_appointment", proposal, tokens[1])[1]["id"], first["id"])
        code, _ = api.request("/rest/v1/rpc/propose_appointment",
                              dict(proposal, p_request_id=str(uuid.uuid4())), tokens[1])
        self.assertEqual(code, 409)  # stale_version
        self.assertIn(api.request("/rest/v1/rpc/reject_proposal", {"p_proposal_id": first["id"]}, tokens[1])[0], (401, 403))
        code, rejected = api.request("/rest/v1/rpc/reject_proposal", {"p_proposal_id": first["id"]}, tokens[0])
        self.assertEqual((code, rejected["status"]), (200, "rejected"))
        code, second = api.request("/rest/v1/rpc/propose_appointment", dict(
            proposal, p_expected_version=3, p_request_id=str(uuid.uuid4()), p_amount_cents=6000,
            p_visit_fee_cents=None, p_materials_included=False), tokens[1])
        self.assertEqual((code, second["version"]), (200, 2), second)

        # Same read the Android client performs: appointment plus embedded proposal history.
        path = (f"/rest/v1/appointments?select=*,appointment_proposals(*)&conversation_id=eq.{cid}"
                "&order=created_at.asc")
        for token in tokens[:2]:
            code, rows = api.request(path, token=token, method="GET")
            self.assertEqual(code, 200)
            self.assertEqual(rows[0]["address"], "Calle Ficticia 1, 3ºB")
            self.assertEqual(sorted((p["version"], p["status"]) for p in rows[0]["appointment_proposals"]),
                             [(1, "rejected"), (2, "pending")])
        for token in (None, tokens[2]):
            code, rows = api.request(path, token=token, method="GET")
            self.assertTrue(code in (401, 403) or (code == 200 and rows == []))
            code, rows = api.request("/rest/v1/appointment_events?select=*", token=token, method="GET")
            self.assertTrue(code in (401, 403) or (code == 200 and rows == []))
        code, notices = api.request("/rest/v1/appointment_events?select=kind&order=created_at.asc",
                                    token=tokens[0], method="GET")
        self.assertEqual([n["kind"] for n in notices], ["requested", "proposed", "rejected", "proposed"])
        for method, body in (("PATCH", {"stage": "confirmed"}), ("DELETE", None)):
            self.assertIn(api.request(f"/rest/v1/appointments?id=eq.{appointment['id']}", body, tokens[0], method)[0],
                          (401, 403))
        self.assertIn(api.request("/rest/v1/appointment_proposals", dict(first, id=str(uuid.uuid4())), tokens[1])[0],
                      (401, 403))
        # No public surface exposes the address: catalogue and search data never contain it.
        code, public = api.request(f"/rest/v1/approved_profiles?profile_id=eq.{profile_id}", method="GET")
        self.assertNotIn("Calle Ficticia", json.dumps(public))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Appointment fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    result = None
    try:
        for role in ("client", "professional", "other"):
            credentials = {"email": f"appointment-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                      dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local appointment fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local appointment fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato citas',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Appointment contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato citas"}}]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(AppointmentContract))
    finally:
        # Only this invocation's random fixture UUIDs and synthetic concejo; never prepared data.
        sql(f"""begin;
            delete from public.appointment_events where appointment_id in
              (select id from public.appointments where profile_id = '{profile_id}');
            delete from public.appointment_proposals where appointment_id in
              (select id from public.appointments where profile_id = '{profile_id}');
            delete from public.appointments where profile_id = '{profile_id}';
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            delete from public.concejos where ine_code = '{CONCEJO}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result is not None and result.wasSuccessful() else 1)
