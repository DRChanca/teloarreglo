#!/usr/bin/env python3
"""Download the official boundaries of the 78 Asturian concejos from IGN.

Source: IGN INSPIRE WFS "Unidades administrativas" (CC BY 4.0, attribution
required). Writes supabase/geo/concejos_asturias.geojson with WGS84 lon/lat
rounded to 6 decimals (~0.1 m), the INE code as text and source metadata.
Read-only against IGN; it never connects to Supabase.
"""
import argparse
import datetime
import json
import urllib.parse
import urllib.request

from configuration import ROOT

WFS = "https://www.ign.es/wfs-inspire/unidades-administrativas"
# nationalCode = 34 (Spain) + 03 (Principado de Asturias) + 33 (province) + INE municipality.
PREFIX = "34033333"
EXPECTED = 78
OUTPUT = ROOT / "supabase/geo/concejos_asturias.geojson"
FILTER = (
    '<fes:Filter xmlns:fes="http://www.opengis.net/fes/2.0" '
    'xmlns:au="http://inspire.ec.europa.eu/schemas/au/4.0">'
    '<fes:PropertyIsLike wildCard="*" singleChar="." escapeChar="!">'
    "<fes:ValueReference>au:nationalCode</fes:ValueReference>"
    f"<fes:Literal>{PREFIX}*</fes:Literal></fes:PropertyIsLike></fes:Filter>"
)


def rounded(value):
    if isinstance(value, list):
        return [rounded(v) for v in value]
    return round(value, 6)


def name_of(properties):
    return properties["name"]["GeographicalName"]["spelling"]["SpellingOfName"]["text"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default=str(OUTPUT))
    args = parser.parse_args()
    query = {
        "service": "WFS", "version": "2.0.0", "request": "GetFeature",
        "typeNames": "au:AdministrativeUnit", "outputFormat": "application/geo+json",
        "srsName": "urn:ogc:def:crs:EPSG::4326", "filter": FILTER,
    }
    url = f"{WFS}?{urllib.parse.urlencode(query)}"
    with urllib.request.urlopen(url, timeout=180) as response:
        data = json.load(response)

    features = []
    for feature in data["features"]:
        props = feature["properties"]
        level = props["nationalLevel"]["href"].rsplit("/", 1)[-1]
        code = props["nationalCode"]
        if level != "4thOrder" or not code.startswith(PREFIX):
            continue
        geometry = feature["geometry"]
        if geometry["type"] not in ("Polygon", "MultiPolygon"):
            raise SystemExit(f"Unexpected geometry {geometry['type']} for {code}")
        lon, lat = (geometry["coordinates"][0][0][0] if geometry["type"] == "MultiPolygon"
                    else geometry["coordinates"][0][0])
        # Guard against axis-order surprises: Asturias is lon -7.2..-4.5, lat 42.8..43.7.
        if not (-7.3 < lon < -4.4 and 42.8 < lat < 43.8):
            raise SystemExit(f"Coordinates out of Asturias for {code}: {lon}, {lat}")
        features.append({
            "type": "Feature",
            "properties": {"ine_code": code[-5:], "name": name_of(props), "ign_national_code": code},
            "geometry": {"type": geometry["type"], "coordinates": rounded(geometry["coordinates"])},
        })

    features.sort(key=lambda f: f["properties"]["ine_code"])
    codes = [f["properties"]["ine_code"] for f in features]
    if len(features) != EXPECTED or len(set(codes)) != EXPECTED:
        raise SystemExit(f"Expected {EXPECTED} distinct concejos, got {len(features)}")

    collection = {
        "type": "FeatureCollection",
        "metadata": {
            "source": "Instituto Geográfico Nacional (IGN), WFS INSPIRE Unidades administrativas",
            "url": WFS,
            "filter": f"au:nationalCode LIKE '{PREFIX}*' (4thOrder)",
            "license": "CC BY 4.0 — © Instituto Geográfico Nacional de España",
            "retrieved": datetime.date.today().isoformat(),
            "crs": "EPSG:4326 (lon, lat), coordinates rounded to 6 decimals",
        },
        "features": features,
    }
    with open(args.output, "w", encoding="utf-8", newline="\n") as out:
        json.dump(collection, out, ensure_ascii=False, separators=(",", ":"))
        out.write("\n")
    print(f"{len(features)} concejos -> {args.output}")


if __name__ == "__main__":
    main()
