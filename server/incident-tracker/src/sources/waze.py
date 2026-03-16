from __future__ import annotations

import logging
import time
from datetime import datetime, timezone

import httpx

from src.config import GeoFilter, WazeConfig
from src.models import RawIncident
from src.sources.base import Source

logger = logging.getLogger(__name__)

_API_URL = "https://www.waze.com/live-map/api/georss"

_SUBTYPE_LABELS = {
    "POLICE_HIDING": "Police Hidden/Speed Trap",
    "POLICE_STANDING": "Police Checkpoint",
}
_DEFAULT_LABEL = "Police Spotted"

_BACKOFF_BASE = 1800  # 30 minutes initial backoff on 403
_BACKOFF_MAX = 7200  # 2 hours max backoff


class WazeSource(Source):
    """Fetches police sighting alerts from the Waze LiveMap API."""

    def __init__(self, config: WazeConfig, geo_filter: GeoFilter) -> None:
        self._config = config
        self._geo = geo_filter
        self._blocked_until = 0.0
        self._backoff = _BACKOFF_BASE

    @property
    def name(self) -> str:
        return "Waze"

    def _build_bbox(self) -> dict[str, float]:
        if self._geo.bbox:
            south, west, north, east = self._geo.bbox
            return {"bottom": south, "left": west, "top": north, "right": east}
        # Fall back to center +/- rough degree offset from radius
        # ~111 km per degree latitude, ~85 km per degree longitude at 39N
        dlat = self._geo.radius_km / 111.0
        dlon = self._geo.radius_km / 85.0
        return {
            "bottom": self._geo.center_lat - dlat,
            "top": self._geo.center_lat + dlat,
            "left": self._geo.center_lon - dlon,
            "right": self._geo.center_lon + dlon,
        }

    async def fetch(self) -> list[RawIncident]:
        if not self._config.enabled:
            return []

        now = time.monotonic()
        if now < self._blocked_until:
            remaining = int(self._blocked_until - now)
            logger.debug("Waze: rate-limited, skipping (%ds remaining)", remaining)
            return []

        bbox = self._build_bbox()
        params = {**bbox, "env": "na", "types": "alerts"}

        async with httpx.AsyncClient(
            timeout=15.0,
            headers={"User-Agent": "CrypTAK-IncidentTracker/1.0"},
        ) as client:
            try:
                resp = await client.get(_API_URL, params=params)
                if resp.status_code == 403:
                    self._blocked_until = now + self._backoff
                    logger.warning(
                        "Waze: 403 rate-limited, backing off %ds",
                        int(self._backoff),
                    )
                    self._backoff = min(self._backoff * 2, _BACKOFF_MAX)
                    return []
                resp.raise_for_status()
                data = resp.json()
            except httpx.HTTPStatusError:
                logger.exception("Waze API request failed")
                return []
            except Exception:
                logger.exception("Waze API request failed")
                return []

        # Successful response — reset backoff
        self._backoff = _BACKOFF_BASE

        incidents: list[RawIncident] = []
        for alert in data.get("alerts", []):
            if alert.get("type") != "POLICE":
                continue

            reliability = alert.get("reliability", 0)
            confidence = alert.get("confidence", 0)
            if reliability < self._config.min_reliability:
                continue
            if confidence < self._config.min_confidence:
                continue

            loc = alert.get("location", {})
            lon = loc.get("x")
            lat = loc.get("y")
            if lat is None or lon is None:
                continue

            subtype = alert.get("subtype", "")
            label = _SUBTYPE_LABELS.get(subtype, _DEFAULT_LABEL)
            street = alert.get("street", "")
            city = alert.get("city", "")

            if street and city:
                title = f"{label}: {street}, {city}"
            elif street:
                title = f"{label}: {street}"
            elif city:
                title = f"{label}: {city}"
            else:
                title = label

            thumbs = alert.get("nThumbsUp", 0)
            description = (
                f"{label} — reliability {reliability}/10, "
                f"{thumbs} confirmation{'s' if thumbs != 1 else ''}"
            )

            pub_ms = alert.get("pubMillis")
            published = datetime.now(timezone.utc)
            if pub_ms:
                published = datetime.fromtimestamp(pub_ms / 1000, tz=timezone.utc)

            category = (
                "police_sighting"
                if thumbs >= self._config.confirmed_threshold
                else "police_advisory"
            )

            incidents.append(
                RawIncident(
                    source_name="Waze",
                    title=title,
                    description=description,
                    url="",
                    published=published,
                    lat=float(lat),
                    lon=float(lon),
                    category=category,
                    severity="low",
                    structured=True,
                    source_id=f"waze-{alert.get('uuid', '')}",
                ),
            )

        logger.debug("Waze: %d police alerts (of %d total)",
                      len(incidents), len(data.get("alerts", [])))
        return incidents
