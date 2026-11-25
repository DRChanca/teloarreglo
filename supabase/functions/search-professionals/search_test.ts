// deno test supabase/functions/search-professionals/
import { featuredFirst, rankByDistance } from "./search.ts";

Deno.test("single search: featured in zone first, then everyone by distance, featured out of zone not raised", () => {
  const from = { lat: 43.3614, lng: -5.8494 }; // Oviedo
  const c = (id: string, km: number, in_zone: boolean, featured = false) =>
    ({ profile_id: id, lat: from.lat + km / 111.2, lng: from.lng, in_zone, featured });
  const ranked = rankByDistance(from, [
    c("zone-far", 9, true),
    c("neighbour-near", 2, false),
    c("sub-zone", 6, true, true),
    c("sub-away", 1, false, true),
    c("zone-near", 3, true),
  ], 10);
  assertEquals(ranked.map((r) => [r.profile_id, r.featured, r.in_zone]), [
    ["sub-zone", true, true],
    ["sub-away", false, false],
    ["neighbour-near", false, false],
    ["zone-near", false, true],
    ["zone-far", false, true],
  ]);
});

Deno.test("single search: the limit counts featured and the rest together", () => {
  const from = { lat: 43.36, lng: -5.85 };
  const ranked = rankByDistance(from, [
    { profile_id: "a", lat: 43.37, lng: -5.85, in_zone: true, featured: true },
    { profile_id: "b", lat: 43.361, lng: -5.85, in_zone: false, featured: false },
    { profile_id: "c", lat: 43.40, lng: -5.85, in_zone: true, featured: false },
  ], 2);
  assertEquals(ranked.map((r) => r.profile_id), ["a", "b"]);
});

function assertEquals(actual: unknown, expected: unknown) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a !== e) throw new Error(`expected ${e}, got ${a}`);
}

Deno.test("featured profiles come first by distance, each profile once, the rest by proximity", () => {
  const ranked = [
    { profile_id: "near", distance_m: 100 },
    { profile_id: "sub-b", distance_m: 900 },
    { profile_id: "mid", distance_m: 1500 },
  ];
  const featured = [
    { profile_id: "sub-a", distance_m: 8000 },
    { profile_id: "sub-b", distance_m: 900 },
  ];
  assertEquals(featuredFirst(featured, ranked, 10), [
    { profile_id: "sub-b", distance_m: 900, featured: true },
    { profile_id: "sub-a", distance_m: 8000, featured: true },
    { profile_id: "near", distance_m: 100, featured: false },
    { profile_id: "mid", distance_m: 1500, featured: false },
  ]);
});

Deno.test("the page limit applies to featured and regular results together", () => {
  const ranked = [{ profile_id: "near", distance_m: 100 }, { profile_id: "mid", distance_m: 1500 }];
  const featured = [{ profile_id: "sub", distance_m: 5000 }];
  assertEquals(featuredFirst(featured, ranked, 2).map((r) => r.profile_id), ["sub", "near"]);
});

Deno.test("without subscribers the order is the plain proximity order", () => {
  const ranked = [{ profile_id: "near", distance_m: 100 }, { profile_id: "mid", distance_m: 1500 }];
  assertEquals(featuredFirst([], ranked, 20).map((r) => [r.profile_id, r.featured]), [["near", false], ["mid", false]]);
});
