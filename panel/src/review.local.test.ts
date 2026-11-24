// @vitest-environment node
// Real panel adapter against the LOCAL Supabase stack. Run only by
// scripts/contract_profile_review.py --local, which prepares disposable fixtures.
import { describe, expect, it } from "vitest";
import { AdminError, createSupabaseAdminApi } from "./api";

const env = process.env;
const enabled = !!env.PANEL_LOCAL_URL && /^http:\/\/(127\.0\.0\.1|localhost):54321$/.test(env.PANEL_LOCAL_URL);

describe.runIf(enabled)("panel adapter against the local stack", () => {
  const api = () => createSupabaseAdminApi(env.PANEL_LOCAL_URL!, env.PANEL_LOCAL_KEY!);

  it("a normal account signs in but gets no submissions", async () => {
    const normal = api();
    expect((await normal.signIn(env.PANEL_OTHER_EMAIL!, env.PANEL_OTHER_PASSWORD!)).is_administrator).toBe(false);
    await expect(normal.submissions("pending")).rejects.toEqual(new AdminError("permission_denied"));
    await expect(normal.review(env.PANEL_SUBMISSION!, true, "x")).rejects.toEqual(new AdminError("permission_denied"));
  });

  it("the administrator decides on the pending submission", async () => {
    const admin = api();
    expect((await admin.signIn(env.PANEL_ADMIN_EMAIL!, env.PANEL_ADMIN_PASSWORD!)).is_administrator).toBe(true);
    const pending = await admin.submissions("pending");
    expect(pending.map((s) => s.submission_id)).toContain(env.PANEL_SUBMISSION);
    const approve = env.PANEL_DECISION === "approve";
    const result = await admin.review(env.PANEL_SUBMISSION!, approve, env.PANEL_REASON!);
    expect(result.status).toBe(approve ? "approved" : "rejected");
    await admin.signOut();
  });
});
