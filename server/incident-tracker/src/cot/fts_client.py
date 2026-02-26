from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

import xml.etree.ElementTree as ET

logger = logging.getLogger(__name__)

_INITIAL_BACKOFF = 1.0
_MAX_BACKOFF = 60.0
_BACKOFF_FACTOR = 2.0
_CLIENT_UID = "incident-tracker-client"
_SA_REFRESH_MINUTES = 4


def _build_sa_cot() -> str:
    """Build a self-identification CoT so FTS registers us as a client."""
    now = datetime.now(timezone.utc)
    stale = now + timedelta(minutes=5)
    fmt = "%Y-%m-%dT%H:%M:%S.%fZ"
    event = ET.Element("event", {
        "version": "2.0",
        "uid": _CLIENT_UID,
        "type": "a-f-G-U-C",
        "time": now.strftime(fmt),
        "start": now.strftime(fmt),
        "stale": stale.strftime(fmt),
        "how": "m-g",
    })
    ET.SubElement(event, "point", {
        "lat": "0", "lon": "0", "hae": "0",
        "ce": "9999999", "le": "9999999",
    })
    detail = ET.SubElement(event, "detail")
    ET.SubElement(detail, "contact", {"callsign": "IncidentTracker"})
    ET.SubElement(detail, "__group", {"name": "Cyan", "role": "Team Member"})
    return ET.tostring(event, encoding="unicode")


class FtsClient:
    """Async TCP client for injecting CoT events into FreeTAKServer.

    Maintains a persistent connection and reconnects with exponential
    backoff on failure.  Sends a self-identification CoT on connect so
    FTS registers us as a client and relays our events.
    """

    def __init__(self, host: str, port: int) -> None:
        self._host = host
        self._port = port
        self._reader: asyncio.StreamReader | None = None
        self._writer: asyncio.StreamWriter | None = None
        self._backoff = _INITIAL_BACKOFF
        self._last_sa: datetime | None = None

    async def connect(self) -> None:
        """Establish TCP connection to FTS, retrying with backoff."""
        while True:
            try:
                self._reader, self._writer = await asyncio.open_connection(
                    self._host, self._port,
                )
                self._backoff = _INITIAL_BACKOFF
                logger.info("Connected to FTS at %s:%d", self._host, self._port)
                await self._send_sa()
                return
            except (ConnectionRefusedError, OSError) as e:
                logger.warning(
                    "FTS connection failed (%s), retrying in %.0fs",
                    e, self._backoff,
                )
                await asyncio.sleep(self._backoff)
                self._backoff = min(self._backoff * _BACKOFF_FACTOR, _MAX_BACKOFF)

    async def _send_sa(self) -> None:
        """Send self-identification CoT to register with FTS."""
        sa = _build_sa_cot()
        self._writer.write((sa + "\n").encode("utf-8"))
        await self._writer.drain()
        self._last_sa = datetime.now(timezone.utc)
        logger.info("Sent self-identification CoT to FTS")

    async def _refresh_sa_if_needed(self) -> None:
        """Re-send SA before its stale time so FTS keeps us registered."""
        if self._last_sa is None:
            return
        elapsed = datetime.now(timezone.utc) - self._last_sa
        if elapsed > timedelta(minutes=_SA_REFRESH_MINUTES):
            await self._send_sa()

    async def send(self, cot_xml: str) -> bool:
        """Send a CoT XML event to FTS. Returns True on success."""
        if self._writer is None:
            await self.connect()

        await self._refresh_sa_if_needed()

        try:
            self._writer.write((cot_xml + "\n").encode("utf-8"))
            await self._writer.drain()
            return True
        except (ConnectionResetError, BrokenPipeError, OSError) as e:
            logger.warning("FTS send failed (%s), reconnecting", e)
            await self._close_writer()
            await self.connect()
            # Retry once after reconnect
            try:
                self._writer.write((cot_xml + "\n").encode("utf-8"))
                await self._writer.drain()
                return True
            except (ConnectionResetError, BrokenPipeError, OSError) as e2:
                logger.error("FTS send failed after reconnect: %s", e2)
                await self._close_writer()
                return False

    async def _close_writer(self) -> None:
        if self._writer is not None:
            try:
                self._writer.close()
                await self._writer.wait_closed()
            except Exception:
                pass
            self._writer = None
            self._reader = None

    async def close(self) -> None:
        """Shut down the TCP connection."""
        await self._close_writer()
        logger.info("FTS client closed")
