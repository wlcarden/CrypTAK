from __future__ import annotations

import logging
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

import httpx

from src.config import GeoFilter, StopIceConfig
from src.models import RawIncident
from src.sources.base import Source

logger = logging.getLogger(__name__)

_BASE_URL = (
    "https://stopice.net/login/data/stats"
)

# Priority text → numeric rank for filtering
_PRIORITY_RANK = {
    "URGENT Confirmed ICE report": 3,
    "ICE Sighting": 2,
    "Unconfirmed Report": 1,
    "Prior incident reporting for record": 0,
    "Mutual Aid": 0,
}

# Config min_priority → minimum rank to include
_MIN_PRIORITY_RANK = {
    "confirmed": 3,
    "sighting": 2,
    "unconfirmed": 1,
}

# Priority → severity mapping
_PRIORITY_SEVERITY = {
    "URGENT Confirmed ICE report": "high",
    "ICE Sighting": "medium",
    "Unconfirmed Report": "low",
    "Prior incident reporting for record": "low",
    "Mutual Aid": "low",
}

# Priority → category mapping (activity-based threat posture)
# Active operations → federal_raid (hostile/red)
# Observed presence → ice_sighting (suspect/yellow eye)
# Unverified report → ice_advisory (suspect/yellow question)
_PRIORITY_CATEGORY = {
    "URGENT Confirmed ICE report": "federal_raid",
    "ICE Sighting": "ice_sighting",
    "Unconfirmed Report": "ice_advisory",
    "Prior incident reporting for record": "ice_advisory",
    "Mutual Aid": "ice_advisory",
}

# Month abbreviations for URL construction
_MONTHS = [
    "", "jan", "feb", "mar", "apr", "may", "jun",
    "jul", "aug", "sep", "oct", "nov", "dec",
]


def _build_xml_url() -> str:
    """Build the URL for the current month's StopICE data file."""
    now = datetime.now(timezone.utc)
    month = _MONTHS[now.month]
    return f"{_BASE_URL}/stopicenet_{month}_{now.year}_complete.xml"


def _parse_timestamp(ts: str) -> datetime:
    """Parse StopICE timestamp like 'feb 25, 2026 (19:22:04) PST'.

    Falls back to current time on parse failure.
    """
    try:
        # Strip timezone abbrev — StopICE always reports PST
        # but we store UTC. PST = UTC-8.
        clean = ts.strip()
        # "feb 25, 2026 (19:22:04) PST" → "feb 25, 2026 19:22:04"
        clean = clean.replace("(", "").replace(")", "")
        # Remove timezone suffix
        for tz in ("PST", "PDT", "EST", "EDT", "CST", "CDT", "MST", "MDT"):
            clean = clean.replace(tz, "").strip()
        dt = datetime.strptime(clean, "%b %d, %Y %H:%M:%S")
        # Assume PST (UTC-8) since StopICE is based in California
        from datetime import timedelta
        return dt.replace(tzinfo=timezone(timedelta(hours=-8)))
    except (ValueError, IndexError):
        return datetime.now(timezone.utc)


class StopIceSource(Source):
    """Fetches ICE sighting reports from StopICE.net public XML data feed.

    The XML file is compiled nightly and contains all reports for the current
    month. We track processed IDs to avoid reprocessing on each poll cycle.
    """

    def __init__(
        self,
        config: StopIceConfig,
        geo_filter: GeoFilter,
    ) -> None:
        self._config = config
        self._geo = geo_filter
        self._seen_ids: set[str] = set()

    @property
    def name(self) -> str:
        return "StopICE"

    def _within_area(self, lat: float, lon: float) -> bool:
        """Check if coordinates fall within the configured geo filter."""
        if self._geo.bbox:
            south, west, north, east = self._geo.bbox
            return south <= lat <= north and west <= lon <= east
        # Fallback: rough distance from center using degree approximation
        dlat = abs(lat - self._geo.center_lat) * 111.0
        dlon = abs(lon - self._geo.center_lon) * 85.0
        dist_km = (dlat ** 2 + dlon ** 2) ** 0.5
        return dist_km <= self._geo.radius_km

    async def fetch(self) -> list[RawIncident]:
        if not self._config.enabled:
            return []

        url = _build_xml_url()
        min_rank = _MIN_PRIORITY_RANK.get(self._config.min_priority, 1)

        async with httpx.AsyncClient(
            timeout=30.0,
            headers={"User-Agent": "CrypTAK-IncidentTracker/1.0"},
        ) as client:
            try:
                resp = await client.get(url)
                resp.raise_for_status()
                xml_text = resp.text
            except Exception:
                logger.exception("StopICE XML fetch failed: %s", url)
                return []

        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError:
            logger.exception("StopICE XML parse failed")
            return []

        incidents: list[RawIncident] = []
        total = 0
        filtered_priority = 0
        filtered_geo = 0

        for report in root.findall("report_data"):
            total += 1
            report_id = _text(report, "id")
            if not report_id:
                continue

            # Skip already-processed reports
            source_id = f"stopice-{report_id}"
            if source_id in self._seen_ids:
                continue

            priority = _text(report, "thispriority")
            rank = _PRIORITY_RANK.get(priority, 0)
            if rank < min_rank:
                filtered_priority += 1
                self._seen_ids.add(source_id)
                continue

            lat_str = _text(report, "lat")
            lon_str = _text(report, "long")
            if not lat_str or not lon_str:
                self._seen_ids.add(source_id)
                continue

            try:
                lat = float(lat_str)
                lon = float(lon_str)
            except ValueError:
                self._seen_ids.add(source_id)
                continue

            if not self._within_area(lat, lon):
                filtered_geo += 1
                self._seen_ids.add(source_id)
                continue

            location = _text(report, "location")
            comments = _text(report, "comments")
            timestamp_str = _text(report, "timestamp")
            report_url = _text(report, "url")

            published = _parse_timestamp(timestamp_str) if timestamp_str else datetime.now(timezone.utc)
            title = f"{priority}: {location}" if location else priority
            description = comments[:500] if comments else priority

            severity = _PRIORITY_SEVERITY.get(priority, "low")
            category = _PRIORITY_CATEGORY.get(priority, "federal_enforcement")

            self._seen_ids.add(source_id)
            incidents.append(
                RawIncident(
                    source_name="StopICE",
                    title=title,
                    description=description,
                    url=report_url or "",
                    published=published,
                    lat=lat,
                    lon=lon,
                    category=category,
                    severity=severity,
                    structured=True,
                    source_id=source_id,
                ),
            )

        logger.debug(
            "StopICE: %d new incidents from %d total "
            "(filtered: %d priority, %d geo, %d already seen)",
            len(incidents), total,
            filtered_priority, filtered_geo,
            len(self._seen_ids) - len(incidents) - filtered_priority - filtered_geo,
        )
        return incidents


def _text(elem: ET.Element, tag: str) -> str:
    """Extract text content from a child element, or empty string."""
    child = elem.find(tag)
    if child is not None and child.text:
        return child.text.strip()
    return ""
