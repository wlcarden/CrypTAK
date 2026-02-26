from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
from uuid import uuid4


@dataclass
class RawIncident:
    """Output from a source adapter before analysis."""

    source_name: str
    title: str
    description: str
    url: str
    published: datetime
    lat: Optional[float] = None
    lon: Optional[float] = None
    category: Optional[str] = None
    severity: Optional[str] = None
    # True for structured sources (NWS, USGS) — bypasses AI pipeline
    structured: bool = False
    # Native source ID for exact dedup (NWS alert ID, USGS event ID)
    source_id: Optional[str] = None


@dataclass
class AnalyzedIncident:
    """Fully processed incident ready for CoT generation."""

    source_name: str
    title: str
    summary: str
    url: str
    published: datetime
    lat: float
    lon: float
    category: str
    severity: str
    cot_type: str
    stale_minutes: int
    uid: str = field(default_factory=lambda: f"incident-tracker-{uuid4()}")
