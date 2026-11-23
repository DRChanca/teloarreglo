// Boundary between the panel UI and Supabase. Authorization is enforced by the RPCs
// (private.administrators, checked from the session); this module only maps results.
import { createClient, type SupabaseClient } from "@supabase/supabase-js";

export type SubmissionStatus = "pending" | "approved" | "rejected";

export interface ProfileContent {
  name: string;
  description: string;
  trades: string[];
  services: string[];
  coverage: { code: string; name: string }[];
  languages: string[];
  phone: string;
  images?: string[];
  prices?: { service: string; scope?: string; unit?: string; amount_cents?: number }[];
  business_address?: string | null;
}

export interface Submission {
  submission_id: string;
  profile_id: string;
  version: number;
  status: SubmissionStatus;
  submitted_at: string;
  published: boolean;
  latest: boolean;
  content: ProfileContent;
  /** Currently approved public version (#30); null while nothing is published. */
  public_content?: ProfileContent | null;
  decision: { approved: boolean; reason: string; decided_at: string; administrator_email: string } | null;
}

export interface Account {
  email: string;
  is_administrator: boolean;
}

export type IncidentStatus = "open" | "resolved";
export type Settlement = "deliver" | "refund" | "split";
export type PerformanceDecision = "performed" | "not_performed" | "missing_evidence";
export type Role = "client" | "professional";

/**
 * One incident as the review sees it (#31): the appointment's data, the accepted conditions, the simulated balance,
 * its movements and the evidence contributed to this incident. Never the exact place nor the rest of the chat.
 */
export interface IncidentReview {
  id: string;
  kind: "cancellation" | "quality" | "not_performed";
  description: string;
  performance: "acknowledged" | "disputed";
  status: IncidentStatus;
  created_at: string;
  resolved_at: string | null;
  opened_by_role: Role;
  opening: { appointment_stage: string; payment_mode: "integrated" | "direct" | null; pending_cents: number | null;
    delivered_cents: number | null; blocks_delivery: boolean };
  /** True while integrated money is pending and the incident blocks its delivery: only then are money actions shown. */
  can_settle: boolean;
  settlement: Settlement | null;
  settlement_refunded_cents: number | null;
  settlement_delivered_cents: number | null;
  settlement_reason: string | null;
  settled_at: string | null;
  settled_by_email: string | null;
  performance_decision: PerformanceDecision | null;
  performance_reason: string | null;
  performance_decided_at: string | null;
  performance_decided_by_email: string | null;
  appointment: { id: string; stage: string; problem: string; profile_name: string | null; requested_start: string;
    requested_end: string; started_at: string | null; done_at: string | null; close_due_at: string | null;
    completed_at: string | null; completion: string | null };
  conditions: { version: number; scope: string; start_at: string; end_at: string; duration_minutes: number;
    amount_cents: number; visit_fee_cents: number | null; materials_included: boolean; travel_included: boolean } | null;
  payment: { mode: "integrated" | "direct"; agreed_cents: number; paid_cents: number; pending_cents: number;
    refunded_cents: number; delivered_cents: number } | null;
  movements: { kind: "paid" | "refunded" | "delivered"; amount_cents: number; created_at: string;
    by: Role | "administration" | "system" }[];
  evidence: { id: string; kind: "message" | "note"; note: string | null; author_role: Role;
    message_author_role: Role | null; message_body: string | null; message_created_at: string | null;
    attachment_kind: "photo" | "video" | null; attachment_path: string | null; attachment_type: string | null;
    created_at: string }[];
}

/** Stable failure categories the UI translates. */
export type Failure =
  | "credentials" | "permission_denied" | "reason_required" | "review_already_recorded"
  | "profile_changed" | "submission_not_found"
  | "decision_already_recorded" | "nothing_pending" | "balance_changed" | "invalid_settlement" | "incident_closed"
  | "incident_not_found" | "invalid_decision"
  | "network" | "service";

export class AdminError extends Error {
  constructor(readonly failure: Failure) { super(failure); }
}

export interface AdminApi {
  /** Current account from the server, or null without a session. */
  account(): Promise<Account | null>;
  signIn(email: string, password: string): Promise<Account>;
  signOut(): Promise<void>;
  submissions(status: SubmissionStatus | "all"): Promise<Submission[]>;
  review(submissionId: string, approve: boolean, reason: string): Promise<Submission | { status: SubmissionStatus }>;
  incidents(status: IncidentStatus | "all"): Promise<IncidentReview[]>;
  /** refundCents only for "split"; expectedPendingCents is the balance shown, so a changed balance is not settled. */
  settleIncident(incidentId: string, outcome: Settlement, refundCents: number | null, expectedPendingCents: number,
    reason: string): Promise<{ status: IncidentStatus }>;
  decidePerformance(incidentId: string, decision: PerformanceDecision, reason: string): Promise<{ status: IncidentStatus }>;
  /** Short-lived URL of a contributed photo or video; Storage only signs files contributed to an incident. */
  evidenceUrl(path: string): Promise<string>;
}

const known: Failure[] = ["permission_denied", "reason_required", "review_already_recorded",
  "profile_changed", "submission_not_found", "decision_already_recorded", "nothing_pending", "balance_changed",
  "invalid_settlement", "incident_closed", "incident_not_found", "invalid_decision"];

export function failureOf(error: { code?: string; message?: string; status?: number } | null): Failure {
  if (!error) return "service";
  const message = error.message ?? "";
  const match = known.find((f) => message === f);
  if (match) return match;
  if (error.code === "42501") return "permission_denied";
  if (message.includes("Failed to fetch") || message.includes("NetworkError")) return "network";
  return "service";
}

export function createSupabaseAdminApi(url: string, publishableKey: string, client?: SupabaseClient): AdminApi {
  // The panel only ever holds a publishable key and the administrator's own session.
  const supabase = client ?? createClient(url, publishableKey, {
    auth: { persistSession: true, autoRefreshToken: true, storageKey: "teloarreglo-panel" },
  });
  async function rpc<T>(name: string, args: Record<string, unknown> = {}): Promise<T> {
    let result;
    try { result = await supabase.rpc(name, args); } catch { throw new AdminError("network"); }
    if (result.error) throw new AdminError(failureOf(result.error));
    return result.data as T;
  }
  const api: AdminApi = {
    async account() {
      const { data } = await supabase.auth.getSession();
      if (!data.session) return null;
      return rpc<Account>("current_account");
    },
    async signIn(email, password) {
      let result;
      try { result = await supabase.auth.signInWithPassword({ email, password }); } catch { throw new AdminError("network"); }
      if (result.error) throw new AdminError(result.error.status === 400 ? "credentials" : failureOf(result.error));
      return rpc<Account>("current_account");
    },
    async signOut() { await supabase.auth.signOut({ scope: "local" }); },
    submissions: (status) => rpc<Submission[]>("admin_profile_submissions", { p_status: status }),
    review: (id, approve, reason) =>
      rpc("review_professional_profile", { p_submission: id, p_approve: approve, p_reason: reason }),
    incidents: (status) => rpc<IncidentReview[]>("admin_incidents", { p_status: status }),
    settleIncident: (id, outcome, refund, expected, reason) => rpc("admin_settle_incident", {
      p_incident_id: id, p_outcome: outcome, p_refund_cents: outcome === "split" ? refund : null,
      p_expected_pending_cents: expected, p_reason: reason }),
    decidePerformance: (id, decision, reason) =>
      rpc("admin_decide_incident_performance", { p_incident_id: id, p_decision: decision, p_reason: reason }),
    async evidenceUrl(path) {
      let result;
      try { result = await supabase.storage.from("chat-attachments").createSignedUrl(path, 300); }
      catch { throw new AdminError("network"); }
      if (result.error || !result.data) throw new AdminError("permission_denied");
      return result.data.signedUrl;
    },
  };
  return api;
}
