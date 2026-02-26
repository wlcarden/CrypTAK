from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from src.config import GeoFilter, StopIceConfig
from src.sources.stopice import (
    StopIceSource,
    _parse_timestamp,
    _PRIORITY_RANK,
    _PRIORITY_SEVERITY,
    _PRIORITY_CATEGORY,
)


def _make_geo(bbox=None):
    return GeoFilter(
        center_lat=38.9, center_lon=-77.1, radius_km=50,
        bbox=bbox or (38.75, -77.52, 39.05, -76.80),
    )


def _make_config(**overrides):
    defaults = {"enabled": True, "min_priority": "unconfirmed"}
    defaults.update(overrides)
    return StopIceConfig(**defaults)


def _make_report(
    report_id="1772076003068",
    lat="38.90",
    lon="-77.16",
    priority="ICE Sighting",
    location="1300 K St NW Washington DC 20005",
    timestamp="feb 25, 2026 (19:22:04) PST",
    comments="ICE agents spotted near metro station",
    url="https://www.stopice.net/?alert=1772076003068",
    media="",
):
    return f"""<report_data>
  <id>{report_id}</id>
  <url>{url}</url>
  <lat>{lat}</lat>
  <long>{lon}</long>
  <thispriority>{priority}</thispriority>
  <location>{location}</location>
  <timestamp>{timestamp}</timestamp>
  <comments>{comments}</comments>
  <media>{media}</media>
</report_data>"""


def _make_xml(*reports):
    body = "\n".join(reports)
    return f'<?xml version="1.0" encoding="utf-16" standalone="yes" ?>\n<stopice_data>\n{body}\n</stopice_data>'


def _mock_response(xml_text, status=200):
    resp = MagicMock()
    resp.status_code = status
    resp.text = xml_text
    resp.raise_for_status = MagicMock()
    return resp


class TestStopIceSource:
    @pytest.mark.asyncio
    async def test_basic_fetch(self):
        xml = _make_xml(_make_report())
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert len(results) == 1
        inc = results[0]
        assert inc.source_name == "StopICE"
        assert inc.lat == 38.90
        assert inc.lon == -77.16
        assert inc.structured is True
        assert inc.source_id == "stopice-1772076003068"
        assert inc.severity == "medium"
        assert inc.category == "ice_sighting"

    @pytest.mark.asyncio
    async def test_title_format(self):
        xml = _make_xml(_make_report(
            priority="URGENT Confirmed ICE report",
            location="4139 Alabama Ave SE Washington DC",
        ))
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].title == "URGENT Confirmed ICE report: 4139 Alabama Ave SE Washington DC"

    @pytest.mark.asyncio
    async def test_urgent_maps_to_federal_raid(self):
        xml = _make_xml(_make_report(priority="URGENT Confirmed ICE report"))
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].category == "federal_raid"
        assert results[0].severity == "high"

    @pytest.mark.asyncio
    async def test_filters_by_min_priority_sighting(self):
        xml = _make_xml(
            _make_report(report_id="1", priority="URGENT Confirmed ICE report"),
            _make_report(report_id="2", priority="ICE Sighting"),
            _make_report(report_id="3", priority="Unconfirmed Report"),
        )
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(min_priority="sighting"), _make_geo())
            results = await source.fetch()

        assert len(results) == 2
        ids = {r.source_id for r in results}
        assert "stopice-1" in ids
        assert "stopice-2" in ids

    @pytest.mark.asyncio
    async def test_filters_by_min_priority_confirmed(self):
        xml = _make_xml(
            _make_report(report_id="1", priority="URGENT Confirmed ICE report"),
            _make_report(report_id="2", priority="ICE Sighting"),
        )
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(min_priority="confirmed"), _make_geo())
            results = await source.fetch()

        assert len(results) == 1
        assert results[0].source_id == "stopice-1"

    @pytest.mark.asyncio
    async def test_filters_by_geo_bbox(self):
        xml = _make_xml(
            _make_report(report_id="1", lat="38.90", lon="-77.16"),   # Inside DC bbox
            _make_report(report_id="2", lat="33.98", lon="-118.18"),  # Los Angeles
        )
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert len(results) == 1
        assert results[0].source_id == "stopice-1"

    @pytest.mark.asyncio
    async def test_filters_by_geo_radius(self):
        xml = _make_xml(
            _make_report(report_id="1", lat="38.90", lon="-77.16"),
            _make_report(report_id="2", lat="33.98", lon="-118.18"),
        )
        geo = GeoFilter(center_lat=38.9, center_lon=-77.1, radius_km=50, bbox=None)
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), geo)
            results = await source.fetch()

        assert len(results) == 1

    @pytest.mark.asyncio
    async def test_skips_already_seen_ids(self):
        xml = _make_xml(_make_report(report_id="1"))
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())

            # First fetch returns the report
            results1 = await source.fetch()
            assert len(results1) == 1

            # Second fetch skips it (already seen)
            results2 = await source.fetch()
            assert len(results2) == 0

    @pytest.mark.asyncio
    async def test_disabled_returns_empty(self):
        source = StopIceSource(_make_config(enabled=False), _make_geo())
        results = await source.fetch()
        assert results == []

    @pytest.mark.asyncio
    async def test_api_failure_returns_empty(self):
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.side_effect = httpx.ConnectError("timeout")
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results == []

    @pytest.mark.asyncio
    async def test_invalid_xml_returns_empty(self):
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response("not valid xml <><>")
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results == []

    @pytest.mark.asyncio
    async def test_missing_coordinates_skipped(self):
        xml = _make_xml(_make_report(lat="", lon=""))
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results == []

    @pytest.mark.asyncio
    async def test_description_from_comments(self):
        xml = _make_xml(_make_report(comments="3 agents in unmarked SUV near metro"))
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].description == "3 agents in unmarked SUV near metro"

    @pytest.mark.asyncio
    async def test_url_preserved(self):
        xml = _make_xml(_make_report(url="https://www.stopice.net/?alert=999"))
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].url == "https://www.stopice.net/?alert=999"

    @pytest.mark.asyncio
    async def test_empty_xml(self):
        xml = '<?xml version="1.0"?>\n<stopice_data></stopice_data>'
        with patch("src.sources.stopice.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(xml)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = StopIceSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results == []

    def test_name(self):
        source = StopIceSource(_make_config(), _make_geo())
        assert source.name == "StopICE"


class TestParseTimestamp:
    def test_standard_pst(self):
        dt = _parse_timestamp("feb 25, 2026 (19:22:04) PST")
        assert dt.year == 2026
        assert dt.month == 2
        assert dt.day == 25
        assert dt.hour == 19
        assert dt.minute == 22
        assert dt.tzinfo is not None

    def test_converts_to_utc_offset(self):
        dt = _parse_timestamp("feb 25, 2026 (19:22:04) PST")
        utc = dt.astimezone(timezone.utc)
        # PST is UTC-8, so 19:22 PST = 03:22 next day UTC
        assert utc.hour == 3
        assert utc.day == 26

    def test_invalid_returns_now(self):
        dt = _parse_timestamp("not a date")
        assert dt.tzinfo is not None
        # Should be close to now
        assert (datetime.now(timezone.utc) - dt).total_seconds() < 5


class TestPriorityMaps:
    def test_all_priorities_have_severity(self):
        for priority in _PRIORITY_RANK:
            assert priority in _PRIORITY_SEVERITY

    def test_all_priorities_have_category(self):
        for priority in _PRIORITY_RANK:
            assert priority in _PRIORITY_CATEGORY

    def test_urgent_is_highest_rank(self):
        assert _PRIORITY_RANK["URGENT Confirmed ICE report"] > _PRIORITY_RANK["ICE Sighting"]
        assert _PRIORITY_RANK["ICE Sighting"] > _PRIORITY_RANK["Unconfirmed Report"]


class TestStopIceConfigValidation:
    def test_valid_priorities(self):
        for p in ("unconfirmed", "sighting", "confirmed"):
            config = StopIceConfig(min_priority=p)
            assert config.min_priority == p

    def test_invalid_priority_rejected(self):
        with pytest.raises(Exception):
            StopIceConfig(min_priority="invalid")
