#!/usr/bin/env python3
"""Real Auth/PostgREST contract for «Realizado» and the client's manual confirmation (#25): the full journey from the
request to the simulated delivery between two accounts, simultaneous retries that deliver once, direct and free
appointments without managed money, and third or anonymous accounts that cannot act. Disposable fixtures on loopback
ONLY; money is simulated."""
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
from contract_agenda import simultaneously

CONCEJO = "33920"  # Synthetic code, never a real concejo.
DAY = "2030-01-15"  # Tuesday; Madrid is UTC+1 then, so the window 08:00–20:00 local is 07:00–19:00Z.


class CompletionContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def started(self, hour, mode="integrated", amount=8000):
        start, end = f"{DAY}T{hour:02d}:00:00Z", f"{DAY}T{hour + 1:02d}:00:00Z"
        code, appointment = self.rpc("request_appointment", {
            "p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()), "p_problem": "Fuga",
            "p_start": start, "p_end": end, "p_address": "Calle Ficticia 1", "p_lat": 43.05, "p_lng": -5.95}, tokens[0])
        self.assertEqual(code, 200, appointment)
        code, proposal = self.rpc("propose_appointment", {
            "p_appointment_id": appointment["id"], "p_expected_version": 1, "p_request_id": str(uuid.uuid4()),
            "p_scope": "Cambiar sifón", "p_start": start, "p_duration_minutes": 60, "p_amount_cents": amount,
            "p_visit_fee_cents": None, "p_materials_included": True, "p_travel_included": True}, tokens[1])
        self.assertEqual(code, 200, proposal)
        if amount:
            code, body = self.rpc("confirm_proposal", {"p_proposal_id": proposal["id"], "p_request_id": str(uuid.uuid4()),
                                                       "p_mode": mode}, tokens[0])
        else:
            code, body = self.rpc("accept_proposal", {"p_proposal_id": proposal["id"]}, tokens[0])
        self.assertEqual(code, 200, body)
        self.assertEqual(self.rpc("start_work", {"p_appointment_id": appointment["id"]}, tokens[1])[0], 200)
        return appointment["id"]

    def state(self, appointment_id, token):
        """What the app reads: stage, server marks and payment, in one embedded request."""
        code, rows = api.request(
            f"/rest/v1/appointments?select=stage,done_at,reminder_due_at,close_due_at,completed_at,completion,"
            f"appointment_payments(*)&id=eq.{appointment_id}", token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows[0] if rows else None

    def movements(self, appointment_id, token):
        code, rows = api.request(f"/rest/v1/payment_movements?select=kind,amount_cents&appointment_id=eq.{appointment_id}"
                                 f"&order=created_at", token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return [f"{r['kind']}:{r['amount_cents']}" for r in rows]

    def events(self, appointment_id, token, kind):
        code, rows = api.request(f"/rest/v1/appointment_events?select=actor_id,recipient_id&kind=eq.{kind}"
                                 f"&appointment_id=eq.{appointment_id}", token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows

    def test_journey_until_the_manual_delivery(self):
        agenda = {"p_default_duration_minutes": 60, "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}
        self.assertEqual(self.rpc("save_agenda", agenda, tokens[1])[0], 200)
        appt = self.started(7)
        done = {"p_appointment_id": appt}

        # Premature or foreign actions are refused and change nothing.
        self.assertEqual(self.rpc("confirm_done", done, tokens[0])[0], 409, "not marked done yet")
        self.assertEqual(self.rpc("mark_done", done, tokens[0])[0], 403, "only the professional marks done")

        # The professional's mark, sent twice at once (a retry after a lost answer): one mark, one notice.
        marks = simultaneously(lambda: self.rpc("mark_done", done, tokens[1]), lambda: self.rpc("mark_done", done, tokens[1]))
        self.assertEqual([code for code, _ in marks], [200, 200], marks)
        self.assertEqual(marks[0][1]["done_at"], marks[1][1]["done_at"])
        self.assertEqual(marks[0][1]["delivered_cents"], 0, "marking done delivers nothing")
        for token in tokens[:2]:
            seen = self.state(appt, token)
            self.assertEqual(seen["stage"], "done")
            self.assertIsNotNone(seen["close_due_at"])
            self.assertEqual(seen["appointment_payments"]["pending_cents"], 8000)
            self.assertEqual(len(self.events(appt, token, "done")), 1)
        self.assertEqual(self.rpc("confirm_done", done, tokens[1])[0], 403, "the professional cannot confirm")

        # The client confirms twice at the same instant: both answers agree and the delivery happens once.
        confirms = simultaneously(lambda: self.rpc("confirm_done", done, tokens[0]),
                                  lambda: self.rpc("confirm_done", done, tokens[0]))
        self.assertEqual([code for code, _ in confirms], [200, 200], confirms)
        self.assertEqual({body["completed_at"] for _, body in confirms}.__len__(), 1)
        self.assertEqual(confirms[0][1]["delivered_cents"], 8000)
        for token in tokens[:2]:
            seen = self.state(appt, token)
            self.assertEqual((seen["stage"], seen["completion"]), ("completed", "client_confirmed"))
            payment = seen["appointment_payments"]
            self.assertEqual((payment["paid_cents"], payment["pending_cents"], payment["delivered_cents"]), (8000, 0, 8000))
            self.assertEqual(self.movements(appt, token), ["paid:8000", "delivered:8000"])
            self.assertEqual(len(self.events(appt, token, "completed")), 1)

        # Direct and free: completed without inventing managed money.
        for hour, mode, amount in ((9, "direct", 8000), (10, "integrated", 0)):
            other = self.started(hour, mode, amount)
            self.assertEqual(self.rpc("mark_done", {"p_appointment_id": other}, tokens[1])[0], 200)
            code, body = self.rpc("confirm_done", {"p_appointment_id": other}, tokens[0])
            self.assertEqual((code, body["stage"], body["delivered_cents"]), (200, "completed", 0), body)
            self.assertEqual(self.movements(other, tokens[0]), [])

    def test_third_accounts_and_direct_writes(self):
        agenda = {"p_default_duration_minutes": 60, "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}
        self.assertEqual(self.rpc("save_agenda", agenda, tokens[1])[0], 200)
        appt = self.started(12)
        self.assertIsNone(self.state(appt, tokens[2]))
        for name in ("mark_done", "confirm_done"):
            for token in (None, tokens[2]):
                self.assertIn(self.rpc(name, {"p_appointment_id": appt}, token)[0], (401, 403))
        self.assertEqual(self.rpc("mark_done", {"p_appointment_id": appt}, tokens[1])[0], 200)
        for token in tokens:
            self.assertIn(api.request(f"/rest/v1/appointments?id=eq.{appt}", {"stage": "completed"}, token, "PATCH")[0],
                          (401, 403))
            self.assertIn(api.request(f"/rest/v1/appointment_payments?appointment_id=eq.{appt}", {"delivered_cents": 8000},
                                      token, "PATCH")[0], (401, 403))
        self.assertEqual(self.state(appt, tokens[0])["stage"], "done")
        self.assertEqual(self.rpc("confirm_done", {"p_appointment_id": appt}, tokens[2])[0], 403)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Completion fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    conversation = None
    result = None
    try:
        for role in ("client", "professional", "other"):
            credentials = {"email": f"done-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local completion fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local completion fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato realizado',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Completion contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato realizado"}}]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, tokens[0])[1]["id"]
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(CompletionContract))
    finally:
        # Only this invocation's random fixture UUIDs and synthetic concejo; never prepared data.
        mine = f"(select id from public.appointments where profile_id = '{profile_id}')"
        sql(f"""begin;
            delete from public.payment_movements where appointment_id in {mine};
            delete from public.appointment_payments where appointment_id in {mine};
            delete from public.agenda_reservations where profile_id = '{profile_id}';
            delete from public.availability_windows where profile_id = '{profile_id}';
            delete from public.agenda_settings where profile_id = '{profile_id}';
            delete from public.appointment_events where appointment_id in {mine};
            delete from public.appointment_changes where appointment_id in {mine};
            delete from public.appointment_proposals where appointment_id in {mine};
            delete from public.appointments where profile_id = '{profile_id}';
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            delete from public.concejos where ine_code = '{CONCEJO}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result is not None and result.wasSuccessful() else 1)
