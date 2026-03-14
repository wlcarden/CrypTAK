#!/usr/bin/env python3
"""Field-to-Home CoT bridge service.

Connects to a local FreeTAKServer (on the field Pi) as a TCP client,
receives all relayed CoT events, and forwards them to a remote FTS
(on the home Unraid server) via Headscale VPN tunnel.

When the VPN tunnel is down, events are buffered in a ring buffer and
flushed on reconnect. Designed to run on the field base Pi alongside
the local FTS instance.
"""

from __future__ import annotations

import asyncio
import collections
import logging
import os
import signal
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("halow-bridge")

# --- Configuration ---

LOCAL_FTS_HOST = os.environ.get("LOCAL_FTS_HOST", "127.0.0.1")
LOCAL_FTS_PORT = int(os.environ.get("LOCAL_FTS_PORT", "8087"))
REMOTE_FTS_HOST = os.environ.get("REMOTE_FTS_HOST", "")
REMOTE_FTS_PORT = int(os.environ.get("REMOTE_FTS_PORT", "8087"))
CLIENT_UID = os.environ.get("CLIENT_UID", "CrypTAK-HaLowBridge")
BUFFER_MAX = int(os.environ.get("BUFFER_MAX", "5000"))
RECONNECT_DELAY = int(os.environ.get("RECONNECT_DELAY", "10"))
SA_REFRESH_MINUTES = 4

_DT_FMT = "%Y-%m-%dT%H:%M:%S.%fZ"


# --- CoT SA builder ---

def build_sa(uid: str = "", callsign: str = "HaLowBridge") -> str:
    """Self-identification CoT to register with FTS as a client."""
    uid = uid or CLIENT_UID
    now = datetime.now(timezone.utc)
    stale = now + timedelta(minutes=5)
    event = ET.Element("event", {
        "version": "2.0",
        "uid": uid,
        "type": "a-f-G-E-S",
        "time": now.strftime(_DT_FMT),
        "start": now.strftime(_DT_FMT),
        "stale": stale.strftime(_DT_FMT),
        "how": "m-g",
    })
    ET.SubElement(event, "point", {
        "lat": "0", "lon": "0", "hae": "0",
        "ce": "9999999", "le": "9999999",
    })
    detail = ET.SubElement(event, "detail")
    ET.SubElement(detail, "contact", {
        "callsign": callsign,
        "endpoint": "0.0.0.0:4242:tcp",
    })
    ET.SubElement(detail, "__group", {"name": "Cyan", "role": "Team Member"})
    ET.SubElement(detail, "takv", {
        "platform": "CrypTAK HaLowBridge",
        "device": "Server",
        "os": "1",
        "version": "1.0",
    })
    ET.SubElement(detail, "uid", {"Droid": callsign})
    return ET.tostring(event, encoding="unicode")


# --- CoT stream parsing ---

def event_key(cot_xml: str) -> str | None:
    """Extract uid:time key from a CoT event for dedup. Returns None if unparseable."""
    try:
        root = ET.fromstring(cot_xml)
        uid = root.get("uid", "")
        t = root.get("time", "")
        if uid and t:
            return f"{uid}:{t}"
    except ET.ParseError:
        pass
    return None


class CoTStreamReader:
    """Reads complete CoT events from a TCP byte stream.

    FTS sends CoT XML over TCP. Events may span multiple TCP frames or
    multiple events may arrive in a single frame. This reader accumulates
    bytes and yields complete <event...>...</event> blocks.
    """

    def __init__(self, reader: asyncio.StreamReader) -> None:
        self._reader = reader
        self._buf = b""

    async def read_event(self) -> str | None:
        """Read the next complete CoT event. Returns None on disconnect."""
        end_tag = b"</event>"
        while True:
            idx = self._buf.find(end_tag)
            if idx >= 0:
                end = idx + len(end_tag)
                chunk = self._buf[:end]
                self._buf = self._buf[end:]
                start = chunk.find(b"<event")
                if start >= 0:
                    return chunk[start:].decode("utf-8", errors="replace")
                continue
            try:
                data = await self._reader.read(4096)
            except (ConnectionError, OSError):
                return None
            if not data:
                return None
            self._buf += data


# --- Ring buffer with dedup ---

class CoTBuffer:
    """Bounded ring buffer for CoT events with dedup tracking."""

    def __init__(self, maxlen: int = 5000) -> None:
        self._buf: collections.deque[str] = collections.deque(maxlen=maxlen)
        self._sent_keys: collections.deque[str] = collections.deque(maxlen=maxlen * 2)
        self._sent_set: set[str] = set()

    def add(self, cot_xml: str) -> None:
        """Add a CoT event to the buffer."""
        self._buf.append(cot_xml)

    def flush(self) -> list[str]:
        """Return all buffered events, filtering out already-sent duplicates.

        Does NOT mark events as sent — caller must call mark_sent() after
        confirmed delivery. This allows re-buffering on partial send failure.
        """
        events = []
        while self._buf:
            cot = self._buf.popleft()
            key = event_key(cot)
            if key and key in self._sent_set:
                continue
            events.append(cot)
        return events

    def mark_sent(self, cot_xml: str) -> None:
        """Mark an event as sent for dedup during live forwarding."""
        key = event_key(cot_xml)
        if key:
            self._track_sent(key)

    def _track_sent(self, key: str) -> None:
        if key in self._sent_set:
            return
        if len(self._sent_keys) == self._sent_keys.maxlen:
            evicted = self._sent_keys[0]
            self._sent_set.discard(evicted)
        self._sent_keys.append(key)
        self._sent_set.add(key)

    @property
    def size(self) -> int:
        return len(self._buf)


# --- FTS TCP client (remote) ---

class RemoteFtsClient:
    """Async TCP client for sending CoT events to the remote FTS."""

    def __init__(self, host: str, port: int) -> None:
        self._host = host
        self._port = port
        self._writer: asyncio.StreamWriter | None = None
        self._backoff = 1.0
        self._last_sa: datetime | None = None

    @property
    def connected(self) -> bool:
        return self._writer is not None

    async def try_connect(self) -> bool:
        """Single connection attempt. Returns True on success."""
        try:
            _, self._writer = await asyncio.open_connection(
                self._host, self._port,
            )
            self._backoff = 1.0
            logger.info("Connected to remote FTS at %s:%d", self._host, self._port)
            await self._send_sa()
            return True
        except (ConnectionRefusedError, OSError, asyncio.TimeoutError) as exc:
            logger.debug("Remote FTS connect failed: %s", exc)
            self._backoff = min(self._backoff * 2, 60.0)
            return False

    async def _send_sa(self) -> None:
        sa = build_sa(uid=CLIENT_UID, callsign="HaLowBridge")
        self._writer.write((sa + "\n").encode("utf-8"))
        await self._writer.drain()
        self._last_sa = datetime.now(timezone.utc)
        logger.info("Sent SA to remote FTS")

    async def refresh_sa(self) -> None:
        """Re-send SA before stale time."""
        if self._last_sa is None:
            return
        elapsed = datetime.now(timezone.utc) - self._last_sa
        if elapsed > timedelta(minutes=SA_REFRESH_MINUTES):
            try:
                await self._send_sa()
            except (ConnectionResetError, BrokenPipeError, OSError):
                await self._close()

    async def send(self, cot_xml: str) -> bool:
        """Send a CoT event. Returns True on success."""
        if not self._writer:
            return False
        try:
            self._writer.write((cot_xml + "\n").encode("utf-8"))
            await self._writer.drain()
            return True
        except (ConnectionResetError, BrokenPipeError, OSError) as exc:
            logger.warning("Remote FTS send failed: %s", exc)
            await self._close()
            return False

    async def _close(self) -> None:
        if self._writer:
            try:
                self._writer.close()
                await self._writer.wait_closed()
            except Exception:
                pass
            self._writer = None

    async def close(self) -> None:
        await self._close()
        logger.info("Remote FTS client closed")


# --- Local FTS listener ---

async def connect_local_fts(
    host: str, port: int,
) -> tuple[CoTStreamReader, asyncio.StreamWriter]:
    """Connect to local FTS with exponential backoff. Returns reader + writer."""
    backoff = 1.0
    while True:
        try:
            reader, writer = await asyncio.open_connection(host, port)
            sa = build_sa(uid=f"{CLIENT_UID}-listener", callsign="HaLowBridge-RX")
            writer.write((sa + "\n").encode("utf-8"))
            await writer.drain()
            logger.info("Connected to local FTS at %s:%d", host, port)
            return CoTStreamReader(reader), writer
        except (ConnectionRefusedError, OSError) as exc:
            logger.warning(
                "Local FTS connect failed (%s), retry in %.0fs", exc, backoff,
            )
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60.0)


# --- Main ---

async def main() -> None:
    if not REMOTE_FTS_HOST:
        logger.error("REMOTE_FTS_HOST not set. Exiting.")
        return

    buf = CoTBuffer(maxlen=BUFFER_MAX)
    remote = RemoteFtsClient(REMOTE_FTS_HOST, REMOTE_FTS_PORT)

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()

    def on_signal():
        logger.info("Shutdown signal received")
        stop.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, on_signal)

    stream, local_writer = await connect_local_fts(LOCAL_FTS_HOST, LOCAL_FTS_PORT)

    remote_ok = await remote.try_connect()
    if remote_ok:
        logger.info("Remote FTS reachable — live forwarding mode")
    else:
        logger.info("Remote FTS unreachable — buffering mode")

    logger.info("HaLow bridge running (buffer max=%d)", BUFFER_MAX)

    async def reconnect_loop():
        """Periodically try to reconnect to remote FTS and flush buffer."""
        while not stop.is_set():
            await asyncio.sleep(RECONNECT_DELAY)
            if not remote.connected:
                if await remote.try_connect():
                    events = buf.flush()
                    if events:
                        logger.info(
                            "Flushing %d buffered events to remote FTS",
                            len(events),
                        )
                        for i, cot in enumerate(events):
                            if not await remote.send(cot):
                                for remaining in events[i:]:
                                    buf.add(remaining)
                                break
                            buf.mark_sent(cot)
            else:
                await remote.refresh_sa()

    reconnect_task = asyncio.create_task(reconnect_loop())

    try:
        while not stop.is_set():
            try:
                cot = await asyncio.wait_for(stream.read_event(), timeout=30.0)
            except asyncio.TimeoutError:
                continue

            if cot is None:
                logger.warning("Local FTS disconnected, reconnecting")
                try:
                    local_writer.close()
                    await local_writer.wait_closed()
                except Exception:
                    pass
                stream, local_writer = await connect_local_fts(
                    LOCAL_FTS_HOST, LOCAL_FTS_PORT,
                )
                continue

            if CLIENT_UID in cot:
                continue

            if remote.connected:
                if await remote.send(cot):
                    buf.mark_sent(cot)
                else:
                    buf.add(cot)
                    logger.info(
                        "Remote FTS lost — buffered event (buf=%d)", buf.size,
                    )
            else:
                buf.add(cot)
                if buf.size % 100 == 1:
                    logger.info("Buffering (remote down, buf=%d)", buf.size)
    finally:
        reconnect_task.cancel()
        try:
            await reconnect_task
        except asyncio.CancelledError:
            pass
        await remote.close()
        try:
            local_writer.close()
            await local_writer.wait_closed()
        except Exception:
            pass
        logger.info(
            "HaLow bridge stopped (%d events unsent in buffer)", buf.size,
        )


if __name__ == "__main__":
    asyncio.run(main())
