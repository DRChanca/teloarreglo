// @vitest-environment node
// Real panel adapter against the LOCAL Supabase stack. Run only by
// scripts/contract_incident_resolution.py --local, which prepares disposable fixtures.
import { describe, expect, it } from "vitest";
import { AdminError, createSupabaseAdminApi } from "./api";

const env = process.env;
const enabled = !!env.PANEL_LOCAL_URL && /^http:\/\/(127\.0\.0\.1|localhost):54321$/.test(env.PANEL_LOCAL_URL);

describe.runIf(enabled)("incident review adapter against the local stack", () => {
  const api = () => createSupabaseAdminApi(env.PANEL_LOCAL_URL!, env.PANEL_LOCAL_KEY!);

  it("a normal account gets no incidents and cannot decide", async () => {
    const normal = api();
    expect((await normal.signIn(env.PANEL_OTHER_EMAIL!, env.PANEL_OTHER_PASSWORD!)).is_administrator).toBe(false);
    await expect(normal.incidents("open")).rejects.toEqual(new AdminError("permission_denied"));
    await expect(normal.settleIncident(env.PANEL_INCIDENT!, "refund", null, 8000, "x"))
      .rejects.toEqual(new AdminError("permission_denied"));
    await normal.signOut();
  });

  it("the administrator splits the pending balance and then decides performance", async () => {
    const admin = api();
    expect((await admin.signIn(env.PANEL_ADMIN_EMAIL!, env.PANEL_ADMIN_PASSWORD!)).is_administrator).toBe(true);
    const open = await admin.incidents("open");
    const incident = open.find((i) => i.id === env.PANEL_INCIDENT)!;
    expect(incident.can_settle).toBe(true);
    const pending = incident.payment!.pending_cents;
    await expect(admin.settleIncident(incident.id, "split", 2500, pending + 1, "Reparto"))
      .rejects.toEqual(new AdminError("balance_changed"));
    expect((await admin.settleIncident(incident.id, "split", 2500, pending, "Trabajo a medias")).status).toBe("open");
    expect((await admin.decidePerformance(incident.id, "performed", "Las fotos muestran parte del trabajo")).status)
      .toBe("resolved");
    await expect(admin.settleIncident(incident.id, "refund", null, 0, "Otra vez"))
      .rejects.toEqual(new AdminError("decision_already_recorded"));
    const resolved = (await admin.incidents("resolved")).find((i) => i.id === incident.id)!;
    expect(resolved.can_settle).toBe(false);
    expect(resolved.settled_by_email).toBe(env.PANEL_ADMIN_EMAIL);
    await admin.signOut();
  });
});
