from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from src.config import NwsConfig
from src.models import RawIncident
from src.sources.base import Source

logger = logging.getLogger(__name__)

_BASE_URL = "https://api.weather.gov/alerts/active/zone"

# Map NWS event types to incident category names.
# Keys are lowercased NWS event strings; values are category names from config.
_EVENT_CATEGORY_MAP: dict[str, str] = {
    "tornado warning": "natural_disaster",
    "tornado watch": "natural_disaster",
    "severe thunderstorm warning": "natural_disaster",
    "severe thunderstorm watch": "natural_disaster",
    "flash flood warning": "natural_disaster",
    "flash flood watch": "natural_disaster",
    "flood warning": "natural_disaster",
    "flood watch": "natural_disaster",
    "winter storm warning": "natural_disaster",
    "winter storm watch": "natural_disaster",
    "blizzard warning": "natural_disaster",
    "ice storm warning": "natural_disaster",
    "hurricane warning": "natural_disaster",
    "hurricane watch": "natural_disaster",
    "tropical storm warning": "natural_disaster",
    "tsunami warning": "natural_disaster",
    "tsunami watch": "natural_disaster",
    "earthquake warning": "natural_disaster",
    "volcano warning": "natural_disaster",
    "avalanche warning": "natural_disaster",
    "red flag warning": "fire",
    "fire warning": "fire",
    "fire weather watch": "fire",
    "extreme fire danger": "fire",
    "hazardous materials warning": "hazmat",
    "civil danger warning": "local_le_tactical",
    "law enforcement warning": "local_le_tactical",
    "shelter in place warning": "local_le_tactical",
    "evacuation immediate": "natural_disaster",
    "child abduction emergency": "missing_person",
    "blue alert": "local_le_tactical",
}

_SEVERITY_MAP: dict[str, str] = {
    "extreme": "critical",
    "severe": "high",
    "moderate": "medium",
    "minor": "low",
    "unknown": "unknown",
}


def _polygon_centroid(
    coordinates: list[list[list[float]]],
) -> tuple[float, float] | None:
    """Compute centroid of a GeoJSON polygon (average of vertices).

    GeoJSON coordinates are [lon, lat]. Returns (lat, lon) for CoT,
    or None if the polygon is empty.
    """
    if not coordinates or not coordinates[0]:
        return None
    ring = coordinates[0]  # outer ring
    lons = [p[0] for p in ring]
    lats = [p[1] for p in ring]
    return sum(lats) / len(lats), sum(lons) / len(lons)


def _parse_timestamp(ts: str | None) -> datetime:
    """Parse ISO 8601 timestamp from NWS."""
    if ts:
        try:
            return datetime.fromisoformat(ts).astimezone(timezone.utc)
        except (ValueError, TypeError):
            pass
    return datetime.now(timezone.utc)


class NwsSource(Source):
    """Fetches active weather alerts from the NWS CAP API."""

    def __init__(self, config: NwsConfig, user_agent: str) -> None:
        self._config = config
        self._user_agent = user_agent

    @property
    def name(self) -> str:
        return "NWS"

    async def fetch(self) -> list[RawIncident]:
        if not self._config.enabled or not self._config.zone_codes:
            return []

        results: list[RawIncident] = []
        async with httpx.AsyncClient(
            headers={
                "User-Agent": self._user_agent,
                "Accept": "application/geo+json",
            },
            timeout=30.0,
        ) as client:
            for zone in self._config.zone_codes:
                try:
                    incidents = await self._fetch_zone(client, zone)
                    results.extend(incidents)
                    logger.debug("NWS zone %s: %d alerts", zone, len(incidents))
                except Exception:
                    logger.exception("NWS zone %s fetch failed", zone)
        return results

    async def _fetch_zone(
        self, client: httpx.AsyncClient, zone: str,
    ) -> list[RawIncident]:
        resp = await client.get(f"{_BASE_URL}/{zone}")
        resp.raise_for_status()
        data = resp.json()

        incidents: list[RawIncident] = []
        for feature in data.get("features", []):
            props = feature.get("properties", {})
            geometry = feature.get("geometry")

            alert_id = props.get("id", "")
            event = props.get("event", "Unknown")
            headline = props.get("headline", event)
            description = props.get("description", "")
            severity_raw = (props.get("severity") or "unknown").lower()
            severity = _SEVERITY_MAP.get(severity_raw, "unknown")
            published = _parse_timestamp(props.get("onset") or props.get("sent"))
            category = _EVENT_CATEGORY_MAP.get(event.lower(), "natural_disaster")

            lat, lon = None, None
            structured = False

            if geometry and geometry.get("type") == "Polygon":
                centroid = _polygon_centroid(geometry["coordinates"])
                if centroid:
                    lat, lon = centroid
                    structured = True
            elif geometry and geometry.get("type") == "Point":
                coords = geometry["coordinates"]
                lat, lon = coords[1], coords[0]
                structured = True
            # geometry: null → needs geocoding from areaDesc

            incidents.append(
                RawIncident(
                    source_name=f"NWS ({zone})",
                    title=headline,
                    description=description[:500],
                    url=f"https://alerts.weather.gov/search?id={alert_id}",
                    published=published,
                    lat=lat,
                    lon=lon,
                    category=category,
                    severity=severity,
                    structured=structured,
                    source_id=alert_id,
                )
            )
        return incidents
