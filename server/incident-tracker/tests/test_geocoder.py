import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.analysis.geocoder import Geocoder, _haversine_km
from src.config import GeoFilter


def _make_geocoder(**overrides) -> Geocoder:
    gf_defaults = {
        "center_lat": 35.96,
        "center_lon": -83.92,
        "radius_km": 100.0,
    }
    gf_defaults.update(overrides)
    geo_filter = GeoFilter(**gf_defaults)
    return Geocoder("TestAgent/1.0", geo_filter)


def _mock_httpx_response(json_data):
    """Create a MagicMock httpx response with sync .json() and .raise_for_status()."""
    resp = MagicMock()
    resp.status_code = 200
    resp.raise_for_status = MagicMock()
    resp.json.return_value = json_data
    return resp


class TestHaversine:
    def test_same_point(self):
        assert _haversine_km(35.96, -83.92, 35.96, -83.92) == 0.0

    def test_known_distance(self):
        # Knoxville to Nashville ~ 255 km
        dist = _haversine_km(35.96, -83.92, 36.16, -86.78)
        assert 250 < dist < 260


class TestWithinArea:
    def test_center_is_within(self):
        gc = _make_geocoder()
        assert gc.within_area(35.96, -83.92) is True

    def test_far_point_outside(self):
        gc = _make_geocoder(radius_km=10)
        assert gc.within_area(36.16, -86.78) is False

    def test_bbox_within(self):
        gc = _make_geocoder(bbox=(35.0, -84.5, 36.5, -83.0))
        assert gc.within_area(35.96, -83.92) is True

    def test_bbox_outside(self):
        gc = _make_geocoder(bbox=(35.0, -84.5, 36.5, -83.0))
        assert gc.within_area(37.0, -83.92) is False


class TestGeocode:
    @pytest.mark.asyncio
    async def test_successful_geocode(self):
        mock_resp = _mock_httpx_response([{"lat": "35.9606", "lon": "-83.9207"}])

        with patch("src.analysis.geocoder.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            gc = _make_geocoder()
            result = await gc.geocode("123 Main St, Knoxville, TN")

        assert result is not None
        lat, lon = result
        assert abs(lat - 35.9606) < 0.001
        assert abs(lon - (-83.9207)) < 0.001

    @pytest.mark.asyncio
    async def test_cache_hit(self):
        mock_resp = _mock_httpx_response([{"lat": "35.9606", "lon": "-83.9207"}])

        with patch("src.analysis.geocoder.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            gc = _make_geocoder()
            r1 = await gc.geocode("123 Main St, Knoxville, TN")
            r2 = await gc.geocode("123 Main St, Knoxville, TN")

        assert r1 == r2
        # Should have been called only once (second is cache hit)
        assert client_instance.get.call_count == 1

    @pytest.mark.asyncio
    async def test_empty_results_returns_none(self):
        mock_resp = _mock_httpx_response([])

        with patch("src.analysis.geocoder.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            gc = _make_geocoder()
            result = await gc.geocode("somewhere vague")

        assert result is None

    @pytest.mark.asyncio
    async def test_outside_area_returns_none(self):
        mock_resp = _mock_httpx_response([{"lat": "40.7128", "lon": "-74.0060"}])  # NYC

        with patch("src.analysis.geocoder.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            gc = _make_geocoder(radius_km=50)
            result = await gc.geocode("Times Square, NYC")

        assert result is None

    @pytest.mark.asyncio
    async def test_empty_input_returns_none(self):
        gc = _make_geocoder()
        assert await gc.geocode("") is None
        assert await gc.geocode("   ") is None

    @pytest.mark.asyncio
    async def test_http_error_returns_none(self):
        with patch("src.analysis.geocoder.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.side_effect = Exception("Connection refused")
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            gc = _make_geocoder()
            result = await gc.geocode("123 Main St, Knoxville, TN")

        assert result is None

    @pytest.mark.asyncio
    async def test_cache_expiry_refetches(self):
        """Expired cache entries should trigger a new API call."""
        mock_resp = _mock_httpx_response([{"lat": "35.9606", "lon": "-83.9207"}])

        with patch("src.analysis.geocoder.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            gc = _make_geocoder()
            await gc.geocode("123 Main St")

            # Expire the cache entry by backdating timestamp
            for key in gc._cache:
                lat, lon, _ = gc._cache[key]
                gc._cache[key] = (lat, lon, 0)  # epoch = expired

            await gc.geocode("123 Main St")

        # Should have been called twice (cache miss after expiry)
        assert client_instance.get.call_count == 2

    @pytest.mark.asyncio
    async def test_cache_pruning(self):
        """Cache should prune entries when exceeding 5000."""
        gc = _make_geocoder()
        # Fill cache with 5001 entries
        import time
        for i in range(5001):
            gc._cache[f"location-{i}"] = (35.96, -83.92, time.monotonic())

        mock_resp = _mock_httpx_response([{"lat": "35.9606", "lon": "-83.9207"}])

        with patch("src.analysis.geocoder.httpx.AsyncClient") as MockClient:
            client_instance = AsyncMock()
            client_instance.get.return_value = mock_resp
            client_instance.__aenter__ = AsyncMock(return_value=client_instance)
            client_instance.__aexit__ = AsyncMock(return_value=False)
            MockClient.return_value = client_instance

            await gc.geocode("new location")

        # Cache should have been pruned
        assert len(gc._cache) <= 5002  # 5001 + 1 new, minus pruned expired


class TestViewbox:
    def test_viewbox_with_bbox(self):
        gc = _make_geocoder(bbox=(35.0, -84.5, 36.5, -83.0))
        vb = gc._viewbox()
        assert vb is not None
        # Format: "west,north,east,south"
        parts = [float(x) for x in vb.split(",")]
        assert parts[0] == -84.5  # west
        assert parts[1] == 36.5   # north
        assert parts[2] == -83.0  # east
        assert parts[3] == 35.0   # south

    def test_viewbox_from_center_radius(self):
        gc = _make_geocoder()
        vb = gc._viewbox()
        assert vb is not None
        parts = [float(x) for x in vb.split(",")]
        # Should roughly surround the center (35.96, -83.92) within 100km
        assert parts[0] < -83.92  # west < center_lon
        assert parts[2] > -83.92  # east > center_lon
