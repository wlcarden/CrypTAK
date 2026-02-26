from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from src.config import UsgsConfig
from src.models import RawIncident
from src.sources.base import Source

logger = logging.getLogger(__name__)

_BASE_URL = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary"

# Map config min_magnitude to the nearest available feed tier.
_MAGNITUDE_TIERS = [
    (4.5, "4.5"),
    (2.5, "2.5"),
    (1.0, "1.0"),
    (0.0, "all"),
]

_VALID_WINDOWS = {"hour", "day", "week", "month"}

_ALERT_SEVERITY: dict[str | None, str] = {
    "red": "critical",
    "orange": "high",
    "yellow": "medium",
    "green": "low",
    None: "low",
}


def _magnitude_feed_tier(min_mag: float) -> str:
    """Select the smallest feed tier that includes all events at min_mag."""
    for threshold, tier in _MAGNITUDE_TIERS:
        if min_mag >= threshold:
            return tier
    return "all"


class UsgsSource(Source):
    """Fetches earthquake data from USGS GeoJSON feeds."""

    def __init__(self, config: UsgsConfig) -> None:
        self._config = config

    @property
    def name(self) -> str:
        return "USGS"

    async def fetch(self) -> list[RawIncident]:
        if not self._config.enabled:
            return []

        window = self._config.feed_window
        if window not in _VALID_WINDOWS:
            logger.warning("Invalid USGS feed_window '%s', defaulting to 'day'", window)
            window = "day"

        tier = _magnitude_feed_tier(self._config.min_magnitude)
        url = f"{_BASE_URL}/{tier}_{window}.geojson"

        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.get(url)
                resp.raise_for_status()
                data = resp.json()
        except Exception:
            logger.exception("USGS feed fetch failed: %s", url)
            return []

        incidents: list[RawIncident] = []
        for feature in data.get("features", []):
            props = feature.get("properties", {})
            geometry = feature.get("geometry", {})
            coords = geometry.get("coordinates", [])

            mag = props.get("mag")
            if mag is None or mag < self._config.min_magnitude:
                continue

            if len(coords) < 2:
                continue

            # GeoJSON: [lon, lat, depth] — swap to (lat, lon)
            lon, lat = coords[0], coords[1]
            place = props.get("place", "Unknown location")
            title = props.get("title", f"M{mag} - {place}")
            event_id = feature.get("id", props.get("code", ""))
            alert_color = props.get("alert")
            severity = _ALERT_SEVERITY.get(alert_color, "low")

            # USGS time is epoch milliseconds
            time_ms = props.get("time")
            if time_ms:
                published = datetime.fromtimestamp(time_ms / 1000, tz=timezone.utc)
            else:
                published = datetime.now(timezone.utc)

            incidents.append(
                RawIncident(
                    source_name="USGS",
                    title=title,
                    description=f"Magnitude {mag} earthquake at {place}. "
                    f"Depth: {coords[2]:.1f} km." if len(coords) > 2
                    else f"Magnitude {mag} earthquake at {place}.",
                    url=props.get("url", ""),
                    published=published,
                    lat=lat,
                    lon=lon,
                    category="natural_disaster",
                    severity=severity,
                    structured=True,
                    source_id=event_id,
                )
            )

        logger.debug("USGS: %d earthquakes (>= M%.1f)", len(incidents), self._config.min_magnitude)
        return incidents
