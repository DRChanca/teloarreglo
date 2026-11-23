import { assertEquals, assertFalse, assertStringIncludes } from "jsr:@std/assert@1.0.13";
import { type Claimed, dispatch, type Outcome, type PushSender } from "./dispatch.ts";
import { classifyFcmResponse, FcmSender, fcmMessage } from "./fcm.ts";

const claimed = (id: string, extra: Partial<Claimed> = {}): Claimed => ({
  delivery_id: id, token: `token-${id}`, recipient_id: "user-1", notice_id: `notice-${id}`,
  kind: "message", event: null, conversation_id: "conv-1", appointment_id: null, created_at: "2026-11-23T10:00:00Z",
  ...extra,
});

function store(rows: Claimed[]) {
  const reports: [string, Outcome, string | null][] = [];
  let claims = 0;
  return {
    reports,
    get claims() { return claims; },
    async claim() { claims++; return claims === 1 ? rows : []; },
    async report(id: string, outcome: Outcome, error: string | null) { reports.push([id, outcome, error]); },
  };
}

Deno.test("every claimed delivery is sent once and reported with its outcome", async () => {
  const db = store([claimed("a"), claimed("b"), claimed("c")]);
  const outcomes: Record<string, Outcome> = { "token-a": "sent", "token-b": "invalid_token", "token-c": "retry" };
  const sender: PushSender = { async send(row) { return { outcome: outcomes[row.token], error: null }; } };
  const summary = await dispatch(db, sender, 50);
  assertEquals(db.reports.map(([id, o]) => [id, o]), [["a", "sent"], ["b", "invalid_token"], ["c", "retry"]]);
  assertEquals(summary, { claimed: 3, sent: 1, retry: 1, invalid_token: 1, failed: 0 });
});

Deno.test("a sender crash is reported as a retry and does not stop the other deliveries", async () => {
  const db = store([claimed("a"), claimed("b")]);
  const sender: PushSender = {
    async send(row) {
      if (row.delivery_id === "a") throw new Error("socket closed");
      return { outcome: "sent", error: null };
    },
  };
  await dispatch(db, sender, 50);
  assertEquals(db.reports, [["a", "retry", "socket closed"], ["b", "sent", null]]);
});

Deno.test("FCM answers are classified so only transient failures are retried", () => {
  assertEquals(classifyFcmResponse(200, ""), "sent");
  assertEquals(classifyFcmResponse(404, '{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}'),
    "invalid_token");
  assertEquals(classifyFcmResponse(400, '{"error":{"status":"INVALID_ARGUMENT","message":"The registration token is not a valid FCM registration token"}}'),
    "invalid_token");
  assertEquals(classifyFcmResponse(400, '{"error":{"status":"INVALID_ARGUMENT","message":"Invalid JSON payload"}}'), "failed");
  assertEquals(classifyFcmResponse(429, ""), "retry");
  assertEquals(classifyFcmResponse(503, ""), "retry");
  assertEquals(classifyFcmResponse(401, ""), "retry");
});

Deno.test("the FCM message is a high-priority data message without private content", () => {
  const message = fcmMessage(claimed("a", { kind: "appointment", event: "done", appointment_id: "appt-1" }));
  assertEquals(message.message.token, "token-a");
  assertEquals(message.message.android.priority, "high");
  assertEquals(message.message.data, {
    notice_id: "notice-a", recipient_id: "user-1", kind: "appointment", event: "done",
    conversation_id: "conv-1", appointment_id: "appt-1",
  });
  assertFalse("notification" in message.message, "the app builds the localized text itself");
  assertStringIncludes(JSON.stringify(message), "notice-a");
});

Deno.test("the FCM sender signs in with the service account once and sends to the project endpoint", async () => {
  const pair = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...der))}\n-----END PRIVATE KEY-----\n`;
  const calls: string[] = [];
  const http = (async (url: string | URL | Request, init?: RequestInit) => {
    calls.push(String(url));
    if (String(url).includes("oauth2")) {
      assertStringIncludes(String(init?.body), "jwt-bearer");
      return new Response(JSON.stringify({ access_token: "access", expires_in: 3600 }));
    }
    assertEquals((init?.headers as Record<string, string>).Authorization, "Bearer access");
    return String(init?.body).includes("token-b")
      ? new Response('{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}', { status: 404 })
      : new Response("{}");
  }) as typeof fetch;
  const sender = new FcmSender({ project_id: "demo", client_email: "push@demo.iam", private_key: pem }, http);
  assertEquals((await sender.send(claimed("a"))).outcome, "sent");
  assertEquals((await sender.send(claimed("b"))).outcome, "invalid_token");
  assertEquals(calls.filter((url) => url.includes("oauth2")).length, 1);
  assertEquals(calls.at(-1), "https://fcm.googleapis.com/v1/projects/demo/messages:send");
});

Deno.test("without a usable service account there is no sender", () => {
  assertEquals(FcmSender.fromEnv(undefined), null);
  assertEquals(FcmSender.fromEnv("not json"), null);
  assertEquals(FcmSender.fromEnv('{"project_id":"demo"}'), null);
});
