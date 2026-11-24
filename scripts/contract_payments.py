#!/usr/bin/env python3
"""Real Auth/PostgREST contract for confirming proposals with a cost (#22): simulated integrated payment
and direct-payment agreement, with truly concurrent confirmations and retries over HTTP.
Disposable fixtures on loopback ONLY; no real money, provider or bank data exists."""
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

CONCEJO = "33991"  # Synthetic code, never a real concejo.
DAY = "2030-01-15"  # Tuesday; Madrid is UTC+1 then, so the window 08:00–20:00 local is 07:00–19:00Z.
ROUNDS = 6


class PaymentsContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def proposal(self, client, conversation, start, end, amount, visit_fee=None):
        code, appointment = self.rpc("request_appointment", {
            "p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()), "p_problem": "Fuga",
            "p_start": start, "p_end": end, "p_address": "Calle Ficticia 1", "p_lat": 43.05, "p_lng": -5.95}, client)
        self.assertEqual(code, 200, appointment)
        code, proposal = self.rpc("propose_appointment", {
            "p_appointment_id": appointment["id"], "p_expected_version": 1, "p_request_id": str(uuid.uuid4()),
            "p_scope": "Cambiar sifón", "p_start": start, "p_duration_minutes": 60, "p_amount_cents": amount,
            "p_visit_fee_cents": visit_fee, "p_materials_included": True, "p_travel_included": True}, tokens[1])
        self.assertEqual(code, 200, proposal)
        return appointment, proposal

    def confirm(self, proposal, key, mode, token):
        return self.rpc("confirm_proposal", {"p_proposal_id": proposal["id"], "p_request_id": key, "p_mode": mode}, token)

    def payments(self, appointment, token):
        code, rows = api.request(f"/rest/v1/appointments?select=stage,appointment_payments(*)&id=eq.{appointment['id']}",
                                 token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows

    def movements(self, appointment, token):
        code, rows = api.request(f"/rest/v1/payment_movements?select=kind,amount_cents&appointment_id=eq.{appointment['id']}",
                                 token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows

    def test_payment_modes_concurrency_retries_and_permissions(self):
        conversations = [self.rpc("open_conversation", {"p_profile_id": profile_id}, t)[1]["id"] for t in (tokens[0], tokens[2])]
        agenda = {"p_default_duration_minutes": 60, "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}
        self.assertEqual(self.rpc("save_agenda", agenda, tokens[1])[0], 200)

        # Two clients pay overlapping proposals at the same instant: one wins, the other is not charged.
        for hour in range(ROUNDS):
            start, end = f"{DAY}T{7 + hour:02d}:00:00Z", f"{DAY}T{8 + hour:02d}:00:00Z"
            a, pa = self.proposal(tokens[0], conversations[0], start, end, 8500)
            b, pb = self.proposal(tokens[2], conversations[1], start, end, 6000)
            results = simultaneously(lambda: self.confirm(pa, str(uuid.uuid4()), "integrated", tokens[0]),
                                     lambda: self.confirm(pb, str(uuid.uuid4()), "integrated", tokens[2]))
            self.assertEqual(sorted(code for code, _ in results), [200, 409], f"round {hour}: {results}")
            (winner, winner_token), (loser, loser_token) = ((a, tokens[0]), (b, tokens[2])) \
                if results[0][0] == 200 else ((b, tokens[2]), (a, tokens[0]))
            self.assertEqual(results[0 if winner is a else 1][1]["paid_cents"], 8500 if winner is a else 6000)
            self.assertEqual(self.payments(loser, loser_token), [{"stage": "pending", "appointment_payments": None}])
            self.assertEqual(self.movements(loser, loser_token), [])
            self.assertEqual(len(self.movements(winner, winner_token)), 1)

        # The same key sent twice at once, then again after a "lost" answer: one payment, one movement.
        appt, paid = self.proposal(tokens[0], conversations[0], f"{DAY}T14:00:00Z", f"{DAY}T15:00:00Z", 12000)
        key = str(uuid.uuid4())
        twice = simultaneously(*[lambda: self.confirm(paid, key, "integrated", tokens[0])] * 2)
        self.assertEqual([code for code, _ in twice], [200, 200], twice)
        self.assertEqual(twice[0][1], twice[1][1])
        self.assertEqual(self.confirm(paid, key, "integrated", tokens[0])[1], twice[0][1])
        self.assertEqual(self.confirm(paid, key, "direct", tokens[0])[0], 400, "a key reused with other content")
        self.assertEqual(self.confirm(paid, str(uuid.uuid4()), "integrated", tokens[0])[1]["request_id"], key)
        for token in tokens[:2]:
            self.assertEqual(self.movements(appt, token), [{"kind": "paid", "amount_cents": 12000}])
            rows = self.payments(appt, token)
            self.assertEqual(rows[0]["stage"], "confirmed")
            money = {k: rows[0]["appointment_payments"][k] for k in
                     ("mode", "paid_cents", "pending_cents", "refunded_cents", "delivered_cents", "simulated")}
            self.assertEqual(money, {"mode": "integrated", "paid_cents": 12000, "pending_cents": 12000,
                                     "refunded_cents": 0, "delivered_cents": 0, "simulated": True})
        # #38: paying confirms, so the professional now gets the exact place; an unconfirmed one stays approximate.
        code, places = self.rpc("appointment_places", {"p_conversation_id": conversations[0]}, tokens[1])
        self.assertEqual(code, 200, places)
        exact = {p["appointment_id"]: p["exact"] for p in places}
        self.assertTrue(exact[appt["id"]])
        _, unpaid = self.proposal(tokens[0], conversations[0], f"{DAY}T18:00:00Z", f"{DAY}T19:00:00Z", 4000)
        code, places = self.rpc("appointment_places", {"p_conversation_id": conversations[0]}, tokens[1])
        self.assertFalse({p["appointment_id"]: p["exact"] for p in places}[unpaid["appointment_id"]])
        self.assertEqual(self.payments(appt, tokens[2]), [], "another client reads nothing")
        self.assertEqual(self.movements(appt, tokens[2]), [])

        # Direct agreement: confirmed and reserved, no integrated movement.
        direct_appt, direct = self.proposal(tokens[2], conversations[1], f"{DAY}T15:00:00Z", f"{DAY}T16:00:00Z", 7000)
        code, agreement = self.confirm(direct, str(uuid.uuid4()), "direct", tokens[2])
        self.assertEqual((code, agreement["mode"], agreement["agreed_cents"], agreement["paid_cents"]),
                         (200, "direct", 7000, 0), agreement)
        self.assertEqual(self.movements(direct_appt, tokens[1]), [])
        code, mine = self.rpc("my_agenda", {}, tokens[1])
        self.assertIn(direct_appt["id"], [b["appointment_id"] for b in mine["bookings"]])

        # Free visits are never charged and keep the free acceptance.
        _, free = self.proposal(tokens[0], conversations[0], f"{DAY}T16:00:00Z", f"{DAY}T17:00:00Z", 0)
        self.assertEqual(self.confirm(free, str(uuid.uuid4()), "integrated", tokens[0])[0], 400)
        self.assertEqual(self.rpc("accept_proposal", {"p_proposal_id": free["id"]}, tokens[0])[0], 200)

        # Permissions and no direct writes.
        _, other = self.proposal(tokens[0], conversations[0], f"{DAY}T17:00:00Z", f"{DAY}T18:00:00Z", 5000)
        for token in (None, tokens[1], tokens[2]):
            self.assertIn(self.confirm(other, str(uuid.uuid4()), "integrated", token)[0], (401, 403))
        for token in tokens:
            self.assertIn(api.request("/rest/v1/payment_movements", {"appointment_id": appt["id"], "kind": "delivered",
                                      "amount_cents": 12000}, token)[0], (401, 403))
            self.assertIn(api.request(f"/rest/v1/appointment_payments?appointment_id=eq.{appt['id']}",
                                      {"delivered_cents": 12000, "pending_cents": 0}, token, "PATCH")[0], (401, 403))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Payment fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    result = None
    try:
        for role in ("client", "professional", "client2"):
            credentials = {"email": f"payments-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local payments fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local payments fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato pagos',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Payments contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato pagos"}}]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(PaymentsContract))
    finally:
        # Only this invocation's random fixture UUIDs and synthetic concejo; never prepared data.
        sql(f"""begin;
            delete from public.payment_movements where appointment_id in
              (select id from public.appointments where profile_id = '{profile_id}');
            delete from public.appointment_payments where appointment_id in
              (select id from public.appointments where profile_id = '{profile_id}');
            delete from public.agenda_reservations where profile_id = '{profile_id}';
            delete from public.availability_windows where profile_id = '{profile_id}';
            delete from public.agenda_settings where profile_id = '{profile_id}';
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
