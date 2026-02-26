from __future__ import annotations

import asyncio
import logging
import os
import sys
from datetime import datetime, timedelta, timezone

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from src.analysis.ai_analyzer import AiAnalyzer
from src.analysis.geocoder import Geocoder
from src.analysis.keyword_filter import KeywordFilter
from src.config import TrackerConfig, load_config
from src.cot.builder import build_cot
from src.cot.fts_client import FtsClient
from src.dedup import Deduplicator
from src.models import AnalyzedIncident, RawIncident
from src.sources.base import Source
from src.sources.dc_crime import DcCrimeSource
from src.sources.nws import NwsSource
from src.sources.rss import RssSource
from src.sources.usgs import UsgsSource
from src.sources.reddit import RedditSource
from src.sources.stopice import StopIceSource
from src.sources.waze import WazeSource

logger = logging.getLogger("incident-tracker")


def _build_sources(config: TrackerConfig) -> list[Source]:
    sources: list[Source] = []
    if config.sources.rss:
        sources.append(RssSource(config.sources.rss))
    if config.sources.nws.enabled:
        sources.append(NwsSource(config.sources.nws, config.nominatim_user_agent))
    if config.sources.usgs.enabled:
        sources.append(UsgsSource(config.sources.usgs))
    if config.sources.dc_crime.enabled:
        sources.append(DcCrimeSource(config.sources.dc_crime))
    if config.sources.waze.enabled:
        sources.append(WazeSource(config.sources.waze, config.geo_filter))
    if config.sources.reddit.enabled:
        sources.append(RedditSource(config.sources.reddit))
    if config.sources.stopice.enabled:
        sources.append(StopIceSource(config.sources.stopice, config.geo_filter))
    return sources


def _to_analyzed(
    raw: RawIncident, config: TrackerConfig,
    ai_result: dict | None = None,
) -> AnalyzedIncident | None:
    """Convert a RawIncident to AnalyzedIncident using AI result or source data."""
    if raw.structured:
        cat_name = raw.category or "natural_disaster"
        cat_def = config.get_category(cat_name)
        if cat_def is None:
            return None
        return AnalyzedIncident(
            source_name=raw.source_name,
            title=raw.title,
            summary=raw.description[:200],
            url=raw.url,
            published=raw.published,
            lat=raw.lat,
            lon=raw.lon,
            category=cat_name,
            severity=raw.severity or "unknown",
            cot_type=cat_def.cot_type,
            stale_minutes=cat_def.stale_minutes,
        )

    if ai_result is None:
        return None

    cat_name = ai_result.get("category", "")
    cat_def = config.get_category(cat_name)
    if cat_def is None:
        logger.warning("AI returned unknown category '%s', skipping", cat_name)
        return None

    return AnalyzedIncident(
        source_name=raw.source_name,
        title=raw.title,
        summary=ai_result.get("summary", raw.title),
        url=raw.url,
        published=raw.published,
        lat=0.0,  # Filled by geocoder
        lon=0.0,
        category=cat_name,
        severity=ai_result.get("severity", "unknown"),
        cot_type=cat_def.cot_type,
        stale_minutes=cat_def.stale_minutes,
    )


async def poll_cycle(
    config: TrackerConfig,
    sources: list[Source],
    dedup: Deduplicator,
    kw_filter: KeywordFilter,
    ai_analyzer: AiAnalyzer,
    geocoder: Geocoder,
    fts: FtsClient,
    replay_buffer: dict[str, tuple[str, datetime]] | None = None,
) -> None:
    """Execute one full poll-analyze-inject cycle.

    replay_buffer: uid → (cot_xml, expiry_utc). Incidents are resent each
    cycle until they expire, keeping markers alive across browser refreshes.
    Node-RED handles opacity fading based on CoT stale timestamps.
    """
    if replay_buffer is None:
        replay_buffer = {}

    now = datetime.now(timezone.utc)

    # Prune expired entries from the replay buffer
    expired_uids = [
        uid for uid, (_, expiry) in replay_buffer.items() if now >= expiry
    ]
    for uid in expired_uids:
        del replay_buffer[uid]
    if expired_uids:
        logger.debug("Pruned %d expired incidents from replay buffer", len(expired_uids))

    # 1. Fetch from all sources concurrently
    results = await asyncio.gather(
        *[source.fetch() for source in sources],
        return_exceptions=True,
    )
    raw_all: list[RawIncident] = []
    for i, result in enumerate(results):
        if isinstance(result, Exception):
            logger.error("Source '%s' failed: %s", sources[i].name, result)
            continue
        raw_all.extend(result)

    # 2. Dedup
    unique = dedup.filter(raw_all) if raw_all else []

    # 3. Split structured vs unstructured
    structured = [i for i in unique if i.structured]
    unstructured = [i for i in unique if not i.structured]

    # 4. Process unstructured (RSS) through keyword filter + AI
    kw_matched = kw_filter.filter(unstructured)

    analyzed: list[AnalyzedIncident] = []

    # 4a. Structured incidents: geo-filter + category mapping
    for raw in structured:
        if raw.lat is not None and raw.lon is not None:
            if not geocoder.within_area(raw.lat, raw.lon):
                continue
        inc = _to_analyzed(raw, config)
        if inc:
            analyzed.append(inc)

    # 4b. Unstructured incidents: AI analyze + geocode
    for raw in kw_matched:
        ai_result = await ai_analyzer.analyze(raw)
        inc = _to_analyzed(raw, config, ai_result)
        if inc is None:
            continue

        # Geocode the location from AI
        location_text = ai_result.get("location", "") if ai_result else ""
        coords = await geocoder.geocode(location_text)
        if coords is None:
            logger.debug("Could not geocode '%s' for '%s'", location_text[:60], raw.title[:60])
            continue
        inc.lat, inc.lon = coords
        analyzed.append(inc)

    # 5. Build CoT for new incidents and add to replay buffer.
    #    Expiry = stale + fade period (fade period equals stale duration).
    new_count = 0
    for inc in analyzed:
        cot_xml = build_cot(inc, config.callsign_prefix, config.display_timezone)
        expiry = now + timedelta(minutes=inc.stale_minutes * 2)
        replay_buffer[inc.uid] = (cot_xml, expiry)
        new_count += 1

    # 6. Send entire replay buffer to FTS (new + previously buffered).
    #    This keeps markers alive across browser refreshes and Node-RED
    #    restarts. Node-RED calculates opacity from CoT timestamps.
    sent = 0
    for uid, (cot_xml, _) in replay_buffer.items():
        if await fts.send(cot_xml):
            sent += 1
        await asyncio.sleep(0.05)

    logger.info(
        "Cycle complete: %d fetched, %d unique, %d structured, "
        "%d kw-matched, %d new → %d total sent (buffer: %d)",
        len(raw_all), len(unique), len(structured),
        len(kw_matched), new_count, sent, len(replay_buffer),
    )


async def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    config_path = os.environ.get("CONFIG_PATH", "/app/config.yaml")
    logger.info("Loading config from %s", config_path)
    config = load_config(config_path)

    sources = _build_sources(config)
    if not sources:
        logger.error("No sources configured. Enable at least one source in config.yaml.")
        sys.exit(1)
    logger.info("Sources: %s", [s.name for s in sources])

    dedup = Deduplicator(config.dedup_window_hours)
    kw_filter = KeywordFilter(config.keywords)
    ai_analyzer = AiAnalyzer(config.ai, config.categories)
    geocoder = Geocoder(config.nominatim_user_agent, config.geo_filter)
    fts = FtsClient(config.fts_host, config.fts_port)

    # Validate AI key early if enabled
    if config.ai.enabled:
        api_key = os.environ.get("ANTHROPIC_API_KEY")
        if not api_key:
            logger.error(
                "ai.enabled=true but ANTHROPIC_API_KEY not set. "
                "Set it in .env or disable AI in config.yaml."
            )
            sys.exit(1)
        logger.info("AI analysis enabled (model: %s, cap: %d/hr)",
                     config.ai.model, config.ai.max_calls_per_hour)
    else:
        logger.info("AI analysis disabled — structured sources only")

    # Connect to FTS
    logger.info("Connecting to FTS at %s:%d", config.fts_host, config.fts_port)
    await fts.connect()

    # Shared replay buffer — resends active incidents each cycle so markers
    # survive browser refreshes and Node-RED restarts.
    replay_buffer: dict[str, tuple[str, datetime]] = {}

    # Scheduler
    scheduler = AsyncIOScheduler()
    scheduler.add_job(
        poll_cycle,
        "interval",
        seconds=config.poll_interval_seconds,
        args=[config, sources, dedup, kw_filter, ai_analyzer, geocoder, fts,
              replay_buffer],
        max_instances=1,
        coalesce=True,
    )
    scheduler.start()
    logger.info("Scheduler started (interval: %ds)", config.poll_interval_seconds)

    # Run first cycle immediately
    try:
        await poll_cycle(config, sources, dedup, kw_filter, ai_analyzer, geocoder, fts,
                         replay_buffer)
    except Exception:
        logger.exception("Initial poll cycle failed")

    # Keep alive
    stop_event = asyncio.Event()
    try:
        await stop_event.wait()
    except (KeyboardInterrupt, SystemExit):
        logger.info("Shutting down")
    finally:
        scheduler.shutdown()
        await ai_analyzer.close()
        await fts.close()


if __name__ == "__main__":
    asyncio.run(main())
