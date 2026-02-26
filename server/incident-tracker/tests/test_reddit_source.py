import time
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from src.config import RedditConfig
from src.sources.reddit import RedditSource


def _make_config(**overrides):
    defaults = {
        "enabled": True,
        "subreddits": ["ICEwatchDC"],
        "search_subreddits": {},
        "lookback_minutes": 60,
    }
    defaults.update(overrides)
    return RedditConfig(**defaults)


def _make_post(**overrides):
    defaults = {
        "id": "abc123",
        "title": "ICE agents spotted at Columbia Heights Metro",
        "selftext": "3 agents in plainclothes near the south entrance, unmarked black SUV",
        "permalink": "/r/ICEwatchDC/comments/abc123/ice_agents_spotted/",
        "created_utc": time.time() - 300,  # 5 minutes ago
        "stickied": False,
    }
    defaults.update(overrides)
    return defaults


def _make_listing(posts):
    return {
        "data": {
            "children": [{"kind": "t3", "data": p} for p in posts],
        },
    }


def _mock_response(listing, status=200):
    resp = MagicMock()
    resp.status_code = status
    resp.json.return_value = listing
    resp.raise_for_status = MagicMock()
    return resp


class TestRedditSource:
    @pytest.mark.asyncio
    async def test_basic_full_feed_fetch(self):
        posts = [_make_post()]
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing(posts))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert len(results) == 1
        inc = results[0]
        assert inc.source_name == "Reddit r/ICEwatchDC"
        assert inc.title == "ICE agents spotted at Columbia Heights Metro"
        assert "plainclothes" in inc.description
        assert inc.structured is False
        assert inc.source_id == "reddit-abc123"
        assert inc.lat is None
        assert inc.lon is None

    @pytest.mark.asyncio
    async def test_search_query_mode(self):
        posts = [_make_post(id="search1", title="ICE raid in Georgetown")]
        config = _make_config(
            subreddits=[],
            search_subreddits={"washingtondc": "ICE OR immigration raid"},
        )
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing(posts))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(config)
            results = await source.fetch()

            # Verify search params
            call_kwargs = mock_client.get.call_args
            params = call_kwargs.kwargs.get("params") or call_kwargs[1].get("params")
            assert params["q"] == "ICE OR immigration raid"
            assert params["sort"] == "new"
            assert params["restrict_sr"] == "on"

        assert len(results) == 1
        assert results[0].source_name == "Reddit r/washingtondc"

    @pytest.mark.asyncio
    async def test_both_modes_combined(self):
        post_feed = _make_post(id="feed1", title="Feed post")
        post_search = _make_post(id="search1", title="Search post")

        config = _make_config(
            subreddits=["ICEwatchDC"],
            search_subreddits={"nova": "ICE OR raid"},
        )

        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            # First call: full-feed, second call: search
            mock_client.get.side_effect = [
                _mock_response(_make_listing([post_feed])),
                _mock_response(_make_listing([post_search])),
            ]
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(config)
            results = await source.fetch()

        assert len(results) == 2
        assert results[0].source_name == "Reddit r/ICEwatchDC"
        assert results[1].source_name == "Reddit r/nova"

    @pytest.mark.asyncio
    async def test_filters_old_posts(self):
        old_post = _make_post(created_utc=time.time() - 7200)  # 2 hours ago
        new_post = _make_post(id="new1", created_utc=time.time() - 300)

        config = _make_config(lookback_minutes=60)
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(
                _make_listing([old_post, new_post]),
            )
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(config)
            results = await source.fetch()

        assert len(results) == 1
        assert results[0].source_id == "reddit-new1"

    @pytest.mark.asyncio
    async def test_skips_stickied_posts(self):
        posts = [
            _make_post(stickied=True, id="sticky"),
            _make_post(stickied=False, id="normal"),
        ]
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing(posts))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert len(results) == 1
        assert results[0].source_id == "reddit-normal"

    @pytest.mark.asyncio
    async def test_disabled_returns_empty(self):
        source = RedditSource(_make_config(enabled=False))
        results = await source.fetch()
        assert results == []

    @pytest.mark.asyncio
    async def test_api_failure_returns_empty(self):
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.side_effect = httpx.ConnectError("timeout")
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert results == []

    @pytest.mark.asyncio
    async def test_url_construction(self):
        posts = [_make_post(permalink="/r/ICEwatchDC/comments/abc123/title/")]
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing(posts))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert results[0].url == "https://www.reddit.com/r/ICEwatchDC/comments/abc123/title/"

    @pytest.mark.asyncio
    async def test_selftext_used_as_description(self):
        posts = [_make_post(selftext="Detailed sighting report with location info")]
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing(posts))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert results[0].description == "Detailed sighting report with location info"

    @pytest.mark.asyncio
    async def test_empty_selftext_uses_title(self):
        posts = [_make_post(selftext="")]
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing(posts))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert results[0].description == results[0].title

    @pytest.mark.asyncio
    async def test_published_timestamp_utc(self):
        now = time.time()
        posts = [_make_post(created_utc=now)]
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing(posts))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert results[0].published.tzinfo == timezone.utc

    @pytest.mark.asyncio
    async def test_full_feed_url_format(self):
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing([]))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config(subreddits=["ICEwatchDC"]))
            await source.fetch()

            url = mock_client.get.call_args[0][0]
            assert url == "https://www.reddit.com/r/ICEwatchDC/new.json"

    @pytest.mark.asyncio
    async def test_search_url_format(self):
        config = _make_config(
            subreddits=[],
            search_subreddits={"washingtondc": "ICE raid"},
        )
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing([]))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(config)
            await source.fetch()

            url = mock_client.get.call_args[0][0]
            assert url == "https://www.reddit.com/r/washingtondc/search.json"

    @pytest.mark.asyncio
    async def test_long_selftext_truncated(self):
        long_text = "x" * 2000
        posts = [_make_post(selftext=long_text)]
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing(posts))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert len(results[0].description) == 1000

    @pytest.mark.asyncio
    async def test_empty_listing(self):
        with patch("src.sources.reddit.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(_make_listing([]))
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = RedditSource(_make_config())
            results = await source.fetch()

        assert results == []

    def test_name(self):
        source = RedditSource(_make_config())
        assert source.name == "Reddit"
