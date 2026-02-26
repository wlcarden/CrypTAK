from __future__ import annotations

import asyncio
import logging
import time
from math import asin, cos, radians, sin, sqrt

import httpx

from src.config import GeoFilter

logger = logging.getLogger(__name__)

_BASE_URL = "https://nominatim.openstreetmap.org/search"
_CACHE_TTL = 86400  # 24 hours
_MIN_REQUEST_INTERVAL = 1.1  # seconds — slightly over 1/sec for safety


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Haversine distance in kilometers between two WGS84 points."""
    lat1, lon1, lat2, lon2 = map(radians, [lat1, lon1, lat2, lon2])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = sin(dlat / 2) ** 2 + cos(lat1) * cos(lat2) * sin(dlon / 2) ** 2
    return 6371 * 2 * asin(sqrt(a))


class Geocoder:
    """Forward geocoder backed by Nominatim with caching and rate limiting."""

    def __init__(self, user_agent: str, geo_filter: GeoFilter) -> None:
        self._user_agent = user_agent
        self._geo_filter = geo_filter
        self._cache: dict[str, tuple[float, float, float]] = {}  # key -> (lat, lon, timestamp)
        self._lock = asyncio.Lock()
        self._last_request = 0.0

    def _viewbox(self) -> str | None:
        """Build Nominatim viewbox parameter from geo_filter."""
        gf = self._geo_filter
        if gf.bbox:
            south, west, north, east = gf.bbox
            return f"{west},{north},{east},{south}"
        # Approximate viewbox from center + radius
        # 1 degree latitude ~ 111 km
        dlat = gf.radius_km / 111.0
        dlon = gf.radius_km / (111.0 * cos(radians(gf.center_lat)))
        west = gf.center_lon - dlon
        east = gf.center_lon + dlon
        south = gf.center_lat - dlat
        north = gf.center_lat + dlat
        return f"{west},{north},{east},{south}"

    def within_area(self, lat: float, lon: float) -> bool:
        """Check if a point falls within the configured geographic area."""
        gf = self._geo_filter
        if gf.bbox:
            south, west, north, east = gf.bbox
            return south <= lat <= north and west <= lon <= east
        return _haversine_km(gf.center_lat, gf.center_lon, lat, lon) <= gf.radius_km

    async def geocode(self, location_text: str) -> tuple[float, float] | None:
        """Forward-geocode a location string to (lat, lon).

        Returns None if geocoding fails or result is outside the geo filter.
        Respects Nominatim's 1 request/second rate limit.
        """
        if not location_text or not location_text.strip():
            return None

        cache_key = location_text.strip().lower()

        # Check cache
        if cache_key in self._cache:
            lat, lon, ts = self._cache[cache_key]
            if time.monotonic() - ts < _CACHE_TTL:
                if self.within_area(lat, lon):
                    return lat, lon
                return None
            del self._cache[cache_key]

        # Rate limit
        async with self._lock:
            elapsed = time.monotonic() - self._last_request
            if elapsed < _MIN_REQUEST_INTERVAL:
                await asyncio.sleep(_MIN_REQUEST_INTERVAL - elapsed)

            try:
                async with httpx.AsyncClient(
                    headers={"User-Agent": self._user_agent},
                    timeout=10.0,
                ) as client:
                    params: dict[str, str] = {
                        "q": location_text.strip(),
                        "format": "jsonv2",
                        "limit": "1",
                    }
                    viewbox = self._viewbox()
                    if viewbox:
                        params["viewbox"] = viewbox
                        params["bounded"] = "1"

                    resp = await client.get(_BASE_URL, params=params)
                    resp.raise_for_status()
                    results = resp.json()

                self._last_request = time.monotonic()

            except Exception:
                logger.exception("Nominatim geocoding failed for '%s'", location_text[:80])
                self._last_request = time.monotonic()
                return None

        if not results:
            logger.debug("Nominatim returned no results for '%s'", location_text[:80])
            return None

        lat = float(results[0]["lat"])
        lon = float(results[0]["lon"])

        # Cache the result
        self._cache[cache_key] = (lat, lon, time.monotonic())

        # Prune cache if it grows too large
        if len(self._cache) > 5000:
            cutoff = time.monotonic() - _CACHE_TTL
            self._cache = {
                k: v for k, v in self._cache.items() if v[2] > cutoff
            }

        if not self.within_area(lat, lon):
            logger.debug(
                "Geocoded '%s' to (%.4f, %.4f) — outside area, discarding",
                location_text[:80], lat, lon,
            )
            return None

        return lat, lon
