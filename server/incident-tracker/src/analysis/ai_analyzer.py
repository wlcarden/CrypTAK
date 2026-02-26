from __future__ import annotations

import json
import logging
import os
import time

from src.config import AiConfig, CategoryDef
from src.models import RawIncident

logger = logging.getLogger(__name__)


class AiAnalyzer:
    """Tier 1: Haiku relevance check + data extraction in a single API call."""

    def __init__(self, config: AiConfig, categories: list[CategoryDef]) -> None:
        self._config = config
        self._categories = categories
        self._call_count = 0
        self._hour_start = time.monotonic()
        self._client = None

    async def _get_client(self):
        """Lazy-init the Anthropic async client."""
        if self._client is None:
            import anthropic
            api_key = os.environ.get("ANTHROPIC_API_KEY")
            if not api_key:
                raise RuntimeError(
                    "ANTHROPIC_API_KEY not set. Either set it in .env or "
                    "disable AI in config.yaml (ai.enabled: false)."
                )
            self._client = anthropic.AsyncAnthropic(api_key=api_key)
        return self._client

    def _check_rate_limit(self) -> bool:
        """Returns True if we're within the hourly call cap."""
        now = time.monotonic()
        if now - self._hour_start >= 3600:
            self._call_count = 0
            self._hour_start = now
        return self._call_count < self._config.max_calls_per_hour

    def _build_system_prompt(self) -> str:
        cat_lines = "\n".join(
            f"  - {c.name}: {c.description}" for c in self._categories
        )
        return (
            f"{self._config.criteria_prompt}\n\n"
            f"Available incident categories:\n{cat_lines}\n\n"
            "Respond with ONLY a JSON object, no markdown fencing.\n"
            "If the article is NOT a relevant incident:\n"
            '  {"relevant": false}\n\n'
            "If it IS relevant, extract:\n"
            "{\n"
            '  "relevant": true,\n'
            '  "category": "<category name from list above>",\n'
            '  "location": "<most specific location mentioned (address, intersection, neighborhood + city)>",\n'
            '  "severity": "<low|medium|high|critical>",\n'
            '  "summary": "<one-sentence summary of the incident>"\n'
            "}"
        )

    async def analyze(self, incident: RawIncident) -> dict | None:
        """Analyze a single incident with AI.

        Returns extracted data dict if relevant, None if irrelevant or on error.
        """
        if not self._config.enabled:
            return None

        if not self._check_rate_limit():
            logger.warning("AI rate limit reached (%d/hr), skipping", self._config.max_calls_per_hour)
            return None

        client = await self._get_client()
        user_content = f"Title: {incident.title}\n\nArticle: {incident.description[:2000]}"

        try:
            self._call_count += 1
            message = await client.messages.create(
                model=self._config.model,
                max_tokens=300,
                system=self._build_system_prompt(),
                messages=[{"role": "user", "content": user_content}],
            )
            raw_text = message.content[0].text.strip()
            # Strip markdown fencing if present (Haiku sometimes adds it)
            if raw_text.startswith("```"):
                raw_text = raw_text.split("\n", 1)[-1]
                if "```" in raw_text:
                    raw_text = raw_text[:raw_text.rindex("```")]
                raw_text = raw_text.strip()
            result = json.loads(raw_text)

            if not result.get("relevant", False):
                return None

            required = {"category", "location", "severity", "summary"}
            if not required.issubset(result.keys()):
                logger.warning("AI response missing fields: %s", required - result.keys())
                return None

            return result

        except json.JSONDecodeError:
            logger.warning("AI returned non-JSON response for '%s'", incident.title[:80])
            return None
        except Exception:
            logger.exception("AI analysis failed for '%s'", incident.title[:80])
            return None

    async def close(self) -> None:
        """Clean up the HTTP client."""
        if self._client is not None:
            await self._client.close()
            self._client = None
