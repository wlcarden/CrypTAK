from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.config import UsgsConfig
from src.sources.usgs import UsgsSource, _magnitude_feed_tier

_SAMPLE_RESPONSE = {
    "type": "FeatureCollection",
    "metadata": {"count": 2},
    "features": [
        {
            "type": "Feature",
            "id": "us2026abcd",
            "geometry": {
                "type": "Point",
                "coordinates": [-117.678, 35.541, 8.2],
            },
            "properties": {
                "mag": 4.2,
                "place": "15 km SSW of Ridgecrest, CA",
                "time": 1740490000000,
                "alert": "green",
                "url": "https://earthquake.usgs.gov/earthquakes/eventpage/us2026abcd",
                "title": "M 4.2 - 15 km SSW of Ridgecrest, CA",
                "code": "2026abcd",
            },
        },
        {
            "type": "Feature",
            "id": "ci12345",
            "geometry": {
                "type": "Point",
                "coordinates": [-118.5, 34.0, 5.0],
            },
            "properties": {
                "mag": 1.5,
                "place": "Near LA",
                "time": 1740491000000,
                "alert": None,
                "url": "",
                "title": "M 1.5 - Near LA",
            },
        },
    ],
}


class TestMagnitudeFeedTier:
    def test_high_magnitude(self):
        assert _magnitude_feed_tier(5.0) == "4.5"

    def test_medium_magnitude(self):
        assert _magnitude_feed_tier(3.0) == "2.5"

    def test_low_magnitude(self):
        assert _magnitude_feed_tier(0.5) == "all"


class TestUsgsSource:
    @pytest.mark.asyncio
    async def test_fetch_parses_earthquakes(self):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = _SAMPLE_RESPONSE

        with patch("src.sources.usgs.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = UsgsConfig(enabled=True, min_magnitude=3.0, feed_window="day")
            source = UsgsSource(config)
            incidents = await source.fetch()

        # Only M4.2 passes the min_magnitude=3.0 filter
        assert len(incidents) == 1
        inc = incidents[0]
        assert inc.structured is True
        # Coordinates swapped from GeoJSON [lon, lat] to (lat, lon)
        assert abs(inc.lat - 35.541) < 0.001
        assert abs(inc.lon - (-117.678)) < 0.001
        assert inc.category == "natural_disaster"
        assert inc.severity == "low"  # green alert
        assert inc.source_id == "us2026abcd"
        assert inc.published.year == 2025  # from epoch ms

    @pytest.mark.asyncio
    async def test_disabled_returns_empty(self):
        config = UsgsConfig(enabled=False)
        source = UsgsSource(config)
        assert await source.fetch() == []

    @pytest.mark.asyncio
    async def test_invalid_window_defaults_to_day(self):
        """Invalid feed_window should fall back to 'day'."""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = {"features": []}

        with patch("src.sources.usgs.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = UsgsConfig(enabled=True, feed_window="invalid")
            source = UsgsSource(config)
            await source.fetch()

            url = client_instance.get.call_args[0][0]
            assert "_day.geojson" in url

    @pytest.mark.asyncio
    async def test_http_error_returns_empty(self):
        with patch("src.sources.usgs.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.side_effect = Exception("network error")
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = UsgsConfig(enabled=True)
            source = UsgsSource(config)
            result = await source.fetch()

        assert result == []

    @pytest.mark.asyncio
    async def test_short_coordinates_skipped(self):
        """Features with fewer than 2 coordinates should be skipped."""
        data = {
            "features": [{
                "type": "Feature",
                "id": "bad",
                "geometry": {"type": "Point", "coordinates": [-117.0]},
                "properties": {"mag": 5.0, "place": "test", "time": 1740490000000},
            }],
        }
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = data

        with patch("src.sources.usgs.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = UsgsConfig(enabled=True, min_magnitude=3.0)
            source = UsgsSource(config)
            result = await source.fetch()

        assert result == []

    @pytest.mark.asyncio
    async def test_missing_time_defaults_to_now(self):
        """Missing time field should default to current UTC time."""
        data = {
            "features": [{
                "type": "Feature",
                "id": "no-time",
                "geometry": {"type": "Point", "coordinates": [-117.0, 35.0, 5.0]},
                "properties": {"mag": 5.0, "place": "Test", "time": None, "alert": None},
            }],
        }
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = data

        with patch("src.sources.usgs.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = UsgsConfig(enabled=True, min_magnitude=3.0)
            source = UsgsSource(config)
            result = await source.fetch()

        assert len(result) == 1
        now = datetime.now(timezone.utc)
        assert (now - result[0].published).total_seconds() < 5

    @pytest.mark.asyncio
    async def test_alert_severity_mapping(self):
        """Different alert colors should map to correct severity levels."""
        features = []
        for i, (alert, expected) in enumerate([
            ("red", "critical"), ("orange", "high"),
            ("yellow", "medium"), ("green", "low"),
        ]):
            features.append({
                "type": "Feature",
                "id": f"test-{i}",
                "geometry": {"type": "Point", "coordinates": [-117.0, 35.0, 5.0]},
                "properties": {"mag": 5.0, "place": "Test", "time": 1740490000000, "alert": alert},
            })

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = {"features": features}

        with patch("src.sources.usgs.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = UsgsConfig(enabled=True, min_magnitude=3.0)
            source = UsgsSource(config)
            result = await source.fetch()

        severities = [r.severity for r in result]
        assert severities == ["critical", "high", "medium", "low"]


class TestMagnitudeFeedTierEdge:
    def test_exact_threshold(self):
        assert _magnitude_feed_tier(4.5) == "4.5"

    def test_zero_magnitude(self):
        assert _magnitude_feed_tier(0.0) == "all"
