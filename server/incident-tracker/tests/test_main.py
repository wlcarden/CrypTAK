"""Tests for main.py — source wiring, raw→analyzed conversion, poll cycle."""

from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.analysis.ai_analyzer import AiAnalyzer
from src.analysis.geocoder import Geocoder
from src.analysis.keyword_filter import KeywordFilter
from src.config import (
    AiConfig,
    CategoryDef,
    DcCrimeConfig,
    GeoFilter,
    NwsConfig,
    RedditConfig,
    RssFeed,
    SourcesConfig,
    StopIceConfig,
    TrackerConfig,
    UsgsConfig,
    WazeConfig,
)
from src.cot.fts_client import FtsClient
from src.dedup import Deduplicator
from src.main import _build_sources, _to_analyzed, poll_cycle
from src.models import AnalyzedIncident, RawIncident


# ── Fixtures ─────────────────────────────────────────────────────────────────

def _geo_filter():
    return GeoFilter(center_lat=35.96, center_lon=-83.92, radius_km=100)


def _categories():
    return [
        CategoryDef(
            name="fire", description="Fires",
            affiliation="neutral", icon_type="fire",
        ),
        CategoryDef(
            name="natural_disaster", description="Natural disasters",
            affiliation="neutral", icon_type="natural",
        ),
        CategoryDef(
            name="traffic", description="Accidents",
            affiliation="unknown", icon_type="traffic",
        ),
    ]


def _config(**overrides) -> TrackerConfig:
    defaults = dict(
        poll_interval_seconds=300,
        dedup_window_hours=24,
        keywords=["fire", "tornado"],
        geo_filter=_geo_filter(),
        sources=SourcesConfig(),
        ai=AiConfig(enabled=False),
        categories=_categories(),
        fts_host="localhost",
        fts_port=8087,
    )
    defaults.update(overrides)
    return TrackerConfig(**defaults)


def _raw_incident(**overrides) -> RawIncident:
    defaults = dict(
        source_name="TestSource",
        title="Fire on Main Street",
        description="A fire broke out at 123 Main St",
        url="https://example.com/fire",
        published=datetime.now(timezone.utc),
    )
    defaults.update(overrides)
    return RawIncident(**defaults)


def _structured_incident(**overrides) -> RawIncident:
    defaults = dict(
        source_name="NWS",
        title="Tornado Warning for Knox County",
        description="A tornado has been spotted",
        url="https://alerts.weather.gov",
        published=datetime.now(timezone.utc),
        lat=35.96,
        lon=-83.92,
        category="natural_disaster",
        severity="critical",
        structured=True,
        source_id="nws-test-1",
    )
    defaults.update(overrides)
    return RawIncident(**defaults)


# ── _build_sources ───────────────────────────────────────────────────────────

class TestBuildSources:
    def test_no_sources_when_all_disabled(self):
        cfg = _config()
        sources = _build_sources(cfg)
        assert len(sources) == 0

    def test_rss_source_added(self):
        cfg = _config(sources=SourcesConfig(
            rss=[RssFeed(name="Test", url="https://example.com/rss")],
        ))
        sources = _build_sources(cfg)
        assert any(s.name == "RSS" for s in sources)

    def test_nws_source_added(self):
        cfg = _config(sources=SourcesConfig(
            nws=NwsConfig(enabled=True, zone_codes=["TNZ063"]),
        ))
        sources = _build_sources(cfg)
        assert any(s.name == "NWS" for s in sources)

    def test_usgs_source_added(self):
        cfg = _config(sources=SourcesConfig(
            usgs=UsgsConfig(enabled=True),
        ))
        sources = _build_sources(cfg)
        assert any(s.name == "USGS" for s in sources)

    def test_dc_crime_source_added(self):
        cfg = _config(sources=SourcesConfig(
            dc_crime=DcCrimeConfig(enabled=True),
        ))
        sources = _build_sources(cfg)
        assert any(s.name == "DC Crime" for s in sources)

    def test_waze_source_added(self):
        cfg = _config(sources=SourcesConfig(
            waze=WazeConfig(enabled=True),
        ))
        sources = _build_sources(cfg)
        assert any(s.name == "Waze" for s in sources)

    def test_reddit_source_added(self):
        cfg = _config(sources=SourcesConfig(
            reddit=RedditConfig(enabled=True, subreddits=["test"]),
        ))
        sources = _build_sources(cfg)
        assert any(s.name == "Reddit" for s in sources)

    def test_stopice_source_added(self):
        cfg = _config(sources=SourcesConfig(
            stopice=StopIceConfig(enabled=True),
        ))
        sources = _build_sources(cfg)
        assert any(s.name == "StopICE" for s in sources)

    def test_multiple_sources(self):
        cfg = _config(sources=SourcesConfig(
            nws=NwsConfig(enabled=True, zone_codes=["TNZ063"]),
            usgs=UsgsConfig(enabled=True),
        ))
        sources = _build_sources(cfg)
        assert len(sources) == 2


# ── _to_analyzed ─────────────────────────────────────────────────────────────

class TestToAnalyzed:
    def test_structured_incident_converts(self):
        raw = _structured_incident()
        cfg = _config()
        result = _to_analyzed(raw, cfg)
        assert result is not None
        assert isinstance(result, AnalyzedIncident)
        assert result.category == "natural_disaster"
        assert result.lat == 35.96
        assert result.cot_type == "a-n-G-E-N"  # neutral + natural icon

    def test_structured_missing_category_returns_none(self):
        raw = _structured_incident(category="nonexistent_category")
        cfg = _config()
        result = _to_analyzed(raw, cfg)
        assert result is None

    def test_unstructured_without_ai_returns_none(self):
        raw = _raw_incident()
        cfg = _config()
        result = _to_analyzed(raw, cfg)
        assert result is None

    def test_unstructured_with_ai_result(self):
        raw = _raw_incident()
        cfg = _config()
        ai_result = {
            "category": "fire",
            "location": "123 Main St",
            "severity": "high",
            "summary": "Fire at Main Street warehouse",
        }
        result = _to_analyzed(raw, cfg, ai_result)
        assert result is not None
        assert result.category == "fire"
        assert result.severity == "high"
        assert result.summary == "Fire at Main Street warehouse"
        # Lat/lon default to 0 (filled by geocoder later)
        assert result.lat == 0.0
        assert result.lon == 0.0

    def test_ai_unknown_category_returns_none(self):
        raw = _raw_incident()
        cfg = _config()
        ai_result = {"category": "unknown_cat", "severity": "low", "summary": "s"}
        result = _to_analyzed(raw, cfg, ai_result)
        assert result is None

    def test_structured_uses_category_stale_minutes(self):
        cfg = _config()
        raw = _structured_incident(category="fire")
        result = _to_analyzed(raw, cfg)
        fire_cat = cfg.get_category("fire")
        assert result.stale_minutes == fire_cat.stale_minutes

    def test_description_truncated_to_200(self):
        raw = _structured_incident(description="A" * 500)
        cfg = _config()
        result = _to_analyzed(raw, cfg)
        assert len(result.summary) == 200

    def test_ai_result_defaults_severity_to_unknown(self):
        raw = _raw_incident()
        cfg = _config()
        ai_result = {"category": "fire", "summary": "test"}
        result = _to_analyzed(raw, cfg, ai_result)
        assert result.severity == "unknown"


# ── poll_cycle ───────────────────────────────────────────────────────────────

class TestPollCycle:
    @pytest.mark.asyncio
    async def test_empty_sources_sends_nothing(self):
        cfg = _config()
        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter([]),
            ai_analyzer=AsyncMock(spec=AiAnalyzer),
            geocoder=MagicMock(spec=Geocoder),
            fts=fts,
        )

        fts.send.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_structured_incident_sent_to_fts(self):
        cfg = _config()
        mock_source = AsyncMock()
        mock_source.name = "TestSource"
        mock_source.fetch = AsyncMock(return_value=[_structured_incident()])

        geocoder = MagicMock(spec=Geocoder)
        geocoder.within_area = MagicMock(return_value=True)

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter([]),
            ai_analyzer=AsyncMock(spec=AiAnalyzer),
            geocoder=geocoder,
            fts=fts,
        )

        fts.send.assert_awaited_once()
        cot_xml = fts.send.call_args[0][0]
        assert "event" in cot_xml
        assert "Tornado Warning" in cot_xml

    @pytest.mark.asyncio
    async def test_structured_outside_geo_filter_skipped(self):
        cfg = _config()
        mock_source = AsyncMock()
        mock_source.name = "TestSource"
        mock_source.fetch = AsyncMock(return_value=[
            _structured_incident(lat=0.0, lon=0.0),  # far outside
        ])

        geocoder = MagicMock(spec=Geocoder)
        geocoder.within_area = MagicMock(return_value=False)

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter([]),
            ai_analyzer=AsyncMock(spec=AiAnalyzer),
            geocoder=geocoder,
            fts=fts,
        )

        fts.send.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_unstructured_through_ai_pipeline(self):
        cfg = _config(ai=AiConfig(enabled=True, model="test"))
        raw = _raw_incident(title="Fire at warehouse", description="Big fire")

        mock_source = AsyncMock()
        mock_source.name = "RSS"
        mock_source.fetch = AsyncMock(return_value=[raw])

        ai_analyzer = AsyncMock(spec=AiAnalyzer)
        ai_analyzer.analyze = AsyncMock(return_value={
            "category": "fire",
            "location": "123 Main St, Knoxville TN",
            "severity": "high",
            "summary": "Warehouse fire",
        })

        geocoder = AsyncMock(spec=Geocoder)
        geocoder.within_area = MagicMock(return_value=True)
        geocoder.geocode = AsyncMock(return_value=(35.96, -83.92))

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter(["fire"]),
            ai_analyzer=ai_analyzer,
            geocoder=geocoder,
            fts=fts,
        )

        ai_analyzer.analyze.assert_awaited_once()
        geocoder.geocode.assert_awaited_once()
        fts.send.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_geocode_failure_skips_incident(self):
        cfg = _config(ai=AiConfig(enabled=True, model="test"))
        raw = _raw_incident()

        mock_source = AsyncMock()
        mock_source.name = "RSS"
        mock_source.fetch = AsyncMock(return_value=[raw])

        ai_analyzer = AsyncMock(spec=AiAnalyzer)
        ai_analyzer.analyze = AsyncMock(return_value={
            "category": "fire",
            "location": "somewhere vague",
            "severity": "low",
            "summary": "fire",
        })

        geocoder = AsyncMock(spec=Geocoder)
        geocoder.within_area = MagicMock(return_value=True)
        geocoder.geocode = AsyncMock(return_value=None)  # geocoding fails

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter(["fire"]),
            ai_analyzer=ai_analyzer,
            geocoder=geocoder,
            fts=fts,
        )

        fts.send.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_source_exception_handled(self):
        """A failing source should not crash the cycle."""
        cfg = _config()

        bad_source = AsyncMock()
        bad_source.name = "BadSource"
        bad_source.fetch = AsyncMock(side_effect=RuntimeError("boom"))

        good_source = AsyncMock()
        good_source.name = "GoodSource"
        good_source.fetch = AsyncMock(return_value=[_structured_incident()])

        geocoder = MagicMock(spec=Geocoder)
        geocoder.within_area = MagicMock(return_value=True)

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[bad_source, good_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter([]),
            ai_analyzer=AsyncMock(spec=AiAnalyzer),
            geocoder=geocoder,
            fts=fts,
        )

        # Good source incident still sent despite bad source failure
        fts.send.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_replay_buffer_accumulates(self):
        cfg = _config()
        replay_buffer = {}

        mock_source = AsyncMock()
        mock_source.name = "TestSource"
        mock_source.fetch = AsyncMock(return_value=[_structured_incident()])

        geocoder = MagicMock(spec=Geocoder)
        geocoder.within_area = MagicMock(return_value=True)

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter([]),
            ai_analyzer=AsyncMock(spec=AiAnalyzer),
            geocoder=geocoder,
            fts=fts,
            replay_buffer=replay_buffer,
        )

        assert len(replay_buffer) == 1

    @pytest.mark.asyncio
    async def test_replay_buffer_prunes_expired(self):
        cfg = _config()
        # Pre-populate buffer with an expired entry
        expired_uid = "incident-tracker-expired"
        replay_buffer = {
            expired_uid: ("<event/>", datetime.now(timezone.utc) - timedelta(hours=1)),
        }

        mock_source = AsyncMock()
        mock_source.name = "TestSource"
        mock_source.fetch = AsyncMock(return_value=[])

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter([]),
            ai_analyzer=AsyncMock(spec=AiAnalyzer),
            geocoder=MagicMock(spec=Geocoder),
            fts=fts,
            replay_buffer=replay_buffer,
        )

        assert expired_uid not in replay_buffer

    @pytest.mark.asyncio
    async def test_replay_buffer_resends_active_entries(self):
        cfg = _config()
        # Pre-populate buffer with a still-active entry
        active_uid = "incident-tracker-active"
        replay_buffer = {
            active_uid: ("<event/>", datetime.now(timezone.utc) + timedelta(hours=1)),
        }

        mock_source = AsyncMock()
        mock_source.name = "TestSource"
        mock_source.fetch = AsyncMock(return_value=[])

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter([]),
            ai_analyzer=AsyncMock(spec=AiAnalyzer),
            geocoder=MagicMock(spec=Geocoder),
            fts=fts,
            replay_buffer=replay_buffer,
        )

        # Active entry should have been sent
        fts.send.assert_awaited_once_with("<event/>")

    @pytest.mark.asyncio
    async def test_dedup_filters_duplicates(self):
        cfg = _config()
        inc = _structured_incident()

        mock_source = AsyncMock()
        mock_source.name = "TestSource"
        mock_source.fetch = AsyncMock(return_value=[inc, inc])

        geocoder = MagicMock(spec=Geocoder)
        geocoder.within_area = MagicMock(return_value=True)

        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        dedup = Deduplicator(24)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=dedup,
            kw_filter=KeywordFilter([]),
            ai_analyzer=AsyncMock(spec=AiAnalyzer),
            geocoder=geocoder,
            fts=fts,
        )

        # Dedup should have removed the duplicate (same source_id)
        assert fts.send.await_count == 1

    @pytest.mark.asyncio
    async def test_keyword_filter_applied_to_unstructured(self):
        """Unstructured incidents not matching keywords should be filtered."""
        cfg = _config(keywords=["tornado"])
        raw = _raw_incident(
            title="Local sports results",
            description="Team won the game",
        )

        mock_source = AsyncMock()
        mock_source.name = "RSS"
        mock_source.fetch = AsyncMock(return_value=[raw])

        ai_analyzer = AsyncMock(spec=AiAnalyzer)
        fts = AsyncMock(spec=FtsClient)
        fts.send = AsyncMock(return_value=True)

        await poll_cycle(
            config=cfg,
            sources=[mock_source],
            dedup=Deduplicator(24),
            kw_filter=KeywordFilter(["tornado"]),
            ai_analyzer=ai_analyzer,
            geocoder=MagicMock(spec=Geocoder),
            fts=fts,
        )

        # Should not reach AI since keyword filter drops it
        ai_analyzer.analyze.assert_not_awaited()
        fts.send.assert_not_awaited()
