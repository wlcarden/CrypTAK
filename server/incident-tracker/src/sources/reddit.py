from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

import httpx

from src.config import RedditConfig
from src.models import RawIncident
from src.sources.base import Source

logger = logging.getLogger(__name__)

_BASE_URL = "https://www.reddit.com"
_USER_AGENT = "CrypTAK-IncidentTracker/1.0 (by u/CrypTAK-bot)"


class RedditSource(Source):
    """Fetches posts from Reddit using the public JSON API.

    Two polling modes:
      1. Full-feed: fetches /r/{sub}/new.json for small, high-signal subs
      2. Search-query: fetches /r/{sub}/search.json?q=... for large, noisy subs
    """

    def __init__(self, config: RedditConfig) -> None:
        self._config = config

    @property
    def name(self) -> str:
        return "Reddit"

    async def fetch(self) -> list[RawIncident]:
        if not self._config.enabled:
            return []

        cutoff = datetime.now(timezone.utc) - timedelta(
            minutes=self._config.lookback_minutes,
        )

        incidents: list[RawIncident] = []

        async with httpx.AsyncClient(
            timeout=15.0,
            headers={"User-Agent": _USER_AGENT},
        ) as client:
            # Mode 1: Full-feed for high-signal subs
            for sub in self._config.subreddits:
                posts = await self._fetch_new(client, sub, cutoff)
                incidents.extend(posts)

            # Mode 2: Search-query for noisy subs
            for sub, query in self._config.search_subreddits.items():
                posts = await self._fetch_search(client, sub, query, cutoff)
                incidents.extend(posts)

        logger.debug(
            "Reddit: %d posts from %d full-feed + %d search subs",
            len(incidents),
            len(self._config.subreddits),
            len(self._config.search_subreddits),
        )
        return incidents

    async def _fetch_new(
        self,
        client: httpx.AsyncClient,
        subreddit: str,
        cutoff: datetime,
    ) -> list[RawIncident]:
        url = f"{_BASE_URL}/r/{subreddit}/new.json"
        params = {"limit": 50, "raw_json": 1}
        return await self._fetch_listing(client, url, params, subreddit, cutoff)

    async def _fetch_search(
        self,
        client: httpx.AsyncClient,
        subreddit: str,
        query: str,
        cutoff: datetime,
    ) -> list[RawIncident]:
        url = f"{_BASE_URL}/r/{subreddit}/search.json"
        params = {
            "q": query,
            "sort": "new",
            "restrict_sr": "on",
            "limit": 25,
            "raw_json": 1,
        }
        return await self._fetch_listing(client, url, params, subreddit, cutoff)

    async def _fetch_listing(
        self,
        client: httpx.AsyncClient,
        url: str,
        params: dict,
        subreddit: str,
        cutoff: datetime,
    ) -> list[RawIncident]:
        try:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            data = resp.json()
        except Exception:
            logger.exception("Reddit API request failed for r/%s", subreddit)
            return []

        listing = data.get("data", {})
        children = listing.get("children", [])

        incidents: list[RawIncident] = []
        for child in children:
            post = child.get("data", {})

            created_utc = post.get("created_utc")
            if created_utc is None:
                continue
            published = datetime.fromtimestamp(created_utc, tz=timezone.utc)
            if published < cutoff:
                continue

            title = post.get("title", "")
            selftext = post.get("selftext", "")
            permalink = post.get("permalink", "")
            post_id = post.get("id", "")

            # Skip stickied/pinned mod posts
            if post.get("stickied", False):
                continue

            full_url = f"{_BASE_URL}{permalink}" if permalink else ""
            description = selftext[:1000] if selftext else title

            incidents.append(
                RawIncident(
                    source_name=f"Reddit r/{subreddit}",
                    title=title,
                    description=description,
                    url=full_url,
                    published=published,
                    lat=None,
                    lon=None,
                    category=None,
                    severity=None,
                    structured=False,
                    source_id=f"reddit-{post_id}",
                ),
            )

        return incidents
