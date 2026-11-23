#!/usr/bin/env python3
"""Real Auth/PostgREST contract for the administrative resolution of incidents (#31): only administrators read the
review (appointment, accepted conditions, movements and contributed evidence; never the place or the chat) and decide;
two administrators settling at the same instant, or a settlement and the client's confirmation, end in one coherent
result without duplicate movements; the performance decision is separate from the money; the clock does not close
a job again after the resolution; the participants read the result and are told. The panel's own Supabase adapter
(panel/src/incidents.local.test.ts) settles and decides on one incident. Disposable fixtures on loopback ONLY; money
is simulated."""
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

CONCEJO = "33931"  # Synthetic code, never a real concejo.
DAY = "2030-01-15"  # Tuesday; Madrid is UTC+1 then, so the window 08:00–20:00 local is 07:00–19:00Z.


class IncidentResolutionContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def get(self, path, token):
        code, rows = api.request("/rest/v1/" + path, token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows

    def incident(self, hour, minute=0, done=True, kind="quality"):
        """An integrated 80 € job, started (and marked done), with an incident opened by the client."""
        start = f"{DAY}T{hour:02d}:{minute:02d}:00Z"
        end_hour, end_minute = divmod(hour * 60 + minute + 20, 60)
        end = f"{DAY}T{end_hour:02d}:{end_minute:02d}:00Z"
        code, appointment = self.rpc("request_appointment", {
            "p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()), "p_problem": "Fuga",
            "p_start": start, "p_end": end, "p_address": "Calle Reservada 9", "p_lat": 43.05, "p_lng": -5.95}, client)
        self.assertEqual(code, 200, appointment)
        code, proposal = self.rpc("propose_appointment", {
            "p_appointment_id": appointment["id"], "p_expected_version": 1, "p_request_id": str(uuid.uuid4()),
            "p_scope": "Cambiar sifón", "p_start": start, "p_duration_minutes": 20, "p_amount_cents": 8000,
            "p_visit_fee_cents": None, "p_materials_included": True, "p_travel_included": True}, professional)
        self.assertEqual(code, 200, proposal)
        self.assertEqual(self.rpc("confirm_proposal", {"p_proposal_id": proposal["id"], "p_request_id": str(uuid.uuid4()),
                                                       "p_mode": "integrated"}, client)[0], 200)
        self.assertEqual(self.rpc("start_work", {"p_appointment_id": appointment["id"]}, professional)[0], 200)
        if done:
            self.assertEqual(self.rpc("mark_done", {"p_appointment_id": appointment["id"]}, professional)[0], 200)
        code, incident = self.rpc("open_incident", {"p_appointment_id": appointment["id"], "p_request_id": str(uuid.uuid4()),
                                                    "p_kind": kind, "p_description": "No quedó bien",
                                                    "p_visibility_acknowledged": True}, client)
        self.assertEqual(code, 200, incident)
        return appointment["id"], incident["id"]

    def settle(self, incident_id, token, outcome, refund=None, expected=8000, reason="Revisadas las pruebas"):
        return self.rpc("admin_settle_incident", {"p_incident_id": incident_id, "p_outcome": outcome,
                                                  "p_refund_cents": refund, "p_expected_pending_cents": expected,
                                                  "p_reason": reason}, token)

    def balance(self, appt):
        payment = self.get(f"appointment_payments?appointment_id=eq.{appt}", client)[0]
        movements = self.get(f"payment_movements?appointment_id=eq.{appt}&kind=neq.paid", client)
        return payment, sorted(m["kind"] for m in movements)

    def test_review_is_limited_and_only_for_administrators(self):
        appt, incident_id = self.incident(7)
        self.assertEqual(self.rpc("send_text", {"p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()),
                                                "p_body": "Conversación que no se aporta"}, client)[0], 200)
        for token in (None, client, professional, outsider):
            self.assertIn(self.rpc("admin_incidents", {"p_status": "open"}, token)[0], (401, 403))
            self.assertIn(self.settle(incident_id, token, "refund")[0], (401, 403))
            self.assertIn(self.rpc("admin_decide_incident_performance", {"p_incident_id": incident_id,
                          "p_decision": "performed", "p_reason": "x"}, token)[0], (401, 403))
        # Editing one's own metadata or the incident row does not grant anything.
        self.assertIn(api.request(f"/rest/v1/incidents?id=eq.{incident_id}", {"settlement": "refund"}, client, "PATCH")[0],
                      (401, 403, 404))
        code, review = self.rpc("admin_incidents", {"p_status": "open"}, administrator)
        self.assertEqual(code, 200)
        mine = next(r for r in review if r["id"] == incident_id)
        self.assertEqual((mine["conditions"]["amount_cents"], mine["payment"]["pending_cents"], mine["can_settle"],
                          [m["kind"] for m in mine["movements"]]), (8000, 8000, True, ["paid"]))
        text = json.dumps(review)
        for secret in ("Calle Reservada", "43.05", "Conversación que no se aporta"):
            self.assertNotIn(secret, text)
        self.assertEqual(self.get(f"chat_messages?conversation_id=eq.{conversation}", administrator), [])
        self.assertEqual(self.get(f"appointments?id=eq.{appt}&select=id", administrator), [])

    def test_two_administrators_settle_at_the_same_instant(self):
        for minute in (0, 20, 40):
            appt, incident_id = self.incident(8, minute)
            results = simultaneously(lambda: self.settle(incident_id, administrator, "refund"),
                                     lambda: self.settle(incident_id, second_administrator, "split", 3000))
            self.assertEqual(sorted(code for code, _ in results), [200, 409], results)
            winner = next(body for code, body in results if code == 200)
            payment, movements = self.balance(appt)
            if winner["settlement"] == "refund":
                self.assertEqual((payment["refunded_cents"], payment["delivered_cents"], movements), (8000, 0, ["refunded"]))
            else:
                self.assertEqual((payment["refunded_cents"], payment["delivered_cents"], movements),
                                 (3000, 5000, ["delivered", "refunded"]))
            self.assertEqual(payment["pending_cents"], 0)

    def test_settlement_and_confirmation_at_the_same_instant(self):
        for minute in (0, 20, 40):
            appt, incident_id = self.incident(9, minute)
            (settle_code, _), (confirm_code, _) = simultaneously(
                lambda: self.settle(incident_id, administrator, "deliver"),
                lambda: self.rpc("confirm_done", {"p_appointment_id": appt}, client))
            self.assertEqual(settle_code, 200)
            # Either the confirmation met the blocking incident (409) or the job already closed by administration.
            self.assertIn(confirm_code, (200, 409))
            payment, movements = self.balance(appt)
            self.assertEqual((payment["delivered_cents"], movements), (8000, ["delivered"]))
            row = self.get(f"appointments?select=stage,completion&id=eq.{appt}", client)[0]
            self.assertEqual((row["stage"], row["completion"]), ("completed", "administration"))

    def test_performance_is_separate_and_the_clock_does_not_close_twice(self):
        appt, incident_id = self.incident(10, done=True, kind="not_performed")
        # The deadline passes while the incident is open: the clock skips it.
        sql(f"""begin; set local session_replication_role = replica;
            update public.appointments set close_due_at = now() - interval '1 minute',
              reminder_due_at = now() - interval '2 minutes' where id = '{appt}'; commit;""")
        sql("select private.run_completion_clock();")
        self.assertEqual(self.get(f"appointments?select=stage&id=eq.{appt}", client)[0]["stage"], "done")
        code, decided = self.rpc("admin_decide_incident_performance", {"p_incident_id": incident_id,
                                 "p_decision": "not_performed", "p_reason": "Nadie acudió"}, administrator)
        self.assertEqual((code, decided["status"]), (200, "open"), "money still pending")
        sql("select private.run_completion_clock();")
        self.assertEqual(self.balance(appt)[0]["delivered_cents"], 0, "the clock does not deliver while unsettled")
        code, settled = self.settle(incident_id, administrator, "deliver", reason="Acuerdo entre las partes")
        self.assertEqual((code, settled["status"], settled["performance_decision"]), (200, "resolved", "not_performed"))
        sql("select private.run_completion_clock();")
        payment, movements = self.balance(appt)
        self.assertEqual((payment["delivered_cents"], movements), (8000, ["delivered"]))
        row = self.get(f"appointments?select=completion&id=eq.{appt}", client)[0]
        self.assertEqual(row["completion"], "administration")
        # What the participants read and are told.
        for token in (client, professional):
            incident = self.get(f"incidents?id=eq.{incident_id}", token)[0]
            self.assertEqual((incident["settlement"], incident["performance_decision"], incident["performance_reason"]),
                             ("deliver", "not_performed", "Nadie acudió"))
            code, notices = self.rpc("my_notices", {}, token)
            events = [n["payload"].get("event") for n in notices if n["payload"].get("appointment_id") == appt]
            self.assertEqual(events.count("incident_decision"), 2)
        self.assertEqual(self.settle(incident_id, administrator, "refund")[0], 409, "no second settlement")

    def test_panel_adapter_settles_and_decides(self):
        appt, incident_id = self.incident(11, done=False, kind="cancellation")
        env = dict(os.environ, PANEL_LOCAL_URL=config["API_URL"], PANEL_LOCAL_KEY=config["PUBLISHABLE_KEY"],
                   PANEL_ADMIN_EMAIL=emails[3], PANEL_ADMIN_PASSWORD=passwords[3],
                   PANEL_OTHER_EMAIL=emails[2], PANEL_OTHER_PASSWORD=passwords[2], PANEL_INCIDENT=incident_id)
        run = subprocess.run(["npx", "--no-install", "vitest", "run", "src/incidents.local.test.ts"],
                             cwd=ROOT / "panel", env=env, text=True, capture_output=True)
        self.assertEqual(run.returncode, 0, run.stdout[-2000:])
        self.assertIn("2 passed", run.stdout)
        payment, movements = self.balance(appt)
        self.assertEqual((payment["refunded_cents"], payment["delivered_cents"], movements),
                         (2500, 5500, ["delivered", "refunded"]))
        incident = self.get(f"incidents?id=eq.{incident_id}", client)[0]
        self.assertEqual((incident["status"], incident["performance_decision"]), ("resolved", "performed"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Incident resolution fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens, emails, passwords = [], [], [], []
    profile_id = str(uuid.uuid4())
    conversation = None
    result = None
    try:
        for role in ("client", "professional", "other", "admin", "admin2"):
            credentials = {"email": f"resolution-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local resolution fixture")
            users.append(user["id"])
            emails.append(credentials["email"])
            passwords.append(credentials["password"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local resolution fixture")
            tokens.append(session["access_token"])
        client, professional, outsider, administrator, second_administrator = tokens
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato resolución',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Resolution contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato resolución"}}]',array['es'],'000');
            insert into private.administrators(user_id) values ('{users[3]}'), ('{users[4]}');
            notify pgrst, 'reload schema';
            commit;""")
        conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, client)[1]["id"]
        code, agenda = api.request("/rest/v1/rpc/save_agenda", {"p_default_duration_minutes": 20,
                                   "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}, professional)
        if code != 200:
            raise SystemExit("Could not prepare the local agenda")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(IncidentResolutionContract))
    finally:
        # Only this invocation's random fixture UUIDs and synthetic concejo; never prepared data. The append-only
        # triggers are skipped for this cleanup transaction only.
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
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            delete from private.administrators where user_id::text in ('{"','".join(users[3:])}');
            delete from public.concejos where ine_code = '{CONCEJO}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result is not None and result.wasSuccessful() else 1)
