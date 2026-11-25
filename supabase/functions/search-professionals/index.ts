// POST /functions/v1/search-professionals
// { "lat": 43.36, "lng": -5.85, "trade": "plumbing", "ine_code"?: "33044", "limit"?: 20 }
// Public search (no sign-in needed). Never returns coordinates, cells or exact
// distances: only profile IDs in order, a rounded distance band and whether the
// result is «Destacado» (active simulated subscription, approved coverage of this concejo).
import { createClient } from "@supabase/supabase-js";
import { distanceBand, rankByDistance, type ZoneCandidate } from "./search.ts";

const TRADES = ["plumbing", "electrical", "carpentry", "painting", "masonry", "general", "cleaning"];
const MAX_LIMIT = 50;

/**
 * Projects with the new API keys expose SUPABASE_SECRET_KEYS (JSON object of
 * named sb_secret_ keys) and may have the legacy service_role JWT disabled.
 */
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
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return reply(405, { error: "method_not_allowed" });
  let input: Record<string, unknown>;
  try {
    input = await request.json();
  } catch {
    return reply(400, { error: "invalid_input" });
  }
  const lat = input.lat, lng = input.lng;
  const trade = input.trade ?? null;
  const ine = input.ine_code ?? null;
  const limit = input.limit ?? 20;
  if (typeof lat !== "number" || typeof lng !== "number" || !Number.isFinite(lat) || !Number.isFinite(lng) ||
      Math.abs(lat) > 90 || Math.abs(lng) > 180 ||
      (trade !== null && (typeof trade !== "string" || !TRADES.includes(trade))) ||
      (ine !== null && (typeof ine !== "string" || !/^33[0-9]{3}$/.test(ine))) ||
      typeof limit !== "number" || !Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    return reply(400, { error: "invalid_input" });
  }

  // Server key only inside the function: it may read private locations; clients never can.
  const db = createClient(Deno.env.get("SUPABASE_URL")!, serverKey(), {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const resolved = await db.rpc("resolve_concejo", { p_lat: lat, p_lng: lng });
    if (resolved.error) throw resolved.error;
    const options = (resolved.data ?? []) as { ine_code: string; name: string; distance_m: number }[];
    let concejo = options[0];
    if (ine !== null) {
      // Manual or confirmed choice: it must be a real concejo (the pin may be anywhere in it).
      const known = await db.from("concejos").select("ine_code, name").eq("ine_code", ine).maybeSingle();
      if (known.error) throw known.error;
      if (!known.data) return reply(400, { error: "unknown_concejo" });
      concejo = { ...known.data, distance_m: 0 };
    } else if (options.length === 0) {
      return reply(200, { status: "outside_area", results: [] });
    } else if (options.length > 1) {
      // Border or uncertain position: never guess the administrative division.
      return reply(200, {
        status: "confirm_concejo",
        options: options.map(({ ine_code, name }) => ({ ine_code, name })),
        results: [],
      });
    }

    // ADR 0001: every approved profile of the trade, by distance; the work zone only informs (in_zone)
    // and decides whether a «Destacado» goes first. Few profiles: a full sort is exact and cheap.
    const all = await db.rpc("search_all_candidates", { p_ine_code: concejo.ine_code, p_trade: trade });
    if (all.error) throw all.error;
    return reply(200, {
      status: "ok",
      concejo: { ine_code: concejo.ine_code, name: concejo.name },
      method: "distance",
      results: rankByDistance({ lat, lng }, all.data as ZoneCandidate[], limit).map((r) => ({
        profile_id: r.profile_id, distance_band_m: distanceBand(r.distance_m), featured: r.featured, in_zone: r.in_zone,
      })),
    });
  } catch (error) {
    // Server log only (code and message, never keys or coordinates); the client gets a
    // generic error. Never report success or an empty list on a server failure.
    const e = error as { code?: string; message?: string };
    console.error("search_failed", e?.code ?? "", e?.message ?? String(error));
    return reply(503, { error: "service_unavailable" });
  }
});
