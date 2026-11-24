#!/usr/bin/env python3
"""Real Auth/PostgREST contract for changing the conditions of a confirmed appointment (#23): versioned
changes, acceptance by the other participant, truly concurrent acceptances and retries over HTTP.
Disposable fixtures on loopback ONLY; payments are simulated, no real money exists."""
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

CONCEJO = "33922"  # Synthetic code, never a real concejo.
DAY = "2030-01-15"  # Tuesday; Madrid is UTC+1 then, so the window 08:00–20:00 local is 07:00–19:00Z.
ROUNDS = 3


class ChangesContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def confirmed(self, client, conversation, start, end, amount):
        """A request, a proposal and its integrated confirmation: an appointment with conditions in force."""
        code, appointment = self.rpc("request_appointment", {
            "p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()), "p_problem": "Fuga",
            "p_start": start, "p_end": end, "p_address": "Calle Ficticia 1", "p_lat": 43.05, "p_lng": -5.95}, client)
        self.assertEqual(code, 200, appointment)
        code, proposal = self.rpc("propose_appointment", {
            "p_appointment_id": appointment["id"], "p_expected_version": 1, "p_request_id": str(uuid.uuid4()),
            "p_scope": "Cambiar sifón", "p_start": start, "p_duration_minutes": 60, "p_amount_cents": amount,
            "p_visit_fee_cents": None, "p_materials_included": True, "p_travel_included": True}, tokens[1])
        self.assertEqual(code, 200, proposal)
        code, payment = self.rpc("confirm_proposal", {"p_proposal_id": proposal["id"], "p_request_id": str(uuid.uuid4()),
                                                      "p_mode": "integrated"}, client)
        self.assertEqual(code, 200, payment)
        return appointment["id"]

    def state(self, appointment_id, token):
        """What the app reads: the appointment with its proposals, payment and changes (one embedded request)."""
        code, rows = api.request(
            f"/rest/v1/appointments?select=version,stage,appointment_proposals(scope,status,start_at,amount_cents),"
            f"appointment_payments(*),appointment_changes(*)&id=eq.{appointment_id}", token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows[0] if rows else None

    def change(self, appointment_id, token, scope, start, amount, minutes=60, key=None, **extra):
        version = self.state(appointment_id, token)["version"]
        body = {"p_appointment_id": appointment_id, "p_expected_version": version,
                "p_request_id": key or str(uuid.uuid4()), "p_scope": scope, "p_start": start,
                "p_duration_minutes": minutes, "p_amount_cents": amount, "p_visit_fee_cents": None,
                "p_materials_included": True, "p_travel_included": True}
        body.update(extra)
        return self.rpc("propose_change", body, token)

    def movements(self, appointment_id, token):
        code, rows = api.request(f"/rest/v1/payment_movements?select=kind,amount_cents&appointment_id=eq.{appointment_id}"
                                 "&order=created_at", token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows

    def test_changes_versions_concurrency_and_permissions(self):
        conversations = [self.rpc("open_conversation", {"p_profile_id": profile_id}, t)[1]["id"] for t in (tokens[0], tokens[2])]
        agenda = {"p_default_duration_minutes": 60, "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}
        self.assertEqual(self.rpc("save_agenda", agenda, tokens[1])[0], 200)

        # Two confirmed appointments are moved to the same free hour and both clients accept at once:
        # one move wins, the other keeps its whole previous appointment.
        for round_ in range(ROUNDS):
            a = self.confirmed(tokens[0], conversations[0], f"{DAY}T{7 + 3 * round_:02d}:00:00Z",
                               f"{DAY}T{8 + 3 * round_:02d}:00:00Z", 8000)
            b = self.confirmed(tokens[2], conversations[1], f"{DAY}T{8 + 3 * round_:02d}:00:00Z",
                               f"{DAY}T{9 + 3 * round_:02d}:00:00Z", 6000)
            target = f"{DAY}T{9 + 3 * round_:02d}:00:00Z"
            code, ca = self.change(a, tokens[1], "Mover A", target, 8000)
            self.assertEqual(code, 200, ca)
            code, cb = self.change(b, tokens[1], "Mover B", target, 6000)
            self.assertEqual(code, 200, cb)
            results = simultaneously(lambda: self.rpc("accept_change", {"p_change_id": ca["id"]}, tokens[0]),
                                     lambda: self.rpc("accept_change", {"p_change_id": cb["id"]}, tokens[2]))
            self.assertEqual(sorted(code for code, _ in results), [200, 409], f"round {round_}: {results}")
            loser, loser_token = (b, tokens[2]) if results[0][0] == 200 else (a, tokens[0])
            kept = self.state(loser, loser_token)
            self.assertEqual([p["scope"] for p in kept["appointment_proposals"] if p["status"] == "accepted"], ["Cambiar sifón"])
            self.assertEqual([c["status"] for c in kept["appointment_changes"]], ["pending"])
            code, mine = self.rpc("my_agenda", {}, tokens[1])
            starts = [x["start_at"] for x in mine["bookings"] if x["appointment_id"] == loser]
            self.assertNotEqual(starts, [target.replace("Z", "+00:00")], "the loser keeps its original hour")
            self.assertEqual(len(starts), 1)

        # An increase accepted twice at once (and again after a "lost" answer): one simulated difference.
        appt = self.confirmed(tokens[0], conversations[0], f"{DAY}T18:00:00Z", f"{DAY}T19:00:00Z", 10000)
        code, more = self.change(appt, tokens[1], "Sifón y llave", f"{DAY}T18:00:00Z", 13000)
        self.assertEqual(code, 200, more)
        twice = simultaneously(*[lambda: self.rpc("accept_change", {"p_change_id": more["id"]}, tokens[0])] * 2)
        self.assertEqual([code for code, _ in twice], [200, 200], twice)
        self.assertEqual(self.rpc("accept_change", {"p_change_id": more["id"]}, tokens[0])[0], 200)
        for token in tokens[:2]:
            self.assertEqual(self.movements(appt, token), [{"kind": "paid", "amount_cents": 10000},
                                                           {"kind": "paid", "amount_cents": 3000}])
        payment = self.state(appt, tokens[0])["appointment_payments"]
        self.assertEqual((payment["paid_cents"], payment["pending_cents"], payment["agreed_cents"]), (13000, 13000, 13000))

        # A retried proposal with the same key returns the same version; other content is a conflict.
        key = str(uuid.uuid4())
        code, less = self.change(appt, tokens[0], "Solo sifón", f"{DAY}T18:00:00Z", 9000, key=key)
        self.assertEqual(code, 200, less)
        self.assertEqual(self.change(appt, tokens[0], "Solo sifón", f"{DAY}T18:00:00Z", 9000, key=key)[1]["id"], less["id"])
        self.assertEqual(self.change(appt, tokens[0], "Solo sifón", f"{DAY}T18:00:00Z", 8000, key=key)[0], 400)
        # An old version is never applied: a newer one supersedes it and the old one answers 409.
        code, newer = self.change(appt, tokens[0], "Solo sifón", f"{DAY}T18:00:00Z", 9500)
        self.assertEqual(self.rpc("accept_change", {"p_change_id": less["id"]}, tokens[1])[0], 409)
        self.assertEqual(self.rpc("accept_change", {"p_change_id": newer["id"]}, tokens[1])[0], 200)
        self.assertEqual(self.movements(appt, tokens[0])[-1], {"kind": "refunded", "amount_cents": 3500})

        # The professional cannot replace the address; out-of-coverage addresses are refused.
        code, body = self.change(appt, tokens[1], "Solo sifón", f"{DAY}T18:00:00Z", 9500,
                                 p_address="Otra calle 2", p_lat=43.06, p_lng=-5.94)
        self.assertEqual(code, 403, "address_owner_only")
        code, body = self.change(appt, tokens[0], "Solo sifón", f"{DAY}T18:00:00Z", 9500,
                                 p_address="Lejos 1", p_lat=43.6, p_lng=-5.4)
        self.assertEqual(code, 400, "out_of_coverage")

        # Third accounts and anonymous callers neither read nor act; nobody writes the tables directly.
        self.assertIsNone(self.state(appt, tokens[2]))
        for token in (None, tokens[2]):
            self.assertIn(self.rpc("reject_change", {"p_change_id": newer["id"]}, token)[0], (401, 403))
        for token in tokens:
            self.assertIn(api.request(f"/rest/v1/appointment_changes?id=eq.{newer['id']}", {"status": "pending"},
                                      token, "PATCH")[0], (401, 403))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Change fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    result = None
    try:
        for role in ("client", "professional", "client2"):
            credentials = {"email": f"changes-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local changes fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local changes fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato cambios',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Changes contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato cambios"}}]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ChangesContract))
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
