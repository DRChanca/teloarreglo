#!/usr/bin/env python3
"""Real Auth/PostgREST contract for reviews (#28): the client of a performed appointment publishes one review after the
public-content notice, retries safely and cannot create a second one, even sending two at the same instant; a quality
complaint does not block it, a dispute about whether it happened waits for the decision on performance; a free
appointment closed by the clock is reviewable later and never reviewed automatically. Anyone reads stars, comment and
date of a profile and its aggregate, never the appointment, the author, the address or the chat. Disposable fixtures on
loopback ONLY; money is simulated."""
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

CONCEJO = "33928"  # Synthetic code, never a real concejo.
DAY = "2030-01-15"  # Tuesday; Madrid is UTC+1 then, so the window 08:00–20:00 local is 07:00–19:00Z.


class ReviewContract(unittest.TestCase):
    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def get(self, path, token):
        code, rows = api.request("/rest/v1/" + path, token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows

    def done(self, hour, mode="integrated", amount=8000):
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
        self.assertEqual(self.rpc("mark_done", {"p_appointment_id": appointment["id"]}, professional)[0], 200)
        return appointment["id"]

    def publish(self, appointment_id, token, stars=5, comment="Muy bien", key=None, acknowledged=True):
        return self.rpc("publish_review", {"p_appointment_id": appointment_id, "p_request_id": key or str(uuid.uuid4()),
                                           "p_stars": stars, "p_comment": comment,
                                           "p_privacy_acknowledged": acknowledged}, token)

    def state(self, appointment_id, token=None):
        code, rows = self.rpc("review_states", {"p_conversation_id": conversation}, token or client)
        self.assertEqual(code, 200, rows)
        return next(row for row in rows if row["appointment_id"] == appointment_id)

    def test_1_confirmed_appointment_one_review_and_public_profile(self):
        appt = self.done(7)
        self.assertEqual(self.state(appt)["state"], "awaiting_confirmation")
        self.assertEqual(self.publish(appt, client)[0], 409)  # awaiting_confirmation
        self.assertEqual(self.rpc("confirm_done", {"p_appointment_id": appt}, client)[0], 200)
        # Without the notice about private data nothing is published; nobody but the client publishes.
        self.assertEqual(self.publish(appt, client, acknowledged=False)[0], 400)
        for token in (None, outsider, professional):
            self.assertIn(self.publish(appt, token)[0], (401, 403))
        # Two different attempts at the same instant: exactly one review.
        keys = [str(uuid.uuid4()), str(uuid.uuid4())]
        results = simultaneously(lambda: self.publish(appt, client, 4, "Rápido y limpio", keys[0]),
                                 lambda: self.publish(appt, client, 4, "Rápido y limpio", keys[1]))
        self.assertEqual(sorted(code for code, _ in results), [200, 409], results)
        winner = next(index for index, (code, _) in enumerate(results) if code == 200)
        review = results[winner][1]
        # A lost answer is recovered by retrying with the same key; another key never creates a second review.
        self.assertEqual(self.publish(appt, client, 4, "Rápido y limpio", keys[winner]), (200, review))
        self.assertEqual(self.publish(appt, client, 4, "Rápido y limpio", keys[1 - winner])[0], 409)  # review_exists
        self.assertEqual(self.publish(appt, client, 1, "Cambiada", keys[winner])[0], 400)  # retry_conflict
        self.assertEqual(self.state(appt)["state"], "published")
        self.assertEqual(self.state(appt, professional)["review"]["id"], review["id"])
        self.assertEqual(self.rpc("review_states", {"p_conversation_id": conversation}, outsider)[1], [])

        # Public profile: anyone reads stars, comment and date, and the aggregate; nothing else.
        rows = self.get(f"reviews?profile_id=eq.{profile_id}&order=created_at.desc", None)
        self.assertEqual(set(rows[0]), {"id", "profile_id", "stars", "comment", "created_at"})
        self.assertIn("Rápido y limpio", [row["comment"] for row in rows])
        profile = self.get(f"approved_profiles?profile_id=eq.{profile_id}&select=rating,review_count", None)[0]
        self.assertEqual(profile["review_count"], len(rows))
        self.assertIn(api.request("/rest/v1/review_sources", token=client, method="GET")[0], (401, 403, 404))
        for token in (client, professional):
            self.assertIn(api.request(f"/rest/v1/reviews?id=eq.{review['id']}", {"stars": 1}, token, "PATCH")[0],
                          (401, 403))
            self.assertIn(api.request(f"/rest/v1/reviews?id=eq.{review['id']}", token=token, method="DELETE")[0],
                          (401, 403))

    def test_2_quality_complaint_reviews_and_dispute_waits(self):
        quality, disputed = self.done(9), self.done(11, "direct")
        self.assertEqual(self.rpc("open_incident", {"p_appointment_id": quality, "p_request_id": str(uuid.uuid4()),
                                                    "p_kind": "quality", "p_description": "Gotea un poco",
                                                    "p_visibility_acknowledged": True}, client)[0], 200)
        self.assertEqual(self.publish(quality, client, 2, "Gotea un poco")[0], 200)
        self.assertEqual(self.rpc("open_incident", {"p_appointment_id": disputed, "p_request_id": str(uuid.uuid4()),
                                                    "p_kind": "not_performed", "p_description": "No vino",
                                                    "p_visibility_acknowledged": True}, professional)[0], 200)
        self.assertEqual(self.publish(disputed, client)[0], 409)
        self.assertEqual(self.state(disputed)["state"], "performance_disputed")
        # Stand-in for the administrative decision (#31) on performance, kept apart from the money.
        sql(f"update public.incidents set status = 'resolved', performance_decision = 'performed' "
            f"where appointment_id = '{disputed}';")
        self.assertEqual(self.state(disputed)["state"], "enabled")
        self.assertEqual(self.publish(disputed, client, 3, "Vino tarde")[0], 200)

    def test_3_free_appointment_closed_by_the_clock(self):
        appt = self.done(13, amount=0)
        sql(f"""update public.appointments set close_due_at = now() - interval '1 minute' where id = '{appt}';
            select private.complete_appointment('{appt}', null, 'no_answer');""")
        self.assertEqual(self.state(appt)["state"], "enabled")
        self.assertIsNone(self.state(appt)["review"], "the silent close never writes a review")
        self.assertEqual(self.publish(appt, client, 5, "Sin coste y bien")[0], 200)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", required=True)
    parser.parse_args()
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Review fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    conversation = None
    result = None
    try:
        for role in ("client", "professional", "other"):
            credentials = {"email": f"review-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local review fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local review fixture")
            tokens.append(session["access_token"])
        client, professional, outsider = tokens
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato reseñas',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Review contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato reseñas"}}]',array['es'],'000');
            notify pgrst, 'reload schema';
            commit;""")
        conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, client)[1]["id"]
        code, agenda = api.request("/rest/v1/rpc/save_agenda", {"p_default_duration_minutes": 60,
                                   "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}, professional)
        if code != 200:
            raise SystemExit("Could not prepare the local agenda")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ReviewContract))
    finally:
        # Only this invocation's random fixture UUIDs and synthetic concejo; never prepared data. The locking
        # triggers are skipped for this cleanup transaction only.
        mine = f"(select id from public.appointments where profile_id = '{profile_id}')"
        conversations = f"(select id from public.conversations where profile_id = '{profile_id}')"
        sql(f"""begin;
            set local session_replication_role = replica;
            delete from private.review_sources where appointment_id in {mine};
            delete from public.reviews where profile_id = '{profile_id}';
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
            delete from public.concejos where ine_code = '{CONCEJO}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result is not None and result.wasSuccessful() else 1)
