import asyncio
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.config import RssFeed
from src.sources.rss import (
    RssSource,
    _extract_article_text,
    _extract_geo,
    _parse_date,
    _strip_html,
)


class TestStripHtml:
    def test_removes_tags(self):
        assert _strip_html("<p>Hello <b>world</b></p>") == "Hello world"

    def test_plain_text_unchanged(self):
        assert _strip_html("No tags here") == "No tags here"

    def test_empty_string(self):
        assert _strip_html("") == ""


class TestParseDate:
    def test_with_published_parsed(self):
        entry = {"published_parsed": (2026, 2, 25, 14, 30, 0, 0, 56, 0)}
        result = _parse_date(entry)
        assert result.year == 2026
        assert result.month == 2
        assert result.tzinfo == timezone.utc

    def test_fallback_to_now(self):
        entry = {}
        result = _parse_date(entry)
        assert (datetime.now(timezone.utc) - result).total_seconds() < 5


class TestRssSource:
    @pytest.mark.asyncio
    async def test_fetch_parses_entries(self):
        mock_feed = MagicMock()
        mock_feed.bozo = False
        mock_feed.entries = [
            {
                "title": "Fire on Main Street",
                "summary": "<p>A large fire broke out</p>",
                "link": "https://news.example.com/fire",
                "published_parsed": (2026, 2, 25, 10, 0, 0, 0, 56, 0),
                "id": "article-123",
            },
        ]

        with patch("src.sources.rss.feedparser.parse", return_value=mock_feed):
            source = RssSource([RssFeed(name="Test", url="https://example.com/rss")])
            incidents = await source.fetch()

        assert len(incidents) == 1
        assert incidents[0].title == "Fire on Main Street"
        assert incidents[0].description == "A large fire broke out"
        assert incidents[0].structured is False
        assert incidents[0].source_id == "article-123"

    @pytest.mark.asyncio
    async def test_disabled_feed_skipped(self):
        source = RssSource([
            RssFeed(name="Disabled", url="https://example.com/rss", enabled=False),
        ])
        incidents = await source.fetch()
        assert len(incidents) == 0

    @pytest.mark.asyncio
    async def test_bozo_feed_still_returns_entries(self):
        mock_feed = MagicMock()
        mock_feed.bozo = True
        mock_feed.bozo_exception = Exception("Malformed XML")
        mock_feed.entries = [
            {"title": "Partial entry", "summary": "Some text", "link": ""},
        ]

        with patch("src.sources.rss.feedparser.parse", return_value=mock_feed):
            source = RssSource([RssFeed(name="Bozo", url="https://example.com")])
            incidents = await source.fetch()

        assert len(incidents) == 1

    @pytest.mark.asyncio
    async def test_entry_without_title_skipped(self):
        mock_feed = MagicMock()
        mock_feed.bozo = False
        mock_feed.entries = [
            {"title": "", "summary": "No title entry"},
            {"title": "Has title", "summary": "Good entry", "link": ""},
        ]

        with patch("src.sources.rss.feedparser.parse", return_value=mock_feed):
            source = RssSource([RssFeed(name="Test", url="https://example.com")])
            incidents = await source.fetch()

        assert len(incidents) == 1
        assert incidents[0].title == "Has title"

    @pytest.mark.asyncio
    async def test_georss_entry_is_structured(self):
        mock_feed = MagicMock()
        mock_feed.bozo = False
        mock_feed.entries = [
            {
                "title": "Earthquake near city",
                "summary": "M3.5",
                "link": "",
                "where": {"type": "Point", "coordinates": (-83.92, 35.96)},
            },
        ]

        with patch("src.sources.rss.feedparser.parse", return_value=mock_feed):
            source = RssSource([RssFeed(name="GeoFeed", url="https://example.com")])
            incidents = await source.fetch()

        assert len(incidents) == 1
        assert incidents[0].structured is True
        assert incidents[0].lat == 35.96
        assert incidents[0].lon == -83.92


class TestExtractArticleText:
    def test_extracts_paragraphs(self):
        html = '<html><body><p>First paragraph with enough text to pass the filter.</p><p>Second short</p></body></html>'
        text = _extract_article_text(html)
        assert "First paragraph" in text
        # Short paragraphs (<30 chars) are excluded
        assert "Second short" not in text

    def test_truncates_long_text(self):
        html = '<p>' + 'A' * 2000 + '</p>'
        text = _extract_article_text(html, max_chars=100)
        assert len(text) <= 100

    def test_empty_html_returns_empty(self):
        assert _extract_article_text("") == ""


class TestParseDateFallbacks:
    def test_with_updated_parsed(self):
        entry = {"updated_parsed": (2026, 1, 15, 8, 0, 0, 0, 15, 0)}
        result = _parse_date(entry)
        assert result.year == 2026
        assert result.month == 1

    def test_with_raw_rfc2822_string(self):
        entry = {"published": "Wed, 25 Feb 2026 14:30:00 GMT"}
        result = _parse_date(entry)
        assert result.year == 2026
        assert result.month == 2

    def test_invalid_parsed_falls_through(self):
        # Overflow in timegm
        entry = {"published_parsed": (99999, 1, 1, 0, 0, 0, 0, 0, 0)}
        result = _parse_date(entry)
        # Should fall through to now
        assert (datetime.now(timezone.utc) - result).total_seconds() < 5


class TestExtractGeo:
    def test_no_where_field(self):
        assert _extract_geo({}) == (None, None)

    def test_non_point_type(self):
        assert _extract_geo({"where": {"type": "Polygon"}}) == (None, None)

    def test_short_coordinates(self):
        assert _extract_geo({"where": {"type": "Point", "coordinates": (-83,)}}) == (None, None)


class TestRssSourceEdgeCases:
    @pytest.mark.asyncio
    async def test_backfill_fetches_article_body(self):
        """Thin descriptions should trigger article body fetch."""
        mock_feed = MagicMock()
        mock_feed.bozo = False
        mock_feed.entries = [
            {
                "title": "Big Fire",
                "summary": "Short",
                "link": "https://news.example.com/article",
            },
        ]

        mock_page_resp = MagicMock()
        mock_page_resp.status_code = 200
        mock_page_resp.raise_for_status = MagicMock()
        mock_page_resp.text = '<html><body><p>This is the full article text about a large fire that occurred downtown.</p></body></html>'

        with patch("src.sources.rss.feedparser.parse", return_value=mock_feed):
            with patch("src.sources.rss.httpx.AsyncClient") as MockClient:
                client_instance = AsyncMock()
                client_instance.get.return_value = mock_page_resp
                client_instance.__aenter__ = AsyncMock(return_value=client_instance)
                client_instance.__aexit__ = AsyncMock(return_value=False)
                MockClient.return_value = client_instance

                source = RssSource([RssFeed(name="Test", url="https://example.com/rss")])
                incidents = await source.fetch()

        assert len(incidents) == 1
        # Description should have been backfilled with article text
        assert "large fire" in incidents[0].description

    @pytest.mark.asyncio
    async def test_backfill_failure_keeps_original(self):
        """If article fetch fails, original description is preserved."""
        mock_feed = MagicMock()
        mock_feed.bozo = False
        mock_feed.entries = [
            {
                "title": "Event",
                "summary": "Brief",
                "link": "https://news.example.com/broken",
            },
        ]

        with patch("src.sources.rss.feedparser.parse", return_value=mock_feed):
            with patch("src.sources.rss.httpx.AsyncClient") as MockClient:
                client_instance = AsyncMock()
                client_instance.get.side_effect = Exception("connection timeout")
                client_instance.__aenter__ = AsyncMock(return_value=client_instance)
                client_instance.__aexit__ = AsyncMock(return_value=False)
                MockClient.return_value = client_instance

                source = RssSource([RssFeed(name="Test", url="https://example.com/rss")])
                incidents = await source.fetch()

        assert len(incidents) == 1
        assert incidents[0].description == "Brief"

    @pytest.mark.asyncio
    async def test_feed_exception_handled(self):
        """A feed that raises during parse should not crash the source."""
        with patch("src.sources.rss.feedparser.parse", side_effect=Exception("parse error")):
            source = RssSource([RssFeed(name="Bad", url="https://example.com/rss")])
            incidents = await source.fetch()

        assert incidents == []

    @pytest.mark.asyncio
    async def test_feed_timeout_handled(self):
        """A feed that times out should not crash the source."""
        async def slow_parse(*args, **kwargs):
            await asyncio.sleep(100)

        with patch("src.sources.rss.asyncio.wait_for", side_effect=asyncio.TimeoutError()):
            source = RssSource([RssFeed(name="Slow", url="https://example.com/rss")])
            incidents = await source.fetch()

        assert incidents == []
