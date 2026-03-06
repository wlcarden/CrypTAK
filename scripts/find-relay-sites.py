#!/usr/bin/env python3
"""find-relay-sites.py — Find optimal elevated positions for mesh relay nodes.

Analyzes SRTM terrain data to find high ground with line-of-sight back to the
base station. Outputs ranked candidates with elevation, distance, bearing, and
LOS status.

Usage:
    python3 scripts/find-relay-sites.py
    python3 scripts/find-relay-sites.py --radius 3.0 --top 20
    python3 scripts/find-relay-sites.py --kml relay-sites.kml

Requires: numpy (pip install numpy)
SRTM tile auto-downloads from AWS on first run.
"""

import argparse
import gzip
import math
import os
import struct
import sys
import urllib.request
from pathlib import Path

import numpy as np

# ---------------------------------------------------------------------------
# Configuration — edit these for your deployment
# ---------------------------------------------------------------------------
BASE_LAT = 38.84191827
BASE_LON = -77.29344941
BASE_ALT_ABOVE_GROUND = 8  # meters — antenna height on roof

SRTM_CACHE = Path.home() / ".cache" / "srtm"
SRTM_AWS_BASE = "https://elevation-tiles-prod.s3.amazonaws.com/skadi"

# Earth radius (meters)
R_EARTH = 6_371_000

# LoRa parameters for Fresnel zone calculation
FREQ_MHZ = 915
WAVELENGTH = 299_792_458 / (FREQ_MHZ * 1e6)

# ---------------------------------------------------------------------------
# SRTM tile loading
# ---------------------------------------------------------------------------
SRTM1_SAMPLES = 3601  # 1-arcsecond tile: 3601 x 3601


def tile_name(lat, lon):
    """SRTM tile name for a given lat/lon."""
    ns = "N" if lat >= 0 else "S"
    ew = "E" if lon >= 0 else "W"
    return f"{ns}{abs(int(math.floor(lat))):02d}{ew}{abs(int(math.floor(lon))):03d}"


def download_tile(name):
    """Download an SRTM1 tile from AWS if not cached."""
    SRTM_CACHE.mkdir(parents=True, exist_ok=True)
    hgt_path = SRTM_CACHE / f"{name}.hgt"

    if hgt_path.exists():
        return hgt_path

    ns_dir = name[:3]  # e.g. "N38"
    url = f"{SRTM_AWS_BASE}/{ns_dir}/{name}.hgt.gz"
    gz_path = hgt_path.with_suffix(".hgt.gz")

    print(f"Downloading SRTM tile {name} from AWS...", end=" ", flush=True)
    try:
        urllib.request.urlretrieve(url, gz_path)
        with gzip.open(gz_path, "rb") as f_in, open(hgt_path, "wb") as f_out:
            f_out.write(f_in.read())
        gz_path.unlink()
        print("done")
    except Exception as e:
        print(f"FAILED: {e}")
        sys.exit(1)

    return hgt_path


def load_tile(name):
    """Load an SRTM1 HGT file into a numpy array."""
    path = download_tile(name)
    size = path.stat().st_size
    samples = int(math.sqrt(size / 2))  # 2 bytes per sample (int16)
    data = np.fromfile(path, dtype=">i2").reshape((samples, samples))
    return data, samples


def get_elevation(tiles, lat, lon):
    """Get elevation for a lat/lon from loaded tiles."""
    name = tile_name(lat, lon)
    if name not in tiles:
        return None

    data, samples = tiles[name]
    # Row 0 = north edge of tile, last row = south edge
    lat_frac = lat - math.floor(lat)
    lon_frac = lon - math.floor(lon)
    row = int((1.0 - lat_frac) * (samples - 1))
    col = int(lon_frac * (samples - 1))
    row = max(0, min(samples - 1, row))
    col = max(0, min(samples - 1, col))
    val = int(data[row, col])
    return val if val != -32768 else None  # -32768 = void


# ---------------------------------------------------------------------------
# Geometry
# ---------------------------------------------------------------------------


def haversine(lat1, lon1, lat2, lon2):
    """Distance in meters between two lat/lon points."""
    lat1, lon1, lat2, lon2 = map(math.radians, [lat1, lon1, lat2, lon2])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return R_EARTH * 2 * math.asin(math.sqrt(a))


def bearing(lat1, lon1, lat2, lon2):
    """Bearing in degrees from point 1 to point 2."""
    lat1, lon1, lat2, lon2 = map(math.radians, [lat1, lon1, lat2, lon2])
    dlon = lon2 - lon1
    x = math.sin(dlon) * math.cos(lat2)
    y = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def bearing_to_compass(deg):
    """Convert bearing degrees to compass direction."""
    dirs = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
    return dirs[int((deg + 11.25) / 22.5) % 16]


def fresnel_radius(dist_a, dist_b, n=1):
    """Fresnel zone radius at a point between transmitter and receiver.

    dist_a, dist_b: distance from point to each end (meters)
    n: Fresnel zone number (1 = first zone)
    """
    total = dist_a + dist_b
    if total == 0:
        return 0
    return math.sqrt(n * WAVELENGTH * dist_a * dist_b / total)


# ---------------------------------------------------------------------------
# Line-of-sight analysis
# ---------------------------------------------------------------------------


def check_los(tiles, lat1, lon1, alt1, lat2, lon2, alt2, samples=100):
    """Check line-of-sight between two points accounting for terrain and Earth curvature.

    Returns (clear, worst_clearance_m, worst_point_idx)
    - clear: True if 60% of first Fresnel zone is unobstructed
    - worst_clearance_m: minimum clearance above terrain (negative = blocked)
    """
    total_dist = haversine(lat1, lon1, lat2, lon2)
    if total_dist < 1:
        return True, 999, 0

    worst_clearance = float("inf")
    worst_idx = 0

    for i in range(1, samples):
        frac = i / samples
        # Interpolate lat/lon along the path
        lat = lat1 + (lat2 - lat1) * frac
        lon = lon1 + (lon2 - lon1) * frac

        terrain_elev = get_elevation(tiles, lat, lon)
        if terrain_elev is None:
            continue

        # Distance from each endpoint
        d1 = total_dist * frac
        d2 = total_dist * (1 - frac)

        # LOS height at this point (linear interpolation + Earth curvature correction)
        # Earth curvature dip: h = d1*d2 / (2*R) — using 4/3 Earth radius for refraction
        curvature_dip = (d1 * d2) / (2 * R_EARTH * 4 / 3)
        los_height = alt1 + (alt2 - alt1) * frac - curvature_dip

        # First Fresnel zone radius at this point
        fz_radius = fresnel_radius(d1, d2) * 0.6  # 60% clearance = acceptable

        clearance = los_height - terrain_elev - fz_radius
        if clearance < worst_clearance:
            worst_clearance = clearance
            worst_idx = i

    clear = worst_clearance >= 0
    return clear, worst_clearance, worst_idx


# ---------------------------------------------------------------------------
# Peak finding
# ---------------------------------------------------------------------------


def find_peaks(tiles, center_lat, center_lon, radius_km, min_prominence=5):
    """Find local elevation peaks within radius of center point.

    Returns list of (lat, lon, elevation, prominence) sorted by elevation desc.
    """
    # Build a grid of elevations within the radius
    name = tile_name(center_lat, center_lon)
    if name not in tiles:
        print(f"ERROR: No SRTM tile for {center_lat}, {center_lon}")
        return []

    data, samples = tiles[name]

    # Convert radius to approximate lat/lon range
    lat_range = radius_km / 111.0  # ~111 km per degree latitude
    lon_range = radius_km / (111.0 * math.cos(math.radians(center_lat)))

    lat_min = center_lat - lat_range
    lat_max = center_lat + lat_range
    lon_min = center_lon - lon_range
    lon_max = center_lon + lon_range

    # Convert to tile row/col indices
    tile_lat = math.floor(center_lat)
    tile_lon = math.floor(center_lon)

    row_max = int((1.0 - (lat_min - tile_lat)) * (samples - 1))
    row_min = int((1.0 - (lat_max - tile_lat)) * (samples - 1))
    col_min = int((lon_min - tile_lon) * (samples - 1))
    col_max = int((lon_max - tile_lon) * (samples - 1))

    row_min = max(0, row_min)
    row_max = min(samples - 1, row_max)
    col_min = max(0, col_min)
    col_max = min(samples - 1, col_max)

    # Extract the sub-grid
    sub = data[row_min:row_max + 1, col_min:col_max + 1].astype(float)
    sub[sub == -32768] = np.nan

    # Find local maxima — a point higher than all 8 neighbors
    # Use a sliding window approach with padding
    peaks = []
    pad = 3  # check within 3-cell (~90m) neighborhood for prominence

    for r in range(pad, sub.shape[0] - pad):
        for c in range(pad, sub.shape[1] - pad):
            val = sub[r, c]
            if np.isnan(val):
                continue

            neighborhood = sub[r - pad:r + pad + 1, c - pad:c + pad + 1]
            neighbor_max = np.nanmax(neighborhood)

            # Must be the highest point in its neighborhood
            if val < neighbor_max:
                continue

            # Calculate prominence: difference from lowest point on ridge to next higher peak
            # Simplified: use difference from average of surrounding ring
            ring = np.concatenate([
                sub[r - pad, c - pad:c + pad + 1],
                sub[r + pad, c - pad:c + pad + 1],
                sub[r - pad:r + pad + 1, c - pad],
                sub[r - pad:r + pad + 1, c + pad],
            ])
            ring = ring[~np.isnan(ring)]
            if len(ring) == 0:
                continue
            prominence = val - np.mean(ring)

            if prominence < min_prominence:
                continue

            # Convert back to lat/lon
            actual_row = row_min + r
            actual_col = col_min + c
            lat = tile_lat + (1.0 - actual_row / (samples - 1))
            lon = tile_lon + actual_col / (samples - 1)

            # Check it's within circular radius
            dist = haversine(center_lat, center_lon, lat, lon) / 1000.0
            if dist > radius_km:
                continue

            peaks.append((lat, lon, float(val), float(prominence), dist))

    # Sort by elevation descending
    peaks.sort(key=lambda p: p[2], reverse=True)
    return peaks


# ---------------------------------------------------------------------------
# KML output
# ---------------------------------------------------------------------------


def write_kml(path, base_lat, base_lon, candidates):
    """Write candidate sites as a KML file for Google Earth / ATAK."""
    placemarks = []

    # Base station
    placemarks.append(f"""    <Placemark>
      <name>Base Station</name>
      <description>CrypTAK mesh base station</description>
      <Style><IconStyle><Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href></Icon></IconStyle></Style>
      <Point><coordinates>{base_lon},{base_lat},0</coordinates></Point>
    </Placemark>""")

    for i, c in enumerate(candidates):
        color = "grn" if c["los"] else "ylw"
        placemarks.append(f"""    <Placemark>
      <name>#{i+1} — {c['elev']}m ({c['compass']} {c['dist_km']:.1f}km)</name>
      <description>Elevation: {c['elev']}m | Prominence: {c['prominence']:.0f}m | Distance: {c['dist_km']:.2f}km | LOS: {'Clear' if c['los'] else 'Blocked'} ({c['clearance']:.0f}m)</description>
      <Style><IconStyle><Icon><href>http://maps.google.com/mapfiles/kml/paddle/{color}-circle.png</href></Icon></IconStyle></Style>
      <Point><coordinates>{c['lon']},{c['lat']},0</coordinates></Point>
    </Placemark>""")

        # LOS line to base
        placemarks.append(f"""    <Placemark>
      <name>LOS #{i+1}</name>
      <Style><LineStyle><color>{'ff00ff00' if c['los'] else 'ff0000ff'}</color><width>2</width></LineStyle></Style>
      <LineString><coordinates>{base_lon},{base_lat},0 {c['lon']},{c['lat']},0</coordinates></LineString>
    </Placemark>""")

    kml = f"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
  <name>CrypTAK Relay Site Candidates</name>
{''.join(placemarks)}
</Document>
</kml>"""

    Path(path).write_text(kml)
    print(f"\nKML written to {path}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description="Find optimal elevated positions for mesh relay nodes")
    parser.add_argument("--lat", type=float, default=BASE_LAT, help="Base station latitude")
    parser.add_argument("--lon", type=float, default=BASE_LON, help="Base station longitude")
    parser.add_argument("--radius", type=float, default=3.0, help="Search radius in km (default: 3)")
    parser.add_argument("--top", type=int, default=15, help="Number of candidates to show (default: 15)")
    parser.add_argument("--min-prominence", type=float, default=3, help="Minimum peak prominence in meters (default: 3)")
    parser.add_argument("--kml", type=str, default=None, help="Output KML file for Google Earth / ATAK")
    args = parser.parse_args()

    print(f"Base station: {args.lat:.6f}, {args.lon:.6f}")
    print(f"Search radius: {args.radius} km")
    print()

    # Load SRTM tiles (may need adjacent tiles near tile boundaries)
    needed = set()
    for dlat in [-args.radius / 111, 0, args.radius / 111]:
        for dlon in [-args.radius / 111, 0, args.radius / 111]:
            needed.add(tile_name(args.lat + dlat, args.lon + dlon))

    tiles = {}
    for name in needed:
        try:
            tiles[name] = load_tile(name)
        except Exception as e:
            print(f"Warning: Could not load tile {name}: {e}")

    # Get base elevation
    base_terrain = get_elevation(tiles, args.lat, args.lon)
    if base_terrain is None:
        print("ERROR: Could not get elevation for base station")
        sys.exit(1)

    base_total = base_terrain + BASE_ALT_ABOVE_GROUND
    print(f"Base terrain elevation: {base_terrain}m + {BASE_ALT_ABOVE_GROUND}m antenna = {base_total}m")
    print()

    # Find peaks
    print("Scanning for elevated positions...", flush=True)
    peaks = find_peaks(tiles, args.lat, args.lon, args.radius, args.min_prominence)
    print(f"Found {len(peaks)} candidate peaks")
    print()

    if not peaks:
        print("No significant peaks found. Try increasing --radius or decreasing --min-prominence.")
        return

    # Check line-of-sight for top candidates
    candidates = []
    check_count = min(len(peaks), args.top * 3)  # check more than we show

    print("Checking line-of-sight...", flush=True)
    for lat, lon, elev, prominence, dist_km in peaks[:check_count]:
        # Assume 3m antenna height at relay site
        relay_alt = elev + 3
        los_clear, clearance, _ = check_los(
            tiles, args.lat, args.lon, base_total, lat, lon, relay_alt
        )

        brg = bearing(args.lat, args.lon, lat, lon)
        compass = bearing_to_compass(brg)

        candidates.append({
            "lat": lat,
            "lon": lon,
            "elev": int(elev),
            "prominence": prominence,
            "dist_km": dist_km,
            "bearing": brg,
            "compass": compass,
            "los": los_clear,
            "clearance": clearance,
        })

    # Sort: LOS-clear first, then by elevation
    candidates.sort(key=lambda c: (-c["los"], -c["elev"]))
    candidates = candidates[:args.top]

    # Print results
    elev_gain_col = "Gain"
    print(f"{'#':<3} {'Lat':>10} {'Lon':>11} {'Elev':>5} {elev_gain_col:>5} {'Dist':>6} {'Dir':>4} {'LOS':>8} {'Clearance':>10}")
    print(f"{'─'*3} {'─'*10} {'─'*11} {'─'*5} {'─'*5} {'─'*6} {'─'*4} {'─'*8} {'─'*10}")

    for i, c in enumerate(candidates):
        gain = c["elev"] - base_terrain
        gain_str = f"+{gain}m" if gain > 0 else f"{gain}m"
        los_str = "Clear" if c["los"] else "BLOCKED"
        clr_str = f"{c['clearance']:.0f}m" if c["clearance"] < 900 else "n/a"

        print(
            f"{i+1:<3} {c['lat']:>10.6f} {c['lon']:>11.6f} "
            f"{c['elev']:>4}m {gain_str:>5} {c['dist_km']:>5.1f}km "
            f"{c['compass']:>4} {los_str:>8} {clr_str:>10}"
        )

    # Summary
    clear_count = sum(1 for c in candidates if c["los"])
    print(f"\n{clear_count}/{len(candidates)} candidates have clear line-of-sight to base")

    if args.kml:
        write_kml(args.kml, args.lat, args.lon, candidates)
    else:
        print("\nTip: add --kml relay-sites.kml to export for Google Earth / ATAK")


if __name__ == "__main__":
    main()
