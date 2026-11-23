// POST /functions/v1/deliver-push  (Authorization: Bearer <PUSH_DISPATCH_SECRET>)
// Called every minute by Supabase Cron (see docs/avisos-contrato.md). Sends due push deliveries through FCM.
// Without FCM_SERVICE_ACCOUNT it claims nothing: notices stay in the app and their deliveries expire after a day.
import { createClient } from "@supabase/supabase-js";
import { type Claimed, dispatch, type DeliveryStore, type Outcome } from "./dispatch.ts";
import { FcmSender } from "./fcm.ts";

function serverKey(): string {
  const secrets = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (secrets) {
    const keys = JSON.parse(secrets) as Record<string, string>;
    const key = keys.default ?? Object.values(keys)[0];
    if (key) return key;
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
}

function reply(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function sameSecret(given: string, expected: string): boolean {
  const a = new TextEncoder().encode(given), b = new TextEncoder().encode(expected);
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

const sender = FcmSender.fromEnv(Deno.env.get("FCM_SERVICE_ACCOUNT"));

Deno.serve(async (request) => {
  const secret = Deno.env.get("PUSH_DISPATCH_SECRET");
  if (!secret) return reply(503, { error: "not_configured" });
  if (request.method !== "POST" || !sameSecret(request.headers.get("Authorization") ?? "", `Bearer ${secret}`)) {
    return reply(401, { error: "unauthorized" });
  }
  if (!sender) return reply(200, { configured: false, claimed: 0 });
  const db = createClient(Deno.env.get("SUPABASE_URL")!, serverKey(), {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const store: DeliveryStore = {
    async claim(limit) {
      const { data, error } = await db.rpc("claim_push_deliveries", { p_limit: limit });
      if (error) throw error;
      return (data ?? []) as Claimed[];
    },
    async report(id: string, outcome: Outcome, message: string | null) {
      const { error } = await db.rpc("report_push_delivery",
        { p_delivery_id: id, p_outcome: outcome, p_error: message });
      if (error) throw error;
    },
  };
  try {
    const summary = await dispatch(store, sender, 100);
    console.log(JSON.stringify({ event: "deliver-push", ...summary }));
    return reply(200, { configured: true, ...summary });
  } catch (error) {
    console.error(JSON.stringify({ event: "deliver-push", error: String(error) }));
    return reply(500, { error: "dispatch_failed" });
  }
});
