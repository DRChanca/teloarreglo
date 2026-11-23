#!/usr/bin/env python3
"""Real Auth/PostgREST + Supabase Cron contract for the server clock of jobs marked «Realizado» (#27).

Two disposable trial accounts get the isolated short interval (reminder 2 min, close 5 min). A real pg_cron job runs
private.run_completion_clock() with no app involved; the apps' view is read back over HTTP afterwards. Also checks
races of the clock with the client's confirmation and with an incident, and recovery after an interruption.

By default the elapsed time is simulated by moving the server marks into the past and the job runs every 5 seconds.
With --real nothing is moved: the job runs every minute, as in the hosted project, and the run waits the real
5 minutes. Disposable fixtures on loopback ONLY; money is simulated."""
import argparse
import json
import os
import secrets
import subprocess
import time
import unittest
import uuid
from datetime import datetime, timedelta, timezone

from configuration import ROOT
from contract_identity import Api
from contract_chat import sql
from contract_agenda import simultaneously

CONCEJO = "33929"  # Synthetic code, never a real concejo.
DAY = datetime(2030, 1, 15, 7, tzinfo=timezone.utc)  # Tuesday; window 08:00–20:00 local is 07:00–19:00Z.
JOB = "completion-clock-contract"


def query(statement):
    """One value from trusted local SQL (the clock and the server log are not reachable by app accounts)."""
    return subprocess.run(
        ["docker", "exec", "-i", "supabase_db_teloarreglo", "psql", "-U", "postgres", "-d", "postgres",
         "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1"],
        input=statement, text=True, check=True, capture_output=True).stdout.strip()


def instant(text):
    """PostgREST timestamps (Python 3.9 needs six fractional digits)."""
    main, _, zone = text.partition("+")
    if "." in main:
        main, fraction = main.split(".")
        main = f"{main}.{fraction.ljust(6, '0')[:6]}"
    return datetime.fromisoformat(f"{main}+{zone or '00:00'}")


def run_clock():
    return json.loads(query("select private.run_completion_clock();"))


class ClockContract(unittest.TestCase):
    slot = 0

    def rpc(self, name, body, token):
        return api.request(f"/rest/v1/rpc/{name}", body, token)

    def done(self, mode="integrated", amount=8000):
        """A job started and marked done by the professional, as the apps do it."""
        start = DAY + timedelta(minutes=30 * ClockContract.slot)
        ClockContract.slot += 1
        start_text, end_text = start.isoformat(), (start + timedelta(minutes=30)).isoformat()
        code, appointment = self.rpc("request_appointment", {
            "p_conversation_id": conversation, "p_request_id": str(uuid.uuid4()), "p_problem": "Fuga",
            "p_start": start_text, "p_end": end_text, "p_address": "Calle Ficticia 1", "p_lat": 43.05,
            "p_lng": -5.95}, tokens[0])
        self.assertEqual(code, 200, appointment)
        code, proposal = self.rpc("propose_appointment", {
            "p_appointment_id": appointment["id"], "p_expected_version": 1, "p_request_id": str(uuid.uuid4()),
            "p_scope": "Cambiar sifón", "p_start": start_text, "p_duration_minutes": 30, "p_amount_cents": amount,
            "p_visit_fee_cents": None, "p_materials_included": True, "p_travel_included": True}, tokens[1])
        self.assertEqual(code, 200, proposal)
        if amount:
            code, body = self.rpc("confirm_proposal", {"p_proposal_id": proposal["id"],
                                                       "p_request_id": str(uuid.uuid4()), "p_mode": mode}, tokens[0])
        else:
            code, body = self.rpc("accept_proposal", {"p_proposal_id": proposal["id"]}, tokens[0])
        self.assertEqual(code, 200, body)
        self.assertEqual(self.rpc("start_work", {"p_appointment_id": appointment["id"]}, tokens[1])[0], 200)
        code, marks = self.rpc("mark_done", {"p_appointment_id": appointment["id"]}, tokens[1])
        self.assertEqual(code, 200, marks)
        return appointment["id"], marks

    def elapse(self, appointment_id, reminder_ago, close_ago):
        """Simulated time: moves this fixture's server marks into the past (trusted SQL, never an app parameter)."""
        sql(f"""update public.appointments set reminder_due_at = now() - interval '{reminder_ago} seconds',
            close_due_at = now() - interval '{close_ago} seconds' where id = '{appointment_id}';""")

    def state(self, appointment_id, token):
        code, rows = api.request(
            f"/rest/v1/appointments?select=stage,done_at,reminder_due_at,close_due_at,completed_at,completion,"
            f"appointment_payments(*)&id=eq.{appointment_id}", token=token, method="GET")
        self.assertEqual(code, 200, rows)
        return rows[0]

    def delivered(self, appointment_id):
        code, rows = api.request(f"/rest/v1/payment_movements?select=amount_cents&kind=eq.delivered"
                                 f"&appointment_id=eq.{appointment_id}", token=tokens[0], method="GET")
        self.assertEqual(code, 200, rows)
        return [r["amount_cents"] for r in rows]

    def notice_events(self, appointment_id, token):
        code, notices = self.rpc("my_notices", {}, token)
        self.assertEqual(code, 200, notices)
        return sorted(n["payload"].get("event") for n in notices if n["payload"].get("appointment_id") == appointment_id)

    def wait_for(self, condition, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = condition()
            if value:
                return value
            time.sleep(2)
        return condition()

    def test_1_cron_reminds_and_closes_with_the_apps_closed(self):
        self.assertEqual(query("select count(*) from pg_available_extensions where name = 'pg_cron';"), "1",
                         "Supabase Cron (pg_cron) is available")
        appt, marks = self.done()
        done_at = instant(marks["done_at"])
        self.assertEqual(instant(marks["reminder_due_at"]) - done_at, timedelta(minutes=2))
        self.assertEqual(instant(marks["close_due_at"]) - done_at, timedelta(minutes=5),
                         "trial accounts get the isolated 5-minute close")
        # From here on no app calls anything until the end: only the scheduled job acts.
        query(f"create extension if not exists pg_cron; select cron.schedule('{JOB}', "
              f"'{'* * * * *' if real else '5 seconds'}', 'select private.run_completion_clock()');")
        if real:
            time.sleep(100)
        else:
            self.elapse(appt, -60, -180)  # 1 min before the reminder.
            time.sleep(12)
        self.assertEqual(query(f"select count(*) from public.appointment_events where kind = 'reminder' "
                               f"and appointment_id = '{appt}';"), "0", "no reminder before it is due")
        if not real:
            self.elapse(appt, 1, -180)
        self.assertTrue(self.wait_for(lambda: query(
            f"select count(*) from public.appointment_events where kind = 'reminder' and appointment_id = '{appt}';")
            == "1", 120 if real else 30), "the job sends the reminder")
        if real:
            time.sleep(max(0, (instant(marks["close_due_at"]) - datetime.now(timezone.utc))
                           .total_seconds() - 15))
        self.assertEqual(query(f"select stage from public.appointments where id = '{appt}';"), "done",
                         "no close before it is due")
        if not real:
            self.elapse(appt, 181, 1)
        self.assertTrue(self.wait_for(lambda: query(
            f"select stage from public.appointments where id = '{appt}';") == "completed", 120 if real else 30),
            "the job closes the job")
        self.assertEqual(query(f"select bool_and(e.created_at >= a.reminder_due_at) and bool_and(a.completed_at >= "
                               f"a.close_due_at) from public.appointment_events e join public.appointments a on "
                               f"a.id = e.appointment_id where e.kind in ('reminder', 'completed') and a.id = '{appt}';"),
                         "t", "never before the deadlines")
        self.assertGreater(int(query(f"select count(*) from cron.job_run_details d join cron.job j using (jobid) "
                                     f"where j.jobname = '{JOB}' and d.status = 'succeeded';")), 0)
        query(f"select cron.unschedule('{JOB}');")

        # The apps open again and read the result over HTTP.
        for token in tokens[:2]:
            seen = self.state(appt, token)
            self.assertEqual((seen["stage"], seen["completion"]), ("completed", "no_answer"))
            payment = seen["appointment_payments"]
            self.assertEqual((payment["pending_cents"], payment["delivered_cents"]), (0, 8000))
        self.assertEqual(self.delivered(appt), [8000])
        client_events, professional_events = self.notice_events(appt, tokens[0]), self.notice_events(appt, tokens[1])
        self.assertEqual([client_events.count(e) for e in ("done", "reminder", "completed")], [1, 1, 1], client_events)
        self.assertEqual([professional_events.count(e) for e in ("reminder", "completed")], [0, 1], professional_events)
        self.assertEqual(query(f"select string_agg(action, ',' order by id) from private.completion_clock_log "
                               f"where appointment_id = '{appt}';"), "reminded,closed")

    def test_2_races_with_the_confirmation_and_with_an_incident(self):
        for _ in range(3):
            appt, _ = self.done()
            self.elapse(appt, 86400, 1)
            (code, body), clock = simultaneously(lambda: self.rpc("confirm_done", {"p_appointment_id": appt}, tokens[0]),
                                                 run_clock)
            self.assertEqual(code, 200, body)
            self.assertEqual(self.state(appt, tokens[0])["stage"], "completed")
            self.assertEqual(self.delivered(appt), [8000], "confirmation and clock deliver once")
            self.assertEqual(body["completion"], self.state(appt, tokens[0])["completion"])
        for _ in range(3):
            appt, _ = self.done()
            self.elapse(appt, 86400, 1)
            (code, incident), clock = simultaneously(lambda: self.rpc("open_incident", {
                "p_appointment_id": appt, "p_request_id": str(uuid.uuid4()), "p_kind": "quality",
                "p_description": "Sigue goteando", "p_visibility_acknowledged": True}, tokens[0]), run_clock)
            self.assertEqual(code, 200, incident)
            stage = self.state(appt, tokens[0])["stage"]
            if incident["blocks_delivery"]:
                self.assertEqual((stage, self.delivered(appt)), ("done", []), "an incident first blocks the close")
                self.assertEqual(run_clock()["closed"], 0)
                self.assertEqual(self.delivered(appt), [])
            else:
                self.assertEqual((stage, self.delivered(appt)), ("completed", [8000]),
                                 "an incident after the close is a communication")

    def test_3_recovery_after_an_interruption(self):
        integrated, _ = self.done()
        direct, _ = self.done("direct")
        free, _ = self.done(amount=0)
        for appt in (integrated, direct, free):
            self.elapse(appt, 3 * 86400, 2 * 86400)  # Missed runs for days.
        run_clock()
        run_clock()
        self.assertEqual(self.delivered(integrated), [8000])
        self.assertEqual(self.delivered(direct) + self.delivered(free), [], "direct and free move no money")
        for appt in (integrated, direct, free):
            self.assertEqual(self.state(appt, tokens[1])["completion"], "no_answer")
            self.assertNotIn("reminder", self.notice_events(appt, tokens[0]), "no late reminder")
            self.assertEqual(self.notice_events(appt, tokens[0]).count("completed"), 1)

    def test_4_apps_cannot_run_the_clock_or_join_the_trial(self):
        for token in (None, tokens[0]):
            self.assertIn(self.rpc("run_completion_clock", {}, token)[0], (401, 403, 404))
            self.assertIn(api.request("/rest/v1/completion_trial_accounts", {"user_id": users[0]}, token)[0],
                          (401, 403, 404))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", action="store_true", required=True)
    parser.add_argument("--real", action="store_true", help="wait the real 5-minute trial interval")
    real = parser.parse_args().real
    config = json.loads(subprocess.check_output(
        [os.environ.get("SUPABASE_BIN", "supabase"), "status", "--output", "json"], cwd=ROOT, text=True))
    if config["API_URL"] not in ("http://127.0.0.1:54321", "http://localhost:54321"):
        raise SystemExit("Clock fixtures require loopback; remote writes are not supported")
    api = Api(config["API_URL"], config["PUBLISHABLE_KEY"])
    admin = Api(config["API_URL"], config["SERVICE_ROLE_KEY"])
    users, tokens = [], []
    profile_id = str(uuid.uuid4())
    conversation = None
    result = None
    try:
        for role in ("client", "professional"):
            credentials = {"email": f"clock-{role}-{secrets.token_hex(6)}@example.org",
                           "password": secrets.token_urlsafe(32)}
            code, user = admin.request("/auth/v1/admin/users",
                                       dict(credentials, email_confirm=True), config["SERVICE_ROLE_KEY"])
            if code not in (200, 201):
                raise SystemExit("Could not create local clock fixture")
            users.append(user["id"])
            code, session = api.request("/auth/v1/token?grant_type=password", credentials)
            if code != 200:
                raise SystemExit("Could not sign in local clock fixture")
            tokens.append(session["access_token"])
        sql(f"""begin;
            insert into public.concejos(ine_code,name,boundary) values ('{CONCEJO}','Contrato reloj',
              'SRID=4326;MULTIPOLYGON(((-6.0 43.0,-5.9 43.0,-5.9 43.1,-6.0 43.1,-6.0 43.0)))'::extensions.geography);
            insert into private.professional_profiles(id,owner_id) values ('{profile_id}','{users[1]}');
            insert into public.approved_profiles(profile_id,name,description,trades,services,coverage,languages,phone)
            values ('{profile_id}','Clock contract','Fixture',array['plumbing'],array['Repair'],
              '[{{"code":"{CONCEJO}","name":"Contrato reloj"}}]',array['es'],'000');
            insert into private.completion_trial_accounts(user_id) values ('{users[0]}'), ('{users[1]}');
            notify pgrst, 'reload schema';
            commit;""")
        conversation = api.request("/rest/v1/rpc/open_conversation", {"p_profile_id": profile_id}, tokens[0])[1]["id"]
        agenda = {"p_default_duration_minutes": 30, "p_windows": [{"weekday": 2, "start": "08:00", "end": "20:00"}]}
        if api.request("/rest/v1/rpc/save_agenda", agenda, tokens[1])[0] != 200:
            raise SystemExit("Could not save the local agenda fixture")
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(ClockContract))
    finally:
        # Only this invocation's job, random fixture UUIDs and synthetic concejo; never prepared data.
        query(f"select cron.unschedule(jobid) from cron.job where jobname = '{JOB}';"
              if query("select count(*) from pg_extension where extname = 'pg_cron';") == "1" else "select 1;")
        mine = f"(select id from public.appointments where profile_id = '{profile_id}')"
        sql(f"""begin;
            set local session_replication_role = replica;
            delete from private.completion_clock_log where appointment_id in {mine};
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
            delete from public.conversations where profile_id = '{profile_id}';
            delete from public.approved_profiles where profile_id = '{profile_id}';
            delete from private.professional_profiles where id = '{profile_id}';
            delete from private.notices where recipient_id::text in ({", ".join(f"'{u}'" for u in users) or "''"});
            delete from private.completion_trial_accounts where user_id::text in
              ({", ".join(f"'{u}'" for u in users) or "''"});
            delete from public.concejos where ine_code = '{CONCEJO}';
            commit;""")
        for user_id in users:
            admin.request(f"/auth/v1/admin/users/{user_id}", token=config["SERVICE_ROLE_KEY"], method="DELETE")
    raise SystemExit(0 if result is not None and result.wasSuccessful() else 1)
