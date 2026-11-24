// deno test supabase/functions/search-professionals/
import { featuredFirst } from "./search.ts";

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
