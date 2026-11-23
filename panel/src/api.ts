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

/** Stable failure categories the UI translates. */
export type Failure =
  | "credentials" | "permission_denied" | "reason_required" | "review_already_recorded"
  | "profile_changed" | "submission_not_found" | "network" | "service";

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
}

const known: Failure[] = ["permission_denied", "reason_required", "review_already_recorded",
  "profile_changed", "submission_not_found"];

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
  };
  return api;
}
