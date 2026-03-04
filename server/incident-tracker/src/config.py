from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional

import yaml
from pydantic import BaseModel, Field, field_validator, model_validator

logger = logging.getLogger(__name__)

# ── IFF Affiliation → CoT type-code character ────────────────────────────────
# Controls marker shape AND color on the TAK map (MIL-STD-2525C):
#   hostile  → red diamond       suspect  → yellow diamond
#   neutral  → green square      unknown  → yellow quatrefoil
#   friendly → blue rectangle
_AFFILIATION_MAP = {
    "hostile": "h",
    "suspect": "s",
    "neutral": "n",
    "unknown": "u",
    "friendly": "f",
    "pending": "p",
}

# ── Icon type → CoT type-code suffix ─────────────────────────────────────────
_ICON_MAP = {
    "law_enforcement": "G-I-i-l",   # fa-exclamation-triangle
    "raid": "G-U-C-V",              # fa-exclamation-circle
    "crime": "G-O-E",               # fa-crosshairs
    "surveillance": "G-I-R",        # fa-eye
    "medical": "G-I-i-h",           # fa-plus-square
    "fire": "G-I-i-f",              # fa-fire
    "natural": "G-E-N",             # fa-bolt
    "traffic": "G-I-i-d",           # fa-car
    "search": "G-O-S",              # fa-search
    "civil": "G-I-i-c",             # fa-bullhorn
    "general": "G-U-C",             # fa-question-circle
}


class GeoFilter(BaseModel):
    """Geographic bounding for incident relevance."""

    center_lat: float
    center_lon: float
    radius_km: float = 200.0
    # Bounding box overrides center+radius when set: (south, west, north, east)
    bbox: Optional[tuple[float, float, float, float]] = None


class RssFeed(BaseModel):
    name: str
    url: str
    enabled: bool = True


class NwsConfig(BaseModel):
    enabled: bool = False
    zone_codes: list[str] = Field(default_factory=list)


class UsgsConfig(BaseModel):
    enabled: bool = False
    min_magnitude: float = 3.0
    feed_window: str = "day"  # hour, day, week, month


class DcCrimeConfig(BaseModel):
    enabled: bool = False
    offenses: list[str] = Field(default_factory=list)
    lookback_minutes: int = 60
    offense_category_map: dict[str, str] = Field(default_factory=dict)


class WazeConfig(BaseModel):
    enabled: bool = False
    min_reliability: int = 3
    min_confidence: int = 0
    confirmed_threshold: int = 2


class RedditConfig(BaseModel):
    enabled: bool = False
    # Full-feed mode: monitor ALL new posts from these subs (high-signal subs)
    subreddits: list[str] = Field(default_factory=list)
    # Search-query mode: search these subs with a query string (noisy subs)
    search_subreddits: dict[str, str] = Field(default_factory=dict)
    lookback_minutes: int = 60


class StopIceConfig(BaseModel):
    enabled: bool = False
    # Minimum priority level to include: "unconfirmed", "sighting", "confirmed"
    min_priority: str = "unconfirmed"

    @field_validator("min_priority")
    @classmethod
    def validate_min_priority(cls, v: str) -> str:
        allowed = {"unconfirmed", "sighting", "confirmed"}
        if v not in allowed:
            raise ValueError(
                f"min_priority must be one of {sorted(allowed)}, got '{v}'"
            )
        return v


class SourcesConfig(BaseModel):
    rss: list[RssFeed] = Field(default_factory=list)
    nws: NwsConfig = Field(default_factory=NwsConfig)
    usgs: UsgsConfig = Field(default_factory=UsgsConfig)
    dc_crime: DcCrimeConfig = Field(default_factory=DcCrimeConfig)
    waze: WazeConfig = Field(default_factory=WazeConfig)
    reddit: RedditConfig = Field(default_factory=RedditConfig)
    stopice: StopIceConfig = Field(default_factory=StopIceConfig)

    @model_validator(mode="before")
    @classmethod
    def coerce_nulls(cls, data: dict) -> dict:
        """YAML parses commented-out lists as None. Coerce to empty defaults."""
        if isinstance(data, dict):
            if data.get("rss") is None:
                data["rss"] = []
        return data


class AiConfig(BaseModel):
    enabled: bool = False
    model: str = "claude-haiku-4-5-20251001"
    max_calls_per_hour: int = 100
    criteria_prompt: str = ""


class CategoryDef(BaseModel):
    name: str
    description: str
    affiliation: str = "unknown"
    icon_type: str = "general"
    stale_minutes: int = 60
    cot_type_override: str = ""

    @field_validator("affiliation")
    @classmethod
    def validate_affiliation(cls, v: str) -> str:
        if v not in _AFFILIATION_MAP:
            raise ValueError(
                f"affiliation must be one of {list(_AFFILIATION_MAP)}, got '{v}'"
            )
        return v

    @field_validator("icon_type")
    @classmethod
    def validate_icon_type(cls, v: str) -> str:
        if v not in _ICON_MAP:
            raise ValueError(
                f"icon_type must be one of {list(_ICON_MAP)}, got '{v}'"
            )
        return v

    @property
    def cot_type(self) -> str:
        if self.cot_type_override:
            return self.cot_type_override
        aff = _AFFILIATION_MAP[self.affiliation]
        icon = _ICON_MAP[self.icon_type]
        return f"a-{aff}-{icon}"


class TrackerConfig(BaseModel):
    poll_interval_seconds: int = 300
    dedup_window_hours: int = 24
    keywords: list[str] = Field(default_factory=list)
    geo_filter: GeoFilter
    sources: SourcesConfig = Field(default_factory=SourcesConfig)
    ai: AiConfig = Field(default_factory=AiConfig)
    categories: list[CategoryDef] = Field(default_factory=list)
    fts_host: str = "freetakserver"
    fts_port: int = 8087
    nominatim_user_agent: str = "CrypTAK-IncidentTracker/1.0"
    callsign_prefix: str = "INCIDENT"
    # IANA timezone for callsign timestamps (e.g. "America/New_York")
    display_timezone: str = "UTC"

    def get_category(self, name: str) -> Optional[CategoryDef]:
        """Look up a category definition by name (case-insensitive)."""
        lower = name.lower()
        for cat in self.categories:
            if cat.name.lower() == lower:
                return cat
        return None


def load_config(path: str = "/app/config.yaml") -> TrackerConfig:
    config_path = Path(path)
    local_path = config_path.parent / "config.local.yaml"
    if local_path.exists():
        logger.info("Using local config override: %s", local_path)
        config_path = local_path
    if not config_path.exists():
        raise FileNotFoundError(f"Config file not found: {config_path}")
    with open(config_path) as f:
        data = yaml.safe_load(f)
    if data is None:
        raise ValueError(f"Config file is empty: {config_path}")
    return TrackerConfig(**data)
