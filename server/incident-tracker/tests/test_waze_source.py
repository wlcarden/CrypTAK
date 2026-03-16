import json
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from src.config import GeoFilter, WazeConfig
from src.sources.waze import WazeSource, _SUBTYPE_LABELS, _DEFAULT_LABEL


def _make_geo(bbox=None):
    return GeoFilter(
        center_lat=38.9, center_lon=-77.1, radius_km=50,
        bbox=bbox,
    )


def _make_config(**overrides):
    defaults = {"enabled": True, "min_reliability": 3, "min_confidence": 0,
                "confirmed_threshold": 2}
    defaults.update(overrides)
    return WazeConfig(**defaults)


def _make_alert(**overrides):
    defaults = {
        "type": "POLICE",
        "subtype": "",
        "uuid": "abc-123",
        "reliability": 7,
        "confidence": 1,
        "nThumbsUp": 2,
        "street": "I-66 E",
        "city": "Fairfax, VA",
        "location": {"x": -77.31, "y": 38.85},
        "pubMillis": 1772117689000,
    }
    defaults.update(overrides)
    return defaults


def _mock_response(alerts, status=200):
    resp = MagicMock()
    resp.status_code = status
    resp.json.return_value = {"alerts": alerts}
    resp.raise_for_status = MagicMock()
    return resp


class TestWazeSource:
    @pytest.mark.asyncio
    async def test_basic_fetch(self):
        alerts = [_make_alert()]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert len(results) == 1
        inc = results[0]
        assert inc.source_name == "Waze"
        assert inc.lat == 38.85
        assert inc.lon == -77.31
        assert inc.structured is True
        assert inc.source_id == "waze-abc-123"
        assert inc.category == "police_sighting"

    @pytest.mark.asyncio
    async def test_low_thumbs_yields_advisory(self):
        alerts = [_make_alert(nThumbsUp=1)]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].category == "police_advisory"

    @pytest.mark.asyncio
    async def test_high_thumbs_yields_sighting(self):
        alerts = [_make_alert(nThumbsUp=5)]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].category == "police_sighting"

    @pytest.mark.asyncio
    async def test_title_with_street_and_city(self):
        alerts = [_make_alert(street="I-66 E", city="Fairfax, VA")]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].title == "Police Spotted: I-66 E, Fairfax, VA"

    @pytest.mark.asyncio
    async def test_title_subtype_hiding(self):
        alerts = [_make_alert(subtype="POLICE_HIDING", street="Rt 50")]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert "Hidden/Speed Trap" in results[0].title

    @pytest.mark.asyncio
    async def test_title_subtype_standing(self):
        alerts = [_make_alert(subtype="POLICE_STANDING", street="Chain Bridge Rd")]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert "Checkpoint" in results[0].title

    @pytest.mark.asyncio
    async def test_filters_non_police(self):
        alerts = [
            _make_alert(type="POLICE"),
            _make_alert(type="ACCIDENT", uuid="xyz"),
            _make_alert(type="JAM", uuid="zzz"),
        ]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert len(results) == 1

    @pytest.mark.asyncio
    async def test_filters_low_reliability(self):
        alerts = [
            _make_alert(reliability=7),
            _make_alert(reliability=1, uuid="low-rel"),
        ]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(min_reliability=3), _make_geo())
            results = await source.fetch()

        assert len(results) == 1
        assert results[0].source_id == "waze-abc-123"

    @pytest.mark.asyncio
    async def test_filters_low_confidence(self):
        alerts = [
            _make_alert(confidence=2),
            _make_alert(confidence=-1, uuid="low-conf"),
        ]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(min_confidence=0), _make_geo())
            results = await source.fetch()

        assert len(results) == 1

    @pytest.mark.asyncio
    async def test_skips_missing_coordinates(self):
        alerts = [_make_alert(location={})]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert len(results) == 0

    @pytest.mark.asyncio
    async def test_disabled_returns_empty(self):
        source = WazeSource(_make_config(enabled=False), _make_geo())
        results = await source.fetch()
        assert results == []

    @pytest.mark.asyncio
    async def test_api_failure_returns_empty(self):
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.side_effect = httpx.ConnectError("timeout")
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results == []

    @pytest.mark.asyncio
    async def test_bbox_from_geo_filter(self):
        bbox = (38.75, -77.52, 39.05, -76.80)
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response([])
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo(bbox=bbox))
            await source.fetch()

            call_kwargs = mock_client.get.call_args
            params = call_kwargs.kwargs.get("params") or call_kwargs[1].get("params")
            assert params["bottom"] == 38.75
            assert params["left"] == -77.52
            assert params["top"] == 39.05
            assert params["right"] == -76.80

    @pytest.mark.asyncio
    async def test_bbox_fallback_from_center_radius(self):
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response([])
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo(bbox=None))
            await source.fetch()

            call_kwargs = mock_client.get.call_args
            params = call_kwargs.kwargs.get("params") or call_kwargs[1].get("params")
            # center_lat=38.9, radius=50km, ~0.45 degree offset
            assert params["bottom"] < 38.9
            assert params["top"] > 38.9

    @pytest.mark.asyncio
    async def test_timestamp_parsing(self):
        alerts = [_make_alert(pubMillis=1772117689000)]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].published.tzinfo == timezone.utc
        assert results[0].published.year >= 2025

    @pytest.mark.asyncio
    async def test_description_includes_reliability(self):
        alerts = [_make_alert(reliability=8, nThumbsUp=3)]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert "8/10" in results[0].description
        assert "3 confirmations" in results[0].description

    @pytest.mark.asyncio
    async def test_title_street_only(self):
        alerts = [_make_alert(street="Rt 50", city="")]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].title == "Police Spotted: Rt 50"

    @pytest.mark.asyncio
    async def test_title_city_only(self):
        alerts = [_make_alert(street="", city="Arlington, VA")]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].title == "Police Spotted: Arlington, VA"

    @pytest.mark.asyncio
    async def test_title_no_street_no_city(self):
        alerts = [_make_alert(street="", city="")]
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response(alerts)
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results[0].title == "Police Spotted"

    def test_name(self):
        source = WazeSource(_make_config(), _make_geo())
        assert source.name == "Waze"


class TestWazeBackoff:
    def _mock_403(self):
        resp = MagicMock()
        resp.status_code = 403
        return resp

    @pytest.mark.asyncio
    async def test_403_triggers_backoff(self):
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = self._mock_403()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            results = await source.fetch()

        assert results == []
        assert source._blocked_until > 0

    @pytest.mark.asyncio
    async def test_skips_during_backoff(self):
        """Should not make an API call while blocked."""
        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = self._mock_403()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            await source.fetch()  # triggers backoff

            mock_client.get.reset_mock()
            results = await source.fetch()  # should skip

        assert results == []
        mock_client.get.assert_not_called()

    @pytest.mark.asyncio
    async def test_backoff_doubles_on_repeated_403(self):
        from src.sources.waze import _BACKOFF_BASE

        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = self._mock_403()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            await source.fetch()

        assert source._backoff == _BACKOFF_BASE * 2

    @pytest.mark.asyncio
    async def test_backoff_capped_at_max(self):
        from src.sources.waze import _BACKOFF_MAX

        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = self._mock_403()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            source._backoff = _BACKOFF_MAX
            source._blocked_until = 0  # allow fetch
            await source.fetch()

        assert source._backoff == _BACKOFF_MAX

    @pytest.mark.asyncio
    async def test_success_resets_backoff(self):
        from src.sources.waze import _BACKOFF_BASE

        with patch("src.sources.waze.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = _mock_response([_make_alert()])
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            source = WazeSource(_make_config(), _make_geo())
            source._backoff = 7200  # simulate previous failures
            results = await source.fetch()

        assert len(results) == 1
        assert source._backoff == _BACKOFF_BASE
