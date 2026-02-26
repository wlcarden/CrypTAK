from __future__ import annotations

import logging
import re

from src.models import RawIncident

logger = logging.getLogger(__name__)


class KeywordFilter:
    """Tier 0 filter: fast keyword matching with zero API cost."""

    def __init__(self, keywords: list[str]) -> None:
        if keywords:
            escaped = [re.escape(k) for k in keywords]
            self._pattern = re.compile("|".join(escaped), re.IGNORECASE)
        else:
            self._pattern = None

    def filter(self, incidents: list[RawIncident]) -> list[RawIncident]:
        """Return incidents that match at least one keyword.

        If no keywords are configured, all incidents pass through.
        Structured incidents (NWS, USGS) always pass through — they don't
        need keyword filtering since they're already categorized.
        """
        if self._pattern is None:
            return incidents

        matched: list[RawIncident] = []
        for inc in incidents:
            if inc.structured:
                matched.append(inc)
                continue
            text = f"{inc.title} {inc.description}"
            if self._pattern.search(text):
                matched.append(inc)

        filtered_count = len(incidents) - len(matched)
        if filtered_count > 0:
            logger.debug("Keyword filter: %d passed, %d filtered", len(matched), filtered_count)
        return matched
