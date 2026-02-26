import json
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.config import NwsConfig
from src.sources.nws import NwsSource, _parse_timestamp, _polygon_centroid

_SAMPLE_RESPONSE = {
    "type": "FeatureCollection",
    "features": [
        {
            "type": "Feature",
            "geometry": {
                "type": "Polygon",
                "coordinates": [[
                    [-84.0, 35.9],
                    [-83.8, 35.9],
                    [-83.8, 36.1],
                    [-84.0, 36.1],
                    [-84.0, 35.9],
                ]],
            },
            "properties": {
                "id": "urn:oid:nws-test-1",
                "event": "Tornado Warning",
                "headline": "Tornado Warning for Knox County",
                "description": "A tornado has been spotted...",
                "severity": "Extreme",
                "urgency": "Immediate",
                "onset": "2026-02-25T14:00:00-05:00",
            },
        },
        {
            "type": "Feature",
            "geometry": None,
            "properties": {
                "id": "urn:oid:nws-test-2",
                "event": "Winter Storm Watch",
                "headline": "Winter Storm Watch for East Tennessee",
                "description": "Heavy snow expected...",
                "severity": "Moderate",
                "areaDesc": "East Tennessee",
                "onset": "2026-02-26T06:00:00-05:00",
            },
        },
    ],
}


class TestNwsSource:
    @pytest.mark.asyncio
    async def test_fetch_parses_polygon_alert(self):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = _SAMPLE_RESPONSE

        with patch("src.sources.nws.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = NwsConfig(enabled=True, zone_codes=["TNZ063"])
            source = NwsSource(config, "TestAgent/1.0")
            incidents = await source.fetch()

        assert len(incidents) == 2

        # Polygon alert — should have centroid coordinates
        polygon_alert = incidents[0]
        assert polygon_alert.structured is True
        assert polygon_alert.lat is not None
        assert abs(polygon_alert.lat - 35.98) < 0.05  # centroid of test polygon
        assert abs(polygon_alert.lon - (-83.9)) < 0.05
        assert polygon_alert.severity == "critical"
        assert polygon_alert.category == "natural_disaster"  # tornado warning
        assert polygon_alert.source_id == "urn:oid:nws-test-1"

        # Null geometry alert — structured=False (needs geocoding)
        null_geo = incidents[1]
        assert null_geo.structured is False
        assert null_geo.lat is None

    @pytest.mark.asyncio
    async def test_disabled_returns_empty(self):
        config = NwsConfig(enabled=False)
        source = NwsSource(config, "TestAgent/1.0")
        assert await source.fetch() == []

    @pytest.mark.asyncio
    async def test_empty_zones_returns_empty(self):
        config = NwsConfig(enabled=True, zone_codes=[])
        source = NwsSource(config, "TestAgent/1.0")
        assert await source.fetch() == []

    @pytest.mark.asyncio
    async def test_point_geometry_parsed(self):
        """Point geometry should use coordinates directly (not centroid)."""
        point_response = {
            "features": [{
                "type": "Feature",
                "geometry": {
                    "type": "Point",
                    "coordinates": [-83.95, 35.97],
                },
                "properties": {
                    "id": "urn:oid:nws-point-1",
                    "event": "Flash Flood Warning",
                    "headline": "Flash Flood Warning",
                    "description": "Flash flooding expected",
                    "severity": "Severe",
                    "onset": "2026-02-26T10:00:00-05:00",
                },
            }],
        }
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = point_response

        with patch("src.sources.nws.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = NwsConfig(enabled=True, zone_codes=["TNZ063"])
            source = NwsSource(config, "TestAgent/1.0")
            incidents = await source.fetch()

        assert len(incidents) == 1
        assert incidents[0].structured is True
        assert abs(incidents[0].lat - 35.97) < 0.01
        assert abs(incidents[0].lon - (-83.95)) < 0.01

    @pytest.mark.asyncio
    async def test_zone_fetch_failure_continues(self):
        """A failing zone should not prevent other zones from being fetched."""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = _SAMPLE_RESPONSE

        call_count = 0

        async def get_with_failure(url, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise Exception("Network error")
            return mock_resp

        with patch("src.sources.nws.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get = AsyncMock(side_effect=get_with_failure)
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            config = NwsConfig(enabled=True, zone_codes=["BAD_ZONE", "TNZ063"])
            source = NwsSource(config, "TestAgent/1.0")
            incidents = await source.fetch()

        # First zone fails, second succeeds
        assert len(incidents) == 2


class TestParseTimestamp:
    def test_valid_iso(self):
        result = _parse_timestamp("2026-02-26T10:00:00-05:00")
        assert result.year == 2026
        assert result.tzinfo is not None

    def test_none_returns_now(self):
        result = _parse_timestamp(None)
        assert (datetime.now(timezone.utc) - result).total_seconds() < 5

    def test_invalid_string_returns_now(self):
        result = _parse_timestamp("not-a-date")
        assert (datetime.now(timezone.utc) - result).total_seconds() < 5


class TestPolygonCentroid:
    def test_empty_coordinates(self):
        assert _polygon_centroid([]) is None

    def test_empty_ring(self):
        assert _polygon_centroid([[]]) is None

    def test_valid_polygon(self):
        coords = [[[-84.0, 35.9], [-83.8, 35.9], [-83.8, 36.1], [-84.0, 36.1], [-84.0, 35.9]]]
        lat, lon = _polygon_centroid(coords)
        assert abs(lat - 35.98) < 0.05
        assert abs(lon - (-83.9)) < 0.05
