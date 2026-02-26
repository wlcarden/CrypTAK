import tempfile
from pathlib import Path

import pytest

from src.config import (
    CategoryDef,
    DcCrimeConfig,
    TrackerConfig,
    _AFFILIATION_MAP,
    _ICON_MAP,
    load_config,
)


_MINIMAL_YAML = """
geo_filter:
  center_lat: 35.96
  center_lon: -83.92
  radius_km: 50
"""

_FULL_YAML = """
poll_interval_seconds: 60
dedup_window_hours: 12
callsign_prefix: "OPS"
geo_filter:
  center_lat: 35.96
  center_lon: -83.92
  radius_km: 50
sources:
  rss:
    - name: TestFeed
      url: https://example.com/rss
  nws:
    enabled: true
    zone_codes: ["TNZ063"]
  usgs:
    enabled: true
    min_magnitude: 2.5
  dc_crime:
    enabled: true
    lookback_minutes: 120
    offenses: ["HOMICIDE", "ROBBERY"]
    offense_category_map:
      "HOMICIDE": "violent_crime"
      "ROBBERY": "violent_crime"
keywords:
  - shooting
  - fire
ai:
  enabled: false
  model: claude-haiku-4-5-20251001
  max_calls_per_hour: 50
  criteria_prompt: "Test prompt"
categories:
  - name: fire
    description: Structure fires
    affiliation: neutral
    icon_type: fire
    stale_minutes: 120
  - name: violent_crime
    description: Shootings and assaults
    affiliation: suspect
    icon_type: law_enforcement
    stale_minutes: 240
fts_host: localhost
fts_port: 9999
"""


def _write_yaml(content: str) -> str:
    f = tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False)
    f.write(content)
    f.close()
    return f.name


class TestLoadConfig:
    def test_minimal_config_loads(self):
        path = _write_yaml(_MINIMAL_YAML)
        config = load_config(path)
        assert config.geo_filter.center_lat == 35.96
        assert config.poll_interval_seconds == 300  # default
        assert config.ai.enabled is False
        assert config.sources.rss == []

    def test_full_config_loads(self):
        path = _write_yaml(_FULL_YAML)
        config = load_config(path)
        assert config.poll_interval_seconds == 60
        assert config.callsign_prefix == "OPS"
        assert len(config.sources.rss) == 1
        assert config.sources.nws.enabled is True
        assert config.sources.usgs.min_magnitude == 2.5
        assert config.sources.dc_crime.enabled is True
        assert config.sources.dc_crime.lookback_minutes == 120
        assert config.sources.dc_crime.offenses == ["HOMICIDE", "ROBBERY"]
        assert len(config.keywords) == 2
        assert config.fts_host == "localhost"

    def test_missing_geo_filter_raises(self):
        path = _write_yaml("poll_interval_seconds: 60\n")
        with pytest.raises(Exception):
            load_config(path)

    def test_missing_file_raises(self):
        with pytest.raises(FileNotFoundError):
            load_config("/nonexistent/path.yaml")

    def test_empty_file_raises(self):
        path = _write_yaml("")
        with pytest.raises(ValueError, match="empty"):
            load_config(path)

    def test_null_rss_coerced_to_empty_list(self):
        yaml_content = """
geo_filter:
  center_lat: 35.0
  center_lon: -83.0
sources:
  rss:
"""
        path = _write_yaml(yaml_content)
        config = load_config(path)
        assert config.sources.rss == []

    def test_invalid_affiliation_raises(self):
        yaml_content = """
geo_filter:
  center_lat: 35.0
  center_lon: -83.0
categories:
  - name: bad
    description: bad category
    affiliation: "evil"
"""
        path = _write_yaml(yaml_content)
        with pytest.raises(Exception):
            load_config(path)

    def test_invalid_icon_type_raises(self):
        yaml_content = """
geo_filter:
  center_lat: 35.0
  center_lon: -83.0
categories:
  - name: bad
    description: bad category
    icon_type: "spaceship"
"""
        path = _write_yaml(yaml_content)
        with pytest.raises(Exception):
            load_config(path)

    def test_local_config_override(self):
        """config.local.yaml takes precedence when it exists."""
        import tempfile, os
        tmpdir = tempfile.mkdtemp()
        main = os.path.join(tmpdir, "config.yaml")
        local = os.path.join(tmpdir, "config.local.yaml")

        with open(main, "w") as f:
            f.write(_MINIMAL_YAML)

        local_yaml = """
geo_filter:
  center_lat: 38.90
  center_lon: -77.16
  radius_km: 50
poll_interval_seconds: 120
"""
        with open(local, "w") as f:
            f.write(local_yaml)

        config = load_config(main)
        assert config.geo_filter.center_lat == 38.90
        assert config.poll_interval_seconds == 120


class TestCategoryDef:
    def test_cot_type_composition(self):
        cat = CategoryDef(
            name="test",
            description="test",
            affiliation="hostile",
            icon_type="law_enforcement",
        )
        assert cat.cot_type == "a-h-G-I-i-l"

    def test_cot_type_all_affiliations(self):
        for aff, code in _AFFILIATION_MAP.items():
            cat = CategoryDef(
                name="t", description="t", affiliation=aff, icon_type="general",
            )
            assert cat.cot_type == f"a-{code}-G-U-C"

    def test_cot_type_all_icon_types(self):
        for icon, suffix in _ICON_MAP.items():
            cat = CategoryDef(
                name="t", description="t", affiliation="unknown", icon_type=icon,
            )
            assert cat.cot_type == f"a-u-{suffix}"

    def test_cot_type_override(self):
        cat = CategoryDef(
            name="custom",
            description="custom",
            affiliation="hostile",
            icon_type="fire",
            cot_type_override="b-r-.-O-P",
        )
        assert cat.cot_type == "b-r-.-O-P"

    def test_defaults(self):
        cat = CategoryDef(name="t", description="t")
        assert cat.affiliation == "unknown"
        assert cat.icon_type == "general"
        assert cat.stale_minutes == 60
        assert cat.cot_type == "a-u-G-U-C"


class TestGetCategory:
    def test_case_insensitive_lookup(self):
        path = _write_yaml(_FULL_YAML)
        config = load_config(path)
        assert config.get_category("Fire") is not None
        assert config.get_category("FIRE") is not None
        assert config.get_category("fire") is not None

    def test_unknown_category_returns_none(self):
        path = _write_yaml(_FULL_YAML)
        config = load_config(path)
        assert config.get_category("nonexistent") is None


class TestDcCrimeConfig:
    def test_defaults(self):
        c = DcCrimeConfig()
        assert c.enabled is False
        assert c.offenses == []
        assert c.lookback_minutes == 60
        assert c.offense_category_map == {}

    def test_from_yaml(self):
        path = _write_yaml(_FULL_YAML)
        config = load_config(path)
        dc = config.sources.dc_crime
        assert dc.enabled is True
        assert dc.lookback_minutes == 120
        assert "HOMICIDE" in dc.offenses
        assert dc.offense_category_map["HOMICIDE"] == "violent_crime"
