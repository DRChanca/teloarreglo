// Ordering by geographic distance (not road distance).
// Since ADR 0001 (25-11-2026) the Edge Function uses rankByDistance: every approved profile, the
// «Destacado» of the concejo's work zone first. searchByRings/featuredFirst (H3 rings over the
// covering profiles, from spikes/h3-busqueda) are kept with their tests for a larger catalogue.
import { cellToBoundary, cellToLatLng, gridDisk, gridRingUnsafe, latLngToCell } from "h3-js";

export const RESOLUTION = 6;
export const CELL_BUDGET = 1000;
/** Shown distances are rounded up to this step so a home cannot be triangulated. */
export const DISTANCE_STEP_M = 500;

export interface Candidate { profile_id: string; lat: number; lng: number; h3_cell: string }
/** Covering approved profiles in the given cells; null means all of them. */
export type Lookup = (cells: string[] | null) => Promise<Candidate[]>;
export interface Ranked { profile_id: string; distance_m: number }
export interface Search { results: Ranked[]; method: "h3" | "coverage"; rings: number; cells: number }

const EARTH_RADIUS_M = 6_371_008.8;

export function distanceM(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const rad = Math.PI / 180;
  const h = Math.sin(((bLat - aLat) * rad) / 2) ** 2 +
    Math.cos(aLat * rad) * Math.cos(bLat * rad) * Math.sin(((bLng - aLng) * rad) / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Public distance band: ceil to DISTANCE_STEP_M, never below one step. */
export function distanceBand(distance: number): number {
  return Math.max(DISTANCE_STEP_M, Math.ceil(distance / DISTANCE_STEP_M) * DISTANCE_STEP_M);
}

function compare(a: Ranked, b: Ranked): number {
  return a.distance_m - b.distance_m || (a.profile_id < b.profile_id ? -1 : a.profile_id > b.profile_id ? 1 : 0);
}

function ring(origin: string, k: number): string[] {
  if (k === 0) return [origin];
  try {
    return gridRingUnsafe(origin, k);
  } catch {
    const inner = new Set(gridDisk(origin, k - 1));
    return gridDisk(origin, k).filter((c) => !inner.has(c));
  }
}

/** Local hexagon geometry to bound the distance to any cell not explored yet. */
function localGeometry(origin: string) {
  const [oLat, oLng] = cellToLatLng(origin);
  let spacing = Infinity;
  let radius = 0;
  for (const cell of gridDisk(origin, 1)) {
    const [cLat, cLng] = cellToLatLng(cell);
    if (cell !== origin) spacing = Math.min(spacing, distanceM(oLat, oLng, cLat, cLng));
    for (const [vLat, vLng] of cellToBoundary(cell)) radius = Math.max(radius, distanceM(cLat, cLng, vLat, vLng));
  }
  return { spacing, radius };
}

/** Minimum distance from the job to any point in a cell at grid distance >= d. */
export function lowerBoundM(d: number, g: { spacing: number; radius: number }): number {
  return Math.max(0, d * (Math.sqrt(3) / 2) * g.spacing * 0.9 - 2 * g.radius * 1.05);
}

export async function searchByRings(
  job: { lat: number; lng: number }, total: number, lookup: Lookup, limit: number,
  cellBudget = CELL_BUDGET,
): Promise<Search> {
  const target = Math.min(limit, total);
  if (target <= 0) return { results: [], method: "h3", rings: 0, cells: 0 };
  const rank = (c: Candidate): Ranked => ({ profile_id: c.profile_id, distance_m: distanceM(job.lat, job.lng, c.lat, c.lng) });

  const origin = latLngToCell(job.lat, job.lng, RESOLUTION);
  const geometry = localGeometry(origin);
  const found = new Map<string, Ranked>();
  let cells = 0;
  let k = 0;
  // Rings are requested in growing batches (0, 1, 2–3, 4–7…) to limit round trips.
  for (let width = 1; ; width *= 2) {
    const batch: string[] = [];
    const last = k + width - 1;
    for (let r = k; r <= last; r++) batch.push(...ring(origin, r));
    if (cells + batch.length > cellBudget) break;
    cells += batch.length;
    for (const c of await lookup(batch)) found.set(c.profile_id, rank(c));
    k = last + 1;
    const ranked = [...found.values()].sort(compare);
    if (ranked.length === total) return { results: ranked.slice(0, target), method: "h3", rings: last, cells };
    // Strict: a tie with someone still unexplored keeps expanding.
    if (ranked.length >= target && ranked[target - 1].distance_m < lowerBoundM(k, geometry)) {
      return { results: ranked.slice(0, target), method: "h3", rings: last, cells };
    }
  }
  // Budget exhausted (few, distant candidates): order the full covering list, always correct.
  const all = (await lookup(null)).map(rank).sort(compare);
  return { results: all.slice(0, target), method: "coverage", rings: k - 1, cells };
}

export interface Placed extends Ranked { featured: boolean }

/**
 * «Destacado» (#33): subscribed profiles covering the concejo go first, closest first, then the rest by
 * proximity; each profile appears once and the page limit counts both. No fixed position is promised.
 */
export function featuredFirst(featured: Ranked[], ranked: Ranked[], limit: number): Placed[] {
  const ids = new Set(featured.map((f) => f.profile_id));
  return [
    ...[...featured].sort(compare).map((r) => ({ ...r, featured: true })),
    ...ranked.filter((r) => !ids.has(r.profile_id)).map((r) => ({ ...r, featured: false })),
  ].slice(0, limit);
}

/** `response_band`: «Tiempo de respuesta» (#80), computed by Postgres; passed through, never used to rank. */
export interface ZoneCandidate {
  profile_id: string; lat: number; lng: number; in_zone: boolean; featured: boolean; response_band: string | null;
}
export interface Listed extends Placed { in_zone: boolean; response_band: string | null }

/**
 * Single search (ADR 0001, 25-11-2026): «Destacado» goes first only when its work zone includes the
 * concejo, closest first; then every other approved profile by distance, in zone or not, without highlight.
 */
export function rankByDistance(from: { lat: number; lng: number }, candidates: ZoneCandidate[], limit: number): Listed[] {
  const placed = candidates.map((c) => ({
    profile_id: c.profile_id,
    distance_m: distanceM(from.lat, from.lng, c.lat, c.lng),
    featured: c.featured && c.in_zone,
    in_zone: c.in_zone,
    response_band: c.response_band,
  }));
  return [
    ...placed.filter((p) => p.featured).sort(compare),
    ...placed.filter((p) => !p.featured).sort(compare),
  ].slice(0, limit);
}
