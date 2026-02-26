from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.config import DcCrimeConfig
from src.sources.dc_crime import DcCrimeSource, _offense_severity

# Epoch ms for 2026-02-26 12:00 UTC
_REPORT_TS = 1740571200000

_SAMPLE_RESPONSE = {
    "type": "FeatureCollection",
    "features": [
        {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-77.030, 38.924]},
            "properties": {
                "CCN": "26014810",
                "OFFENSE": "ASSAULT W/DANGEROUS WEAPON",
                "METHOD": "GUN",
                "BLOCK": "1300 - 1399 BLOCK OF FAIRMONT ST NW",
                "REPORT_DAT": _REPORT_TS,
                "LATITUDE": 38.9247,
                "LONGITUDE": -77.0310,
            },
        },
        {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-77.050, 38.910]},
            "properties": {
                "CCN": "26014811",
                "OFFENSE": "THEFT/OTHER",
                "METHOD": "OTHERS",
                "BLOCK": "600 - 699 BLOCK OF H ST NE",
                "REPORT_DAT": _REPORT_TS,
                "LATITUDE": 38.910,
                "LONGITUDE": -77.050,
            },
        },
        {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-77.02, 38.90]},
            "properties": {
                "CCN": "26014812",
                "OFFENSE": "HOMICIDE",
                "METHOD": "GUN",
                "BLOCK": "100 - 199 BLOCK OF K ST SE",
                "REPORT_DAT": _REPORT_TS,
                "LATITUDE": 38.900,
                "LONGITUDE": -77.020,
            },
        },
    ],
}


def _make_config(**overrides) -> DcCrimeConfig:
    defaults = {
        "enabled": True,
        "lookback_minutes": 60,
        "offenses": [],
        "offense_category_map": {
            "HOMICIDE": "violent_crime",
            "ASSAULT W/DANGEROUS WEAPON": "violent_crime",
        },
    }
    defaults.update(overrides)
    return DcCrimeConfig(**defaults)


def _mock_http(response_data):
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.raise_for_status = MagicMock()
    mock_resp.json.return_value = response_data

    client_instance = AsyncMock()
    client_instance.get.return_value = mock_resp
    client_instance.__aenter__ = AsyncMock(return_value=client_instance)
    client_instance.__aexit__ = AsyncMock(return_value=False)
    return client_instance


class TestDcCrimeSource:
    @pytest.mark.asyncio
    async def test_fetch_parses_all_incidents(self):
        with patch("src.sources.dc_crime.httpx.AsyncClient") as MockClient:
            MockClient.return_value = _mock_http(_SAMPLE_RESPONSE)
            source = DcCrimeSource(_make_config())
            incidents = await source.fetch()

        assert len(incidents) == 3

    @pytest.mark.asyncio
    async def test_offense_filter(self):
        config = _make_config(offenses=["HOMICIDE", "ASSAULT W/DANGEROUS WEAPON"])
        with patch("src.sources.dc_crime.httpx.AsyncClient") as MockClient:
            MockClient.return_value = _mock_http(_SAMPLE_RESPONSE)
            source = DcCrimeSource(config)
            incidents = await source.fetch()

        assert len(incidents) == 2
        offenses = {i.title.split(":")[0] for i in incidents}
        assert "THEFT/OTHER" not in offenses

    @pytest.mark.asyncio
    async def test_category_mapping(self):
        with patch("src.sources.dc_crime.httpx.AsyncClient") as MockClient:
            MockClient.return_value = _mock_http(_SAMPLE_RESPONSE)
            source = DcCrimeSource(_make_config())
            incidents = await source.fetch()

        by_ccn = {i.source_id: i for i in incidents}
        assert by_ccn["dc-crime-26014810"].category == "violent_crime"
        assert by_ccn["dc-crime-26014812"].category == "violent_crime"
        # Unmapped offense → default "property_crime"
        assert by_ccn["dc-crime-26014811"].category == "property_crime"

    @pytest.mark.asyncio
    async def test_coordinates_from_properties(self):
        with patch("src.sources.dc_crime.httpx.AsyncClient") as MockClient:
            MockClient.return_value = _mock_http(_SAMPLE_RESPONSE)
            source = DcCrimeSource(_make_config())
            incidents = await source.fetch()

        inc = incidents[0]
        assert inc.structured is True
        assert abs(inc.lat - 38.9247) < 0.001
        assert abs(inc.lon - (-77.0310)) < 0.001

    @pytest.mark.asyncio
    async def test_coordinates_fallback_to_geometry(self):
        """When LATITUDE/LONGITUDE are null, fall back to geometry coords."""
        data = {
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [-77.05, 38.91]},
                "properties": {
                    "CCN": "26099",
                    "OFFENSE": "ROBBERY",
                    "METHOD": "GUN",
                    "BLOCK": "Test Block",
                    "REPORT_DAT": _REPORT_TS,
                    "LATITUDE": None,
                    "LONGITUDE": None,
                },
            }],
        }
        with patch("src.sources.dc_crime.httpx.AsyncClient") as MockClient:
            MockClient.return_value = _mock_http(data)
            source = DcCrimeSource(_make_config())
            incidents = await source.fetch()

        assert len(incidents) == 1
        assert abs(incidents[0].lat - 38.91) < 0.001
        assert abs(incidents[0].lon - (-77.05)) < 0.001

    @pytest.mark.asyncio
    async def test_skips_incidents_without_coordinates(self):
        data = {
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "geometry": None,
                "properties": {
                    "CCN": "26099",
                    "OFFENSE": "ROBBERY",
                    "METHOD": "GUN",
                    "BLOCK": "Unknown",
                    "REPORT_DAT": _REPORT_TS,
                    "LATITUDE": None,
                    "LONGITUDE": None,
                },
            }],
        }
        with patch("src.sources.dc_crime.httpx.AsyncClient") as MockClient:
            MockClient.return_value = _mock_http(data)
            source = DcCrimeSource(_make_config())
            incidents = await source.fetch()

        assert len(incidents) == 0

    @pytest.mark.asyncio
    async def test_disabled_returns_empty(self):
        config = DcCrimeConfig(enabled=False)
        source = DcCrimeSource(config)
        assert await source.fetch() == []

    @pytest.mark.asyncio
    async def test_source_id_format(self):
        with patch("src.sources.dc_crime.httpx.AsyncClient") as MockClient:
            MockClient.return_value = _mock_http(_SAMPLE_RESPONSE)
            source = DcCrimeSource(_make_config())
            incidents = await source.fetch()

        assert incidents[0].source_id == "dc-crime-26014810"

    @pytest.mark.asyncio
    async def test_api_failure_returns_empty(self):
        client_instance = AsyncMock()
        client_instance.get.side_effect = Exception("Connection refused")
        client_instance.__aenter__ = AsyncMock(return_value=client_instance)
        client_instance.__aexit__ = AsyncMock(return_value=False)

        with patch("src.sources.dc_crime.httpx.AsyncClient") as MockClient:
            MockClient.return_value = client_instance
            source = DcCrimeSource(_make_config())
            incidents = await source.fetch()

        assert incidents == []


class TestOffenseSeverity:
    def test_high_severity(self):
        assert _offense_severity("HOMICIDE") == "high"
        assert _offense_severity("ASSAULT W/DANGEROUS WEAPON") == "high"
        assert _offense_severity("SEX ABUSE") == "high"

    def test_medium_severity(self):
        assert _offense_severity("ROBBERY") == "medium"
        assert _offense_severity("ARSON") == "medium"

    def test_low_severity(self):
        assert _offense_severity("THEFT/OTHER") == "low"
        assert _offense_severity("MOTOR VEHICLE THEFT") == "low"
        assert _offense_severity("BURGLARY") == "low"
