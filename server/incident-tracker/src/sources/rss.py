from __future__ import annotations

import asyncio
import logging
import re
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

import feedparser
import httpx

from src.config import RssFeed
from src.models import RawIncident
from src.sources.base import Source

logger = logging.getLogger(__name__)

_HTML_TAG_RE = re.compile(r"<[^>]+>")
_P_TAG_RE = re.compile(r"<p[^>]*>(.*?)</p>", re.IGNORECASE | re.DOTALL)
_FETCH_TIMEOUT = 30  # seconds
_MIN_DESCRIPTION_LEN = 80  # fetch article body if description shorter than this


def _strip_html(text: str) -> str:
    """Remove HTML tags from text."""
    return _HTML_TAG_RE.sub("", text).strip()


def _extract_article_text(html: str, max_chars: int = 1500) -> str:
    """Extract readable text from <p> tags in an HTML page."""
    paragraphs = _P_TAG_RE.findall(html)
    text = " ".join(_strip_html(p) for p in paragraphs if len(_strip_html(p)) > 30)
    return text[:max_chars]


def _parse_date(entry: dict) -> datetime:
    """Extract publication date from a feedparser entry."""
    for field in ("published_parsed", "updated_parsed"):
        parsed = entry.get(field)
        if parsed:
            try:
                from calendar import timegm
                return datetime.fromtimestamp(timegm(parsed), tz=timezone.utc)
            except (ValueError, OverflowError):
                continue

    for field in ("published", "updated"):
        raw = entry.get(field)
        if raw:
            try:
                return parsedate_to_datetime(raw).astimezone(timezone.utc)
            except (ValueError, TypeError):
                continue

    return datetime.now(timezone.utc)


def _extract_geo(entry: dict) -> tuple[float | None, float | None]:
    """Extract coordinates from GeoRSS-tagged entries.

    feedparser parses georss:point and georss:where into entry['where']
    as a GeoJSON-style dict: {'type': 'Point', 'coordinates': (lon, lat)}.
    """
    where = entry.get("where")
    if where and where.get("type") == "Point":
        coords = where.get("coordinates")
        if coords and len(coords) >= 2:
            lon, lat = coords[0], coords[1]
            return float(lat), float(lon)
    return None, None


class RssSource(Source):
    """Fetches incidents from RSS/Atom feeds."""

    def __init__(self, feeds: list[RssFeed]) -> None:
        self._feeds = [f for f in feeds if f.enabled]

    @property
    def name(self) -> str:
        return "RSS"

    async def fetch(self) -> list[RawIncident]:
        results: list[RawIncident] = []
        for feed_cfg in self._feeds:
            try:
                incidents = await asyncio.wait_for(
                    self._fetch_feed(feed_cfg),
                    timeout=_FETCH_TIMEOUT,
                )
                results.extend(incidents)
                logger.debug(
                    "RSS feed '%s': %d entries", feed_cfg.name, len(incidents)
                )
            except asyncio.TimeoutError:
                logger.warning("RSS feed '%s' timed out after %ds", feed_cfg.name, _FETCH_TIMEOUT)
            except Exception:
                logger.exception("RSS feed '%s' fetch failed", feed_cfg.name)
        return results

    async def _fetch_feed(self, feed_cfg: RssFeed) -> list[RawIncident]:
        feed = await asyncio.to_thread(feedparser.parse, feed_cfg.url)
        if feed.bozo:
            logger.warning(
                "RSS feed '%s' had parse errors: %s",
                feed_cfg.name,
                feed.bozo_exception,
            )

        incidents: list[RawIncident] = []
        urls_to_fetch: list[tuple[int, str]] = []

        for entry in feed.entries:
            title = entry.get("title", "").strip()
            description = _strip_html(entry.get("summary", entry.get("description", "")))
            link = entry.get("link", "")
            published = _parse_date(entry)
            lat, lon = _extract_geo(entry)

            if not title:
                continue

            idx = len(incidents)
            incidents.append(
                RawIncident(
                    source_name=feed_cfg.name,
                    title=title,
                    description=description,
                    url=link,
                    published=published,
                    lat=lat,
                    lon=lon,
                    structured=lat is not None and lon is not None,
                    source_id=entry.get("id"),
                )
            )

            if len(description) < _MIN_DESCRIPTION_LEN and link:
                urls_to_fetch.append((idx, link))

        # Fetch article bodies for entries with thin descriptions
        if urls_to_fetch:
            await self._backfill_descriptions(incidents, urls_to_fetch)

        return incidents

    async def _backfill_descriptions(
        self,
        incidents: list[RawIncident],
        urls: list[tuple[int, str]],
    ) -> None:
        """Fetch article pages and extract lead text for thin descriptions."""
        async with httpx.AsyncClient(
            timeout=10.0,
            follow_redirects=True,
            headers={"User-Agent": "CrypTAK-IncidentTracker/1.0"},
        ) as client:
            for idx, url in urls:
                try:
                    resp = await client.get(url)
                    resp.raise_for_status()
                    text = _extract_article_text(resp.text)
                    if len(text) > len(incidents[idx].description):
                        incidents[idx].description = text
                except Exception:
                    logger.debug("Could not fetch article body: %s", url[:80])
