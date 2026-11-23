#!/usr/bin/env node
// Print a one-time, reviewable SQL load for the search: the 78 IGN concejos and the
// registered locations of the 18 prepared profiles. Never connects to a database.
//
//   cd scripts/search-data && npm ci
//   node prepare_search_data.mjs [--part concejos|locations] > /RUTA_LOCAL/carga.sql
//
// Locations are fictitious: a deterministic point within ~800 m of the centre of the
// town of each profile's fictitious business address (public city centres, no homes),
// always inside that concejo. Only the server reads them; the app never receives them.
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { latLngToCell } from "h3-js";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const RESOLUTION = 6; // Must match supabase/functions/search-professionals/search.ts
const NAMESPACE = "c1771400-0000-4000-8000-000000000014"; // scripts/prepare_catalogue.py
const TRADES = ["plumbing", "electrical", "carpentry", "painting", "masonry", "general"];
// Town centres of the fictitious business addresses (squares/streets, not people).
const TOWNS = {
  Oviedo: { ine: "33044", lat: 43.3614, lng: -5.8494 },
  "Gijón": { ine: "33024", lat: 43.5357, lng: -5.6615 },
  "Avilés": { ine: "33004", lat: 43.5547, lng: -5.9248 },
};
const JITTER_M = 800;

const read = (path) => JSON.parse(readFileSync(ROOT + path, "utf8"));
const literal = (v) => `'${String(v).replaceAll("'", "''")}'`;

/** RFC 4122 v5, identical to Python's uuid.uuid5 used by prepare_catalogue.py. */
function uuid5(namespace, name) {
  const ns = Buffer.from(namespace.replaceAll("-", ""), "hex");
  const hash = createHash("sha1").update(Buffer.concat([ns, Buffer.from(name, "utf8")])).digest();
  hash[6] = (hash[6] & 0x0f) | 0x50;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const h = hash.subarray(0, 16).toString("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Deterministic PRNG (mulberry32) seeded from the profile name. */
function random(seedText) {
  let a = createHash("sha1").update(seedText).digest().readUInt32LE(0);
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Ray casting on [lon, lat] rings; holes subtract. */
function inRing(lng, lat, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i], [xj, yj] = ring[j];
    if ((yi > lat) !== (yj > lat) && lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
function inGeometry(lng, lat, geometry) {
  const polygons = geometry.type === "MultiPolygon" ? geometry.coordinates : [geometry.coordinates];
  return polygons.some(([outer, ...holes]) => inRing(lng, lat, outer) && !holes.some((h) => inRing(lng, lat, h)));
}

function profiles() {
  const rows = read("supabase/catalogue/profiles.json");
  const details = new Map(read("supabase/catalogue/details.json").map((d) => [d.name, d]));
  if (rows.length !== 18 || !TRADES.every((t) => rows.filter((p) => p.trade === t).length === 3)) {
    throw new Error("Expected the 18 prepared profiles, three per trade");
  }
  return rows.map((p, i) => ({ id: uuid5(NAMESPACE, String(i)), ...p, ...details.get(p.name) }));
}

function locate(profile, concejos) {
  const town = profile.business_address?.split(",").at(-1).trim();
  const anchor = TOWNS[town];
  if (!anchor) throw new Error(`No town centre for ${profile.name}: ${profile.business_address}`);
  const boundary = concejos.get(anchor.ine).geometry;
  const next = random(profile.name);
  for (let attempt = 0; attempt < 1000; attempt++) {
    const r = JITTER_M * Math.sqrt(next()), angle = 2 * Math.PI * next();
    const lat = anchor.lat + (r * Math.cos(angle)) / 111_320;
    const lng = anchor.lng + (r * Math.sin(angle)) / (111_320 * Math.cos((anchor.lat * Math.PI) / 180));
    if (inGeometry(lng, lat, boundary)) {
      const [la, ln] = [Number(lat.toFixed(6)), Number(lng.toFixed(6))];
      return { lat: la, lng: ln, ine: anchor.ine, cell: latLngToCell(la, ln, RESOLUTION) };
    }
  }
  throw new Error(`No point inside ${anchor.ine} for ${profile.name}`);
}

function concejosSql(features) {
  const rows = features.map(({ properties: p, geometry }) =>
    `(${literal(p.ine_code)}, ${literal(p.name)}, ` +
    `extensions.st_multi(extensions.st_geomfromgeojson(${literal(JSON.stringify(geometry))}))::extensions.geography)`);
  return [
    "-- 78 concejos de Asturias. Fuente: IGN, WFS INSPIRE Unidades administrativas.",
    "-- CC BY 4.0 — © Instituto Geográfico Nacional de España. Ver supabase/geo/.",
    "insert into public.concejos(ine_code, name, boundary) values",
    rows.join(",\n") + ";",
    "do $$ begin",
    "  if (select count(*) from public.concejos where ine_code like '33%') <> 78 then",
    "    raise exception 'expected exactly 78 concejos';",
    "  end if;",
    "end $$;",
  ].join("\n");
}

function locationsSql(located) {
  const values = located.map(({ p, l }) =>
    `(${literal(p.id)}::uuid, ${literal(p.name)}, ${l.lat}, ${l.lng}, ${literal(l.cell)}, ${literal(l.ine)})`);
  return [
    "-- Ubicaciones registradas (ficticias) de los 18 perfiles preparados. Privadas: solo servidor.",
    "create temporary table search_locations_preparation (",
    "  profile_id uuid, name text, lat double precision, lng double precision, h3_cell text, ine_code text",
    ") on commit drop;",
    "insert into pg_temp.search_locations_preparation values",
    values.join(",\n") + ";",
    "do $$ begin",
    "  -- Same IDs and names as the published catalogue, or nothing is written.",
    "  if exists (select 1 from pg_temp.search_locations_preparation v",
    "             left join public.approved_profiles p on p.profile_id = v.profile_id",
    "             where p.name is distinct from v.name) then",
    "    raise exception 'catalogue_mismatch: run only against the prepared catalogue';",
    "  end if;",
    "  -- Every point inside the concejo of its fictitious business address.",
    "  if exists (select 1 from pg_temp.search_locations_preparation v",
    "             join public.concejos c on c.ine_code = v.ine_code",
    "             where not extensions.st_covers(c.boundary,",
    "               extensions.st_setsrid(extensions.st_makepoint(v.lng, v.lat), 4326)::extensions.geography)) then",
    "    raise exception 'location_outside_concejo';",
    "  end if;",
    "end $$;",
    "-- A second run conflicts on profile_id and aborts: never overwrites a location.",
    "insert into private.professional_locations(profile_id, location, h3_cell)",
    "select profile_id, extensions.st_setsrid(extensions.st_makepoint(lng, lat), 4326)::extensions.geography, h3_cell",
    "from pg_temp.search_locations_preparation;",
  ].join("\n");
}

const part = process.argv.includes("--part") ? process.argv[process.argv.indexOf("--part") + 1] : "all";
if (!["all", "concejos", "locations"].includes(part)) throw new Error("--part concejos|locations");
const features = read("supabase/geo/concejos_asturias.geojson").features;
const concejos = new Map(features.map((f) => [f.properties.ine_code, f]));
const located = profiles().map((p) => ({ p, l: locate(p, concejos) }));

const HEADER = "-- Generado por scripts/search-data/prepare_search_data.mjs. Revisar antes de ejecutar.";

// The Supabase SQL Editor rejects large queries ("Query is too large"). With
// --out-dir, concejos are split into files of at most --max-kb (each its own
// transaction; a repeated file conflicts and aborts), plus a final check file.
const outDir = process.argv.includes("--out-dir") ? process.argv[process.argv.indexOf("--out-dir") + 1] : null;
if (outDir) {
  const { mkdirSync, writeFileSync } = await import("node:fs");
  const maxBytes = 1024 * Number(process.argv.includes("--max-kb") ? process.argv[process.argv.indexOf("--max-kb") + 1] : 200);
  mkdirSync(outDir, { recursive: true });
  const files = [];
  let batch = [];
  const flush = () => { if (batch.length) files.push(batch); batch = []; };
  for (const f of features) {
    const size = (b) => Buffer.byteLength(concejosSql(b), "utf8");
    if (batch.length && size([...batch, f]) > maxBytes) flush();
    batch.push(f);
    if (size(batch) > maxBytes) throw new Error(`${f.properties.name} alone exceeds --max-kb`);
  }
  flush();
  const pad = (n) => String(n).padStart(2, "0");
  files.forEach((b, i) => {
    const sql = concejosSql(b).replace(/\ndo \$\$ begin[\s\S]*end \$\$;$/, ""); // count check only at the end
    writeFileSync(`${outDir}/concejos-${pad(i + 1)}.sql`, [HEADER, `-- Parte ${i + 1} de ${files.length}.`, "begin;", sql, "commit;", ""].join("\n"));
  });
  writeFileSync(`${outDir}/concejos-${pad(files.length + 1)}-comprobar.sql`, [HEADER,
    "do $$ begin",
    "  if (select count(*) from public.concejos where ine_code like '33%') <> 78 then",
    "    raise exception 'expected exactly 78 concejos';",
    "  end if;",
    "end $$;", ""].join("\n"));
  writeFileSync(`${outDir}/ubicaciones.sql`, [HEADER, "begin;", locationsSql(located), "commit;", ""].join("\n"));
  process.stderr.write(`${files.length} archivos de concejos + comprobación + ubicaciones en ${outDir}\n`);
} else {
  const out = [HEADER, "begin;"];
  if (part !== "locations") out.push(concejosSql(features));
  if (part !== "concejos") out.push(locationsSql(located));
  out.push("commit;");
  process.stdout.write(out.join("\n") + "\n");
}
