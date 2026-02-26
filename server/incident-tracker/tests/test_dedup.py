import time
from datetime import datetime, timezone
from unittest.mock import patch

from src.dedup import Deduplicator
from src.models import RawIncident


def _make_incident(title: str, source_id: str | None = None) -> RawIncident:
    return RawIncident(
        source_name="test",
        title=title,
        description="",
        url="https://example.com",
        published=datetime.now(timezone.utc),
        source_id=source_id,
    )


class TestDeduplicator:
    def test_exact_title_blocked(self):
        dedup = Deduplicator(window_hours=24)
        incidents = [
            _make_incident("Shooting on Main Street"),
            _make_incident("Shooting on Main Street"),
        ]
        result = dedup.filter(incidents)
        assert len(result) == 1

    def test_source_id_dedup(self):
        dedup = Deduplicator(window_hours=24)
        incidents = [
            _make_incident("Alert A", source_id="nws-123"),
            _make_incident("Alert A (updated)", source_id="nws-123"),
        ]
        result = dedup.filter(incidents)
        assert len(result) == 1

    def test_fuzzy_match_above_threshold(self):
        dedup = Deduplicator(window_hours=24, similarity_threshold=0.7)
        incidents = [
            _make_incident("Shooting reported on Main Street downtown"),
            _make_incident("Shooting reported on Main Street in downtown area"),
        ]
        result = dedup.filter(incidents)
        assert len(result) == 1

    def test_fuzzy_match_below_threshold(self):
        dedup = Deduplicator(window_hours=24, similarity_threshold=0.9)
        incidents = [
            _make_incident("Shooting on Main Street"),
            _make_incident("Fire at warehouse on Elm Avenue"),
        ]
        result = dedup.filter(incidents)
        assert len(result) == 2

    def test_different_incidents_pass(self):
        dedup = Deduplicator(window_hours=24)
        incidents = [
            _make_incident("Fire on Elm Street"),
            _make_incident("Robbery at convenience store"),
            _make_incident("Earthquake M3.5 near Ridgecrest"),
        ]
        result = dedup.filter(incidents)
        assert len(result) == 3

    def test_window_expiry(self):
        dedup = Deduplicator(window_hours=1)
        inc1 = _make_incident("Shooting on Main Street")
        dedup.filter([inc1])

        # Simulate time passing beyond the window
        past_time = time.monotonic() - 3700  # >1 hour ago
        for key in dedup._title_seen:
            words, _ = dedup._title_seen[key]
            dedup._title_seen[key] = (words, past_time)

        # Same title should now pass (window expired)
        inc2 = _make_incident("Shooting on Main Street")
        result = dedup.filter([inc2])
        assert len(result) == 1

    def test_empty_title_not_deduped(self):
        dedup = Deduplicator(window_hours=24)
        incidents = [
            _make_incident(""),
            _make_incident(""),
        ]
        result = dedup.filter(incidents)
        assert len(result) == 2

    def test_across_batches(self):
        dedup = Deduplicator(window_hours=24)
        batch1 = [_make_incident("Shooting on Main Street")]
        batch2 = [_make_incident("Shooting on Main Street")]
        dedup.filter(batch1)
        result = dedup.filter(batch2)
        assert len(result) == 0
