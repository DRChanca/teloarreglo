// Push dispatch loop (#32): claim due deliveries, send each one, report its outcome.
// The database decides what is due, leases it and handles retries; this loop never touches domain state.

/** One leased delivery as returned by public.claim_push_deliveries. Identifiers and kinds only. */
export interface Claimed {
  delivery_id: string;
  token: string;
  recipient_id: string;
  notice_id: string;
  kind: string;
  event: string | null;
  conversation_id: string | null;
  appointment_id: string | null;
  created_at: string;
}

export type Outcome = "sent" | "retry" | "invalid_token" | "failed";

/** Transport boundary: FCM in production, a fake in tests. */
export interface PushSender {
  send(row: Claimed): Promise<{ outcome: Outcome; error: string | null }>;
}

export interface DeliveryStore {
  claim(limit: number): Promise<Claimed[]>;
  report(id: string, outcome: Outcome, error: string | null): Promise<void>;
}

export type Summary = { claimed: number } & Record<Outcome, number>;

export async function dispatch(store: DeliveryStore, sender: PushSender, limit: number): Promise<Summary> {
  const rows = await store.claim(limit);
  const summary: Summary = { claimed: rows.length, sent: 0, retry: 0, invalid_token: 0, failed: 0 };
  for (const row of rows) {
    let result: { outcome: Outcome; error: string | null };
    try {
      result = await sender.send(row);
    } catch (error) {
      // Unknown whether FCM got it: retry. Android replaces a repeated notice by its tag, so the person sees one.
      result = { outcome: "retry", error: error instanceof Error ? error.message : String(error) };
    }
    summary[result.outcome]++;
    await store.report(row.delivery_id, result.outcome, result.error?.slice(0, 500) ?? null);
  }
  return summary;
}
