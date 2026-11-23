// FCM HTTP v1 transport (#32). Free on the Spark plan: no Blaze, no Firebase database, storage or auth.
// The service account JSON lives only in the Edge Function secret FCM_SERVICE_ACCOUNT, never in the APK or Git.
import type { Claimed, Outcome, PushSender } from "./dispatch.ts";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

/**
 * Data-only, high-priority message: Android wakes the app's messaging service even when the app is closed, and
 * the app writes the localized text (ES/EN/FR/DE) itself. No body, reason, address or name leaves the server.
 */
export function fcmMessage(row: Claimed) {
  const data: Record<string, string> = {
    notice_id: row.notice_id, recipient_id: row.recipient_id, kind: row.kind, event: row.event ?? "",
    conversation_id: row.conversation_id ?? "", appointment_id: row.appointment_id ?? "",
  };
  return { message: { token: row.token, data, android: { priority: "high", ttl: "86400s" } } };
}

/** 404/UNREGISTERED and a rejected token mean the device must register again; 429, 5xx and auth are transient. */
export function classifyFcmResponse(status: number, body: string): Outcome {
  if (status >= 200 && status < 300) return "sent";
  if (status === 404 || body.includes("UNREGISTERED")) return "invalid_token";
  if (status === 400) return /registration token/i.test(body) ? "invalid_token" : "failed";
  if (status === 429 || status >= 500 || status === 401 || status === 403) return "retry";
  return "failed";
}

function base64url(bytes: Uint8Array | string): string {
  const raw = typeof bytes === "string" ? bytes : String.fromCharCode(...bytes);
  return btoa(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function signedAssertion(account: ServiceAccount, now: number): Promise<string> {
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(JSON.stringify({
    iss: account.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  }));
  const pem = account.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false,
    ["sign"]);
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key,
    new TextEncoder().encode(`${header}.${claims}`)));
  return `${header}.${claims}.${base64url(signature)}`;
}

export class FcmSender implements PushSender {
  private token: { value: string; expires: number } | null = null;

  constructor(private account: ServiceAccount, private http: typeof fetch = fetch) {}

  /** null when the secret is absent or unusable: the dispatcher then leaves deliveries for the in-app list. */
  static fromEnv(raw: string | undefined): FcmSender | null {
    if (!raw) return null;
    try {
      const account = JSON.parse(raw) as ServiceAccount;
      return account.project_id && account.client_email && account.private_key ? new FcmSender(account) : null;
    } catch {
      return null;
    }
  }

  private async accessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (this.token && this.token.expires - 60 > now) return this.token.value;
    const response = await this.http("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: await signedAssertion(this.account, now),
      }),
    });
    if (!response.ok) throw new Error(`oauth_${response.status}`);
    const body = await response.json() as { access_token: string; expires_in: number };
    this.token = { value: body.access_token, expires: now + body.expires_in };
    return body.access_token;
  }

  async send(row: Claimed): Promise<{ outcome: Outcome; error: string | null }> {
    const response = await this.http(
      `https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`, {
        method: "POST",
        headers: { "Authorization": `Bearer ${await this.accessToken()}`, "Content-Type": "application/json" },
        body: JSON.stringify(fcmMessage(row)),
      },
    );
    const body = await response.text();
    const outcome = classifyFcmResponse(response.status, body);
    return { outcome, error: outcome === "sent" ? null : `${response.status} ${body.slice(0, 200)}` };
  }
}
