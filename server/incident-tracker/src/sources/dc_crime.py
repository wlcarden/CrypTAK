from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

import httpx

from src.config import DcCrimeConfig
from src.models import RawIncident
from src.sources.base import Source

logger = logging.getLogger(__name__)

_BASE_URL = (
    "https://maps2.dcgis.dc.gov/dcgis/rest/services"
    "/FEEDS/MPD/FeatureServer/39/query"
)

_DEFAULT_CATEGORY = "property_crime"


class DcCrimeSource(Source):
    """Fetches recent crime incidents from DC Open Data (ArcGIS FeatureServer)."""

    def __init__(self, config: DcCrimeConfig) -> None:
        self._config = config

    @property
    def name(self) -> str:
        return "DC Crime"

    async def fetch(self) -> list[RawIncident]:
        if not self._config.enabled:
            return []

        cutoff = datetime.now(timezone.utc) - timedelta(
            minutes=self._config.lookback_minutes,
        )
        cutoff_str = cutoff.strftime("%Y-%m-%d %H:%M:%S")

        params = {
            "where": f"REPORT_DAT >= timestamp '{cutoff_str}'",
            "outFields": "CCN,OFFENSE,METHOD,BLOCK,REPORT_DAT,LATITUDE,LONGITUDE",
            "f": "geojson",
            "resultRecordCount": 500,
        }

        async with httpx.AsyncClient(timeout=30.0) as client:
            try:
                resp = await client.get(_BASE_URL, params=params)
                resp.raise_for_status()
                data = resp.json()
            except Exception:
                logger.exception("DC Crime API request failed")
                return []

        incidents: list[RawIncident] = []
        for feature in data.get("features", []):
            props = feature.get("properties", {})
            offense = props.get("OFFENSE", "")

            if self._config.offenses and offense not in self._config.offenses:
                continue

            ccn = str(props.get("CCN", ""))
            method = props.get("METHOD", "")
            block = props.get("BLOCK", "")
            report_ms = props.get("REPORT_DAT")
            lat = props.get("LATITUDE")
            lon = props.get("LONGITUDE")

            if lat is None or lon is None:
                geometry = feature.get("geometry")
                if geometry and geometry.get("type") == "Point":
                    coords = geometry["coordinates"]
                    lon, lat = coords[0], coords[1]

            if lat is None or lon is None:
                continue

            published = datetime.now(timezone.utc)
            if report_ms:
                published = datetime.fromtimestamp(
                    report_ms / 1000, tz=timezone.utc,
                )

            category = self._config.offense_category_map.get(
                offense, _DEFAULT_CATEGORY,
            )

            title = f"{offense}: {block}" if block else offense
            description = f"{offense} ({method})" if method else offense

            incidents.append(
                RawIncident(
                    source_name="DC Crime",
                    title=title,
                    description=description,
                    url=f"https://crimecards.dc.gov/a/search?ccn={ccn}",
                    published=published,
                    lat=float(lat),
                    lon=float(lon),
                    category=category,
                    severity=_offense_severity(offense),
                    structured=True,
                    source_id=f"dc-crime-{ccn}",
                ),
            )

        logger.debug("DC Crime: %d incidents fetched", len(incidents))
        return incidents


def _offense_severity(offense: str) -> str:
    """Map DC offense types to severity levels."""
    high = {"HOMICIDE", "ASSAULT W/DANGEROUS WEAPON", "SEX ABUSE"}
    medium = {"ROBBERY", "ARSON"}
    if offense in high:
        return "high"
    if offense in medium:
        return "medium"
    return "low"
