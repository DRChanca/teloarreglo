#!/usr/bin/env python3
"""Real Auth/PostgREST agenda and free-visit confirmation contract (#21), with truly concurrent
confirmations over HTTP. Disposable fixtures on loopback ONLY."""
import argparse
import json
import os
import secrets
import subprocess
import threading
import unittest
import uuid

from configuration import ROOT
from contract_identity import Api
from contract_chat import sql

CONCEJO = "33993"  # Synthetic code, never a real concejo.
ROUNDS = 8
# 2030-01-15 is a Tuesday; Madrid is UTC+1 then. The window 08:00–20:00 local is 07:00–19:00Z.
DAY = "2030-01-15"


def simultaneously(*calls):
    """Starts every call at the same instant and returns their results in order."""
    barrier = threading.Barrier(len(calls))
    results = [None] * len(calls)

    def run(index, call):
        barrier.wait()
        results[index] = call()

    threads = [threading.Thread(target=run, args=(i, c)) for i, c in enumerate(calls)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


class AgendaContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def free_proposal(self, client, conversation, start, end):
        code, appointment = self.rpc("request_appointment", {
            "p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()), "p_problem": "Fuga",
            "p_start": start, "p_end": end, "p_address": "Calle Ficticia 1", "p_lat": 43.05, "p_lng": -5.95}, client)
        self.assertEqual(code, 200, appointment)
        code, proposal = self.rpc("propose_appointment", {
            "p_appointment_id": appointment["id"], "p_expected_version": 1, "p_request_id": str(uuid.uuid4()),
            "p_scope": "Visita gratuita", "p_start": start, "p_duration_minutes": 60, "p_amount_cents": 0,
            "p_visit_fee_cents": None, "p_materials_included": False, "p_travel_included": True}, tokens[1])
        self.assertEqual(code, 200, proposal)
        return appointment, proposal

    def test_agenda_concurrency_permissions_and_retries(self):
        conversations = []
        for token in (tokens[0], tokens[2]):
            code, conversation = self.rpc("open_conversation", {"p_profile_id": profile_id}, token)
            self.assertEqual(code, 200)
            conversations.append(conversation["id"])
        agenda = {"p_default_duration_minutes": 60, "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}
        self.assertIn(self.rpc("save_agenda", agenda, tokens[0])[0], (401, 403))
        self.assertIn(self.rpc("save_agenda", agenda, None)[0], (401, 403))
        code, saved = self.rpc("save_agenda", agenda, tokens[1])
        self.assertEqual((code, saved["default_duration_minutes"], len(saved["windows"])), (200, 60, 1), saved)
        self.assertEqual(self.rpc("save_agenda", dict(agenda, p_default_duration_minutes=5), tokens[1])[0], 400)

        # Two clients confirm overlapping free proposals at the same instant, several times.
        wins = 0
        for hour in range(ROUNDS):
            start, end = f"{DAY}T{7 + hour:02d}:00:00Z", f"{DAY}T{8 + hour:02d}:00:00Z"
            a, pa = self.free_proposal(tokens[0], conversations[0], start, end)
            b, pb = self.free_proposal(tokens[2], conversations[1], start, end)
            results = simultaneously(lambda: self.rpc("accept_proposal", {"p_proposal_id": pa["id"]}, tokens[0]),
                                     lambda: self.rpc("accept_proposal", {"p_proposal_id": pb["id"]}, tokens[2]))
            codes = sorted(code for code, _ in results)
            self.assertEqual(codes, [200, 409], f"round {hour}: exactly one confirmation wins")
            wins += 1
            loser_token, loser = (tokens[2], b) if results[0][0] == 200 else (tokens[0], a)
            code, rows = api.request(f"/rest/v1/appointments?select=stage,appointment_proposals(status)"
                                     f"&id=eq.{loser['id']}", token=loser_token, method="GET")
            self.assertEqual((rows[0]["stage"], rows[0]["appointment_proposals"][0]["status"]), ("pending", "pending"))
        code, mine = self.rpc("my_agenda", {}, tokens[1])
        self.assertEqual((code, len(mine["bookings"])), (200, wins), "one reservation per round")

        # Contiguous hours touch: 15:00–16:00Z after the 14:00–15:00Z booking of the last round.
        _, contiguous = self.free_proposal(tokens[0], conversations[0], f"{DAY}T15:00:00Z", f"{DAY}T16:00:00Z")
        # The same client retries the same acceptance twice at once: both succeed, one notice.
        results = simultaneously(*[lambda: self.rpc("accept_proposal", {"p_proposal_id": contiguous["id"]}, tokens[0])] * 2)
        self.assertEqual([code for code, _ in results], [200, 200])
        self.assertEqual({body["status"] for _, body in results}, {"accepted"})
        code, notices = api.request(f"/rest/v1/appointment_events?select=kind&proposal_id=eq.{contiguous['id']}",
                                    token=tokens[1], method="GET")
        self.assertEqual([n["kind"] for n in notices], ["proposed", "accepted"])
        # Both accounts see the confirmed appointment.
        for token in tokens[:2]:
            code, rows = api.request(f"/rest/v1/appointments?select=stage&id=eq.{contiguous['appointment_id']}",
                                     token=token, method="GET")
            self.assertEqual(rows, [{"stage": "confirmed"}])

        # Outside availability, blocks and paid proposals are refused without partial effects.
        _, late = self.free_proposal(tokens[0], conversations[0], f"{DAY}T19:00:00Z", f"{DAY}T20:00:00Z")
        self.assertEqual(self.rpc("accept_proposal", {"p_proposal_id": late["id"]}, tokens[0])[0], 409)
        block = {"p_request_id": str(uuid.uuid4()), "p_start": f"{DAY}T16:00:00Z", "p_end": f"{DAY}T17:00:00Z",
                 "p_reason": "Desplazamiento"}
        self.assertIn(self.rpc("add_agenda_block", block, tokens[0])[0], (401, 403))
        code, blocked = self.rpc("add_agenda_block", block, tokens[1])
        self.assertEqual(code, 200, blocked)
        self.assertEqual(self.rpc("add_agenda_block", block, tokens[1])[1]["id"], blocked["id"])
        self.assertEqual(self.rpc("add_agenda_block", dict(block, p_request_id=str(uuid.uuid4()),
                         p_start=f"{DAY}T14:30:00Z"), tokens[1])[0], 409)
        _, during = self.free_proposal(tokens[0], conversations[0], f"{DAY}T16:00:00Z", f"{DAY}T17:00:00Z")
        self.assertEqual(self.rpc("accept_proposal", {"p_proposal_id": during["id"]}, tokens[0])[0], 409)
        code, removed = self.rpc("remove_agenda_block", {"p_block_id": blocked["id"]}, tokens[1])
        self.assertEqual((code, removed["removed"]), (200, True))
        self.assertEqual(self.rpc("accept_proposal", {"p_proposal_id": during["id"]}, tokens[0])[0], 200)

        # No direct writes; nobody else reads the agenda.
        for token in tokens:
            self.assertIn(api.request("/rest/v1/agenda_reservations", {
                "profile_id": profile_id, "professional_id": users[1], "kind": "block",
                "during": f"[{DAY}T18:00:00Z,{DAY}T19:00:00Z)", "request_id": str(uuid.uuid4())}, token)[0], (401, 403))
            self.assertIn(api.request(f"/rest/v1/appointment_proposals?id=eq.{late['id']}",
                                      {"status": "accepted"}, token, "PATCH")[0], (401, 403))
            self.assertIn(api.request(f"/rest/v1/agenda_reservations?profile_id=eq.{profile_id}",
                                      None, token, "DELETE")[0], (401, 403))
        for token in (None, tokens[0], tokens[2]):
            for table in ("agenda_reservations", "availability_windows", "agenda_settings"):
                code, rows = api.request(f"/rest/v1/{table}?select=*", token=token, method="GET")
                self.assertTrue(code in (401, 403) or (code == 200 and rows == []), table)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Agenda fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    result = None
    try:
        for role in ("client", "professional", "client2"):
            credentials = {"email": f"agenda-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local agenda fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local agenda fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato agenda',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Agenda contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato agenda"}}]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(AgendaContract))
    finally:
        # Only this invocation's random fixture UUIDs and synthetic concejo; never prepared data.
        sql(f"""begin;
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
