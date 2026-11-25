import { describe, expect, it, vi } from "vitest";
import { createClient } from "@supabase/supabase-js";
import { AdminError, createSupabaseAdminApi } from "./api";

function clientWith(fetchImpl: typeof fetch) {
  return createClient("https://panel.example.org", "sb_publishable_test", {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { fetch: fetchImpl },
  });
}

describe("Supabase adapter", () => {
  it("sends the decision for the exact submission to the protected RPC", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ status: "rejected" }),
      { status: 200, headers: { "Content-Type": "application/json" } }));
    const api = createSupabaseAdminApi("", "", clientWith(fetchMock as unknown as typeof fetch));
    await api.review("s1", false, "Falta la cobertura");
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(new URL(url).pathname).toBe("/rest/v1/rpc/review_professional_profile");
    expect(JSON.parse(init.body as string)).toEqual({ p_submission: "s1", p_approve: false, p_reason: "Falta la cobertura" });
  });

  it("reads the work zone changes of a profile through its protected RPC", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify([]),
      { status: 200, headers: { "Content-Type": "application/json" } }));
    const api = createSupabaseAdminApi("", "", clientWith(fetchMock as unknown as typeof fetch));
    expect(await api.workZoneChanges("p1")).toEqual([]);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(new URL(url).pathname).toBe("/rest/v1/rpc/admin_work_zone_changes");
    expect(JSON.parse(init.body as string)).toEqual({ p_profile: "p1" });
  });

  it("reports a server refusal as permission denied", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ code: "42501", message: "permission_denied" }),
      { status: 403, headers: { "Content-Type": "application/json" } }));
    const api = createSupabaseAdminApi("", "", clientWith(fetchMock as unknown as typeof fetch));
    await expect(api.submissions("pending")).rejects.toEqual(new AdminError("permission_denied"));
  });

  it("settles an incident with the reviewed balance and sends no refund amount unless it is a split", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ status: "open" }),
      { status: 200, headers: { "Content-Type": "application/json" } }));
    const api = createSupabaseAdminApi("", "", clientWith(fetchMock as unknown as typeof fetch));
    await api.settleIncident("i1", "refund", 1200, 8000, "Sin trabajo");
    await api.settleIncident("i1", "split", 3000, 8000, "Parcial");
    const bodies = fetchMock.mock.calls.map((c) => JSON.parse((c as unknown as [string, RequestInit])[1].body as string));
    expect(new URL((fetchMock.mock.calls[0] as unknown as [string])[0]).pathname).toBe("/rest/v1/rpc/admin_settle_incident");
    expect(bodies[0]).toEqual({ p_incident_id: "i1", p_outcome: "refund", p_refund_cents: null,
      p_expected_pending_cents: 8000, p_reason: "Sin trabajo" });
    expect(bodies[1].p_refund_cents).toBe(3000);
  });

  it("decides on performance through its own RPC", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ status: "resolved" }),
      { status: 200, headers: { "Content-Type": "application/json" } }));
    const api = createSupabaseAdminApi("", "", clientWith(fetchMock as unknown as typeof fetch));
    await api.decidePerformance("i1", "missing_evidence", "No hay fotos");
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(new URL(url).pathname).toBe("/rest/v1/rpc/admin_decide_incident_performance");
    expect(JSON.parse(init.body as string)).toEqual({ p_incident_id: "i1", p_decision: "missing_evidence", p_reason: "No hay fotos" });
  });

  it("maps an executed decision and a changed balance to stable failures", async () => {
    for (const message of ["decision_already_recorded", "balance_changed", "nothing_pending"]) {
      const fetchMock = vi.fn(async () => new Response(JSON.stringify({ code: "PT409", message }),
        { status: 409, headers: { "Content-Type": "application/json" } }));
      const api = createSupabaseAdminApi("", "", clientWith(fetchMock as unknown as typeof fetch));
      await expect(api.settleIncident("i1", "deliver", null, 8000, "x")).rejects.toEqual(new AdminError(message as never));
    }
  });
});
