#!/usr/bin/env python3
"""Real Auth/PostgREST contract for «Comenzar trabajo» and cancellation before the start (#24): a start and a
cancellation sent at the same instant end in exactly one of them, simultaneous cancellations refund once, and
third or anonymous accounts cannot act. Disposable fixtures on loopback ONLY; money is simulated."""
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

CONCEJO = "33925"  # Synthetic code, never a real concejo.
DAY = "2030-01-15"  # Tuesday; Madrid is UTC+1 then, so the window 08:00–20:00 local is 07:00–19:00Z.
ROUNDS = 4


class StartCancelContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def confirmed(self, hour, mode="integrated", amount=8000):
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
        return appointment["id"]

    def state(self, appointment_id, token):
        """What the app reads: stage, server marks and payment, in one embedded request."""
        code, rows = api.request(
            f"/rest/v1/appointments?select=stage,started_at,cancelled_at,cancelled_by,appointment_payments(*)"
            f"&id=eq.{appointment_id}", token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows[0] if rows else None

    def refunds(self, appointment_id, token):
        code, rows = api.request(f"/rest/v1/payment_movements?select=amount_cents&kind=eq.refunded&cancellation=is.true"
                                 f"&appointment_id=eq.{appointment_id}", token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return [r["amount_cents"] for r in rows]

    def booked(self, appointment_id):
        code, mine = self.rpc("my_agenda", {}, tokens[1])
        self.assertEqual(code, 200, mine)
        return [b for b in mine["bookings"] if b["appointment_id"] == appointment_id]

    def test_start_and_cancel_race_retries_and_permissions(self):
        agenda = {"p_default_duration_minutes": 60, "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}
        self.assertEqual(self.rpc("save_agenda", agenda, tokens[1])[0], 200)

        # Start (professional) and cancel (client) at the same instant: exactly one wins and the state agrees.
        outcomes = set()
        for round_ in range(ROUNDS):
            appt = self.confirmed(7 + round_)
            results = simultaneously(lambda: self.rpc("start_work", {"p_appointment_id": appt}, tokens[1]),
                                     lambda: self.rpc("cancel_appointment", {"p_appointment_id": appt}, tokens[0]))
            self.assertEqual(sorted(code for code, _ in results), [200, 409], f"round {round_}: {results}")
            started = results[0][0] == 200
            outcomes.add("started" if started else "cancelled")
            for token in tokens[:2]:
                seen = self.state(appt, token)
                payment = seen["appointment_payments"]
                if started:
                    self.assertEqual((seen["stage"], seen["cancelled_at"]), ("started", None))
                    self.assertEqual((payment["pending_cents"], payment["refunded_cents"]), (8000, 0))
                    self.assertEqual(self.refunds(appt, token), [])
                else:
                    self.assertEqual((seen["stage"], seen["started_at"]), ("cancelled", None))
                    self.assertEqual((payment["pending_cents"], payment["refunded_cents"]), (0, 8000))
                    self.assertEqual(self.refunds(appt, token), [8000])
            self.assertEqual(len(self.booked(appt)), 1 if started else 0)
        print(f"start/cancel race outcomes: {sorted(outcomes)}")

        # Both participants cancel at once, then retry after a "lost" answer: one refund, one cancellation.
        appt = self.confirmed(12, amount=9000)
        both = simultaneously(lambda: self.rpc("cancel_appointment", {"p_appointment_id": appt}, tokens[0]),
                              lambda: self.rpc("cancel_appointment", {"p_appointment_id": appt}, tokens[1]))
        self.assertEqual([code for code, _ in both], [200, 200], both)
        self.assertEqual(both[0][1]["cancelled_by"], both[1][1]["cancelled_by"])
        self.assertEqual(self.rpc("cancel_appointment", {"p_appointment_id": appt}, tokens[0])[1]["refunded_cents"], 9000)
        self.assertEqual(self.refunds(appt, tokens[0]), [9000])
        self.assertEqual(self.booked(appt), [])

        # Direct and free: the cancellation moves no money; the hour is released for another booking.
        direct = self.confirmed(13, mode="direct")
        free = self.confirmed(14, amount=0)
        for appt in (direct, free):
            code, body = self.rpc("cancel_appointment", {"p_appointment_id": appt}, tokens[1])
            self.assertEqual((code, body["refunded_cents"]), (200, 0), body)
            self.assertEqual(self.refunds(appt, tokens[0]), [])
        self.assertEqual(self.state(direct, tokens[0])["appointment_payments"]["refunded_cents"], 0)
        self.assertIsNone(self.state(free, tokens[0])["appointment_payments"])
        self.confirmed(13)  # The released 13:00 slot books again.

        # Third accounts and anonymous callers neither read nor act; nobody writes the marks directly.
        appt = self.confirmed(15)
        self.assertIsNone(self.state(appt, tokens[2]))
        for name in ("start_work", "cancel_appointment"):
            for token in (None, tokens[2]):
                self.assertIn(self.rpc(name, {"p_appointment_id": appt}, token)[0], (401, 403))
        self.assertEqual(self.rpc("start_work", {"p_appointment_id": appt}, tokens[0])[0], 403, "only its professional")
        for token in tokens:
            self.assertIn(api.request(f"/rest/v1/appointments?id=eq.{appt}", {"stage": "cancelled"}, token, "PATCH")[0],
                          (401, 403))
        self.assertEqual(self.state(appt, tokens[0])["stage"], "confirmed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Start/cancel fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    conversation = None
    result = None
    try:
        for role in ("client", "professional", "other"):
            credentials = {"email": f"start-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local start/cancel fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local start/cancel fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato inicio',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Start contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato inicio"}}]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, tokens[0])[1]["id"]
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(StartCancelContract))
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
