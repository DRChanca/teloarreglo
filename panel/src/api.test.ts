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

  it("reports a server refusal as permission denied", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ code: "42501", message: "permission_denied" }),
      { status: 403, headers: { "Content-Type": "application/json" } }));
    const api = createSupabaseAdminApi("", "", clientWith(fetchMock as unknown as typeof fetch));
    await expect(api.submissions("pending")).rejects.toEqual(new AdminError("permission_denied"));
  });
});
