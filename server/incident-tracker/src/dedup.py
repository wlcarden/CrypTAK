from __future__ import annotations

import logging
import time

from src.models import RawIncident

logger = logging.getLogger(__name__)


def _normalize_title(title: str) -> str:
    return title.strip().lower()


def _word_set(text: str) -> set[str]:
    return set(text.split())


def _jaccard_similarity(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    intersection = a & b
    union = a | b
    return len(intersection) / len(union)


class Deduplicator:
    """Sliding-window deduplication for incidents.

    Uses exact source ID matching for structured sources (NWS, USGS)
    and fuzzy title similarity for RSS.
    """

    def __init__(self, window_hours: int, similarity_threshold: float = 0.8) -> None:
        self._window_seconds = window_hours * 3600
        self._threshold = similarity_threshold
        # source_id -> first_seen timestamp
        self._id_seen: dict[str, float] = {}
        # normalized_title -> (word_set, first_seen)
        self._title_seen: dict[str, tuple[set[str], float]] = {}

    def _prune(self) -> None:
        """Remove entries older than the window."""
        cutoff = time.monotonic() - self._window_seconds
        self._id_seen = {k: v for k, v in self._id_seen.items() if v > cutoff}
        self._title_seen = {
            k: v for k, v in self._title_seen.items() if v[1] > cutoff
        }

    def is_duplicate(self, incident: RawIncident) -> bool:
        """Check if this incident is a duplicate of a recently seen one."""
        now = time.monotonic()

        # Exact ID match (structured sources)
        if incident.source_id:
            if incident.source_id in self._id_seen:
                return True
            self._id_seen[incident.source_id] = now

        # Fuzzy title match (RSS)
        norm_title = _normalize_title(incident.title)
        if not norm_title:
            return False

        words = _word_set(norm_title)

        for seen_title, (seen_words, _) in self._title_seen.items():
            if _jaccard_similarity(words, seen_words) >= self._threshold:
                return True

        self._title_seen[norm_title] = (words, now)
        return False

    def filter(self, incidents: list[RawIncident]) -> list[RawIncident]:
        """Remove duplicates from a list of incidents."""
        self._prune()
        unique: list[RawIncident] = []
        dupes = 0
        for inc in incidents:
            if self.is_duplicate(inc):
                dupes += 1
            else:
                unique.append(inc)
        if dupes > 0:
            logger.debug("Dedup: %d unique, %d duplicates removed", len(unique), dupes)
        return unique
