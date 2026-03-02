#!/usr/bin/env python3
"""Meshtastic -> FTS relay service.

Connects to a Meshtastic node (T-Beam bridge) via TCP or USB serial,
subscribes to position packets from the mesh network, converts them to
CoT (Cursor on Target) XML, and injects into FreeTAKServer over TCP.

Mesh nodes appear as friendly PLI markers on the TAK map.
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal
import socket
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

from meshtastic import mesh_pb2, portnums_pb2
from pubsub import pub

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("mesh-relay")

# --- Configuration ---

MESH_HOST = os.environ.get("MESH_HOST", "192.168.50.198")
MESH_SERIAL = os.environ.get("MESH_SERIAL", "")
FTS_HOST = os.environ.get("FTS_HOST", "freetakserver")
FTS_PORT = int(os.environ.get("FTS_PORT", "8087"))
CLIENT_UID = "CrypTAK-MeshRelay"
STALE_MINUTES = int(os.environ.get("STALE_MINUTES", "30"))
SA_REFRESH_MINUTES = 4
RECONNECT_DELAY = 10
MESH_HEARTBEAT_SECS = 30  # keep T-Beam TCP alive (firmware app-level idle ~130s)
POSITION_POLL_SECS = int(os.environ.get("POSITION_POLL_SECS", "120"))

# Known node IDs (without '!' prefix) treated as friendly (blue).
# All other mesh nodes appear as neutral (green).
_friendly_raw = os.environ.get("FRIENDLY_NODES", "")
FRIENDLY_NODES: set[str] = {
    n.strip().lstrip("!") for n in _friendly_raw.split(",") if n.strip()
}

_DT_FMT = "%Y-%m-%dT%H:%M:%S.%fZ"


# --- CoT builders ---

def _build_sa() -> str:
    """Self-identification CoT to register with FTS as a client."""
    now = datetime.now(timezone.utc)
    stale = now + timedelta(minutes=5)
    event = ET.Element("event", {
        "version": "2.0",
        "uid": CLIENT_UID,
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
        "callsign": "MeshRelay",
        "endpoint": "0.0.0.0:4242:tcp",
    })
    ET.SubElement(detail, "__group", {"name": "Cyan", "role": "Team Member"})
    ET.SubElement(detail, "takv", {
        "platform": "CrypTAK MeshRelay",
        "device": "Server",
        "os": "1",
        "version": "1.0",
    })
    ET.SubElement(detail, "uid", {"Droid": "MeshRelay"})
    return ET.tostring(event, encoding="unicode")


def build_pli(
    node_id: str,
    callsign: str,
    lat: float,
    lon: float,
    alt: float = 0.0,
    speed: float = 0.0,
    course: float = 0.0,
    battery: int = 0,
    hw_model: str = "",
) -> str:
    """Build a PLI CoT event for a mesh node position.

    Known nodes (in FRIENDLY_NODES) get type a-f-G-E-S (friendly/blue).
    Unknown nodes get type a-n-G-E-S (neutral/green).
    G-E-S = Ground Equipment Sensor (MIL-STD-2525).
    Includes <takv> and <uid Droid> so ATAK renders a proper PLI marker
    instead of a generic grey point.
    """
    friendly = node_id in FRIENDLY_NODES
    aff = "f" if friendly else "n"
    now = datetime.now(timezone.utc)
    stale = now + timedelta(minutes=STALE_MINUTES)
    event = ET.Element("event", {
        "version": "2.0",
        "uid": f"mesh-{node_id}",
        "type": f"a-{aff}-G-E-S",
        "time": now.strftime(_DT_FMT),
        "start": now.strftime(_DT_FMT),
        "stale": stale.strftime(_DT_FMT),
        "how": "m-g",
    })
    ET.SubElement(event, "point", {
        "lat": f"{lat:.6f}",
        "lon": f"{lon:.6f}",
        "hae": str(int(alt)),
        "ce": "9999999",
        "le": "9999999",
    })
    detail = ET.SubElement(event, "detail")
    ET.SubElement(detail, "contact", {
        "callsign": callsign,
        "endpoint": "0.0.0.0:4242:tcp",
    })
    group_name = "Cyan" if friendly else "Green"
    ET.SubElement(detail, "__group", {"name": group_name, "role": "Team Member"})
    ET.SubElement(detail, "takv", {
        "platform": "CrypTAK MeshRelay",
        "device": hw_model or "Meshtastic",
        "os": "1",
        "version": "1.0",
    })
    ET.SubElement(detail, "uid", {"Droid": callsign})
    if speed or course:
        ET.SubElement(detail, "track", {
            "speed": f"{speed:.1f}",
            "course": f"{course:.1f}",
        })
    if battery:
        ET.SubElement(detail, "status", {"battery": str(battery)})
    ET.SubElement(detail, "__meshtastic")
    return ET.tostring(event, encoding="unicode")


# --- FTS TCP client ---

class FtsClient:
    """Async TCP client that sends CoT XML to FreeTAKServer."""

    def __init__(self, host: str, port: int) -> None:
        self._host = host
        self._port = port
        self._writer: asyncio.StreamWriter | None = None
        self._backoff = 1.0
        self._last_sa: datetime | None = None

    async def connect(self) -> None:
        """Connect to FTS with exponential backoff, send SA on success."""
        while True:
            try:
                _, self._writer = await asyncio.open_connection(
                    self._host, self._port,
                )
                self._backoff = 1.0
                logger.info("Connected to FTS at %s:%d", self._host, self._port)
                await self._send_sa()
                return
            except (ConnectionRefusedError, OSError) as exc:
                logger.warning(
                    "FTS connect failed (%s), retry in %.0fs", exc, self._backoff,
                )
                await asyncio.sleep(self._backoff)
                self._backoff = min(self._backoff * 2, 60.0)

    async def _send_sa(self) -> None:
        sa = _build_sa()
        self._writer.write((sa + "\n").encode("utf-8"))
        await self._writer.drain()
        self._last_sa = datetime.now(timezone.utc)
        logger.info("Sent SA to FTS")

    async def refresh_sa(self) -> None:
        """Re-send SA before stale time so FTS keeps us registered."""
        if self._last_sa is None:
            return
        elapsed = datetime.now(timezone.utc) - self._last_sa
        if elapsed > timedelta(minutes=SA_REFRESH_MINUTES):
            try:
                await self._send_sa()
            except (ConnectionResetError, BrokenPipeError, OSError) as exc:
                logger.warning("SA refresh failed (%s), will reconnect on next send", exc)
                await self._close()

    async def send(self, cot_xml: str) -> bool:
        """Send a CoT event to FTS. Returns True on success."""
        if self._writer is None:
            await self.connect()
        await self.refresh_sa()
        try:
            self._writer.write((cot_xml + "\n").encode("utf-8"))
            await self._writer.drain()
            return True
        except (ConnectionResetError, BrokenPipeError, OSError) as exc:
            logger.warning("FTS send failed (%s), reconnecting", exc)
            await self._close()
            await self.connect()
            try:
                self._writer.write((cot_xml + "\n").encode("utf-8"))
                await self._writer.drain()
                return True
            except (ConnectionResetError, BrokenPipeError, OSError) as exc2:
                logger.error("FTS send failed after reconnect: %s", exc2)
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
        logger.info("FTS client closed")


# --- Meshtastic listener ---

def _seed_from_nodedb(iface, queue, loop, max_age_secs: int = 0):
    """Push cached positions from the T-Beam's nodedb into the queue.

    The meshtastic firmware doesn't forward received position packets
    to the serial/TCP API if the position data hasn't changed (it
    updates lastHeard internally but suppresses duplicate content).
    This means fixed-position nodes never generate pubsub events after
    the initial boot broadcast.

    We compensate by periodically re-reading the nodedb and pushing
    PLI for nodes that are still alive.  The lastHeard timestamp tells
    us when the T-Beam's radio last received ANY packet from a node.
    If max_age_secs > 0, only nodes heard within that window are
    included — nodes that go offline will be skipped and their TAK
    markers will naturally expire via the CoT stale timeout.
    """
    my_node_num = getattr(
        getattr(iface, "myInfo", None), "my_node_num", None,
    )
    now_epoch = int(time.time())
    seeded = 0
    for nid, node in list(iface.nodes.items()):
        if node.get("num") == my_node_num:
            continue

        # Skip nodes not heard recently (they're offline).
        # Also skip nodes with lastHeard=0 (learned via gossip, never
        # directly heard by this radio).
        if max_age_secs > 0:
            last_heard = node.get("lastHeard", 0) or 0
            if not last_heard or (now_epoch - last_heard) > max_age_secs:
                continue

        pos = node.get("position", {})
        lat = pos.get("latitude", 0.0) or 0.0
        lon = pos.get("longitude", 0.0) or 0.0
        if lat == 0.0 and lon == 0.0:
            lat_i = pos.get("latitudeI", 0) or 0
            lon_i = pos.get("longitudeI", 0) or 0
            if lat_i and lon_i:
                lat = lat_i / 1e7
                lon = lon_i / 1e7
        if lat == 0.0 and lon == 0.0:
            continue
        user = node.get("user", {})
        callsign = user.get("longName") or user.get("shortName") or nid
        node_id = nid.replace("!", "")
        battery = 0
        dm = node.get("deviceMetrics", {})
        if dm:
            battery = int(dm.get("batteryLevel", 0) or 0)
        data = {
            "node_id": node_id,
            "callsign": callsign,
            "lat": lat,
            "lon": lon,
            "alt": float(pos.get("altitude", 0) or 0),
            "speed": 0.0,
            "course": 0.0,
            "battery": battery,
            "hw_model": user.get("hwModel", ""),
        }
        loop.call_soon_threadsafe(queue.put_nowait, data)
        seeded += 1
        logger.debug(
            "Seeded from nodedb: %s at %.6f, %.6f", callsign, lat, lon,
        )
    if seeded:
        logger.info("Refreshed %d positions from nodedb", seeded)


def _mesh_thread(queue: asyncio.Queue, loop: asyncio.AbstractEventLoop):
    """Background thread: connect to mesh node with auto-reconnect.

    Subscribes to position packets and pushes them onto the asyncio
    queue. Automatically reconnects when the mesh node reboots or
    the TCP/serial connection drops.
    """

    def on_position(packet, interface):
        try:
            decoded = packet.get("decoded", {})
            position = decoded.get("position", {})

            lat = position.get("latitude", 0.0)
            lon = position.get("longitude", 0.0)
            if lat == 0.0 and lon == 0.0:
                # Fall back to integer fields (raw protobuf)
                lat_i = position.get("latitudeI", 0)
                lon_i = position.get("longitudeI", 0)
                if lat_i and lon_i:
                    lat = lat_i / 1e7
                    lon = lon_i / 1e7

            # Skip null-island (no GPS fix)
            if lat == 0.0 and lon == 0.0:
                return

            from_id = packet.get("fromId", str(packet.get("from", "unknown")))
            node_info = interface.nodes.get(from_id, {})
            user = node_info.get("user", {})
            callsign = (
                user.get("longName")
                or user.get("shortName")
                or from_id
            )

            battery = 0
            device_metrics = node_info.get("deviceMetrics", {})
            if device_metrics:
                battery = int(device_metrics.get("batteryLevel", 0) or 0)

            node_id = from_id.replace("!", "")
            hw_model = user.get("hwModel", "")

            data = {
                "node_id": node_id,
                "callsign": callsign,
                "lat": lat,
                "lon": lon,
                "alt": float(position.get("altitude", 0) or 0),
                "speed": float(position.get("groundSpeed", 0) or 0),
                "course": float(position.get("groundTrack", 0) or 0),
                "battery": battery,
                "hw_model": hw_model,
            }
            loop.call_soon_threadsafe(queue.put_nowait, data)
            logger.info(
                "Position from %s: %.6f, %.6f alt=%dm",
                callsign, lat, lon, int(data["alt"]),
            )
        except Exception:
            logger.exception("Error processing position packet")

    # Global subscription — survives interface reconnects because
    # PyPubSub topics are not bound to a specific interface instance.
    pub.subscribe(on_position, "meshtastic.receive.position")
    logger.info("Subscribed to mesh position packets")

    while True:
        disconnected = threading.Event()

        def on_disconnect(interface=None):
            disconnected.set()

        iface = None
        try:
            if MESH_SERIAL:
                from meshtastic.serial_interface import SerialInterface
                logger.info("Connecting to Meshtastic via serial: %s", MESH_SERIAL)
                iface = SerialInterface(MESH_SERIAL)
            else:
                from meshtastic.tcp_interface import TCPInterface
                logger.info("Connecting to Meshtastic via TCP: %s", MESH_HOST)
                iface = TCPInterface(hostname=MESH_HOST)

            # T-Beam firmware has an app-level idle timeout (~130s) that
            # only resets on real Meshtastic protocol traffic. TCP keepalive
            # probes alone don't prevent it. Two layers:
            # 1. TCP keepalive — prevents OS-level socket death
            # 2. Meshtastic heartbeat every 30s — resets firmware idle timer
            if hasattr(iface, "socket") and iface.socket:
                iface.socket.setsockopt(
                    socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1,
                )
                iface.socket.setsockopt(
                    socket.IPPROTO_TCP, socket.TCP_KEEPIDLE,
                    MESH_HEARTBEAT_SECS,
                )
                iface.socket.setsockopt(
                    socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 10,
                )
                iface.socket.setsockopt(
                    socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 5,
                )

            logger.info(
                "Mesh node connected, heartbeat every %ds, position poll every %ds",
                MESH_HEARTBEAT_SECS, POSITION_POLL_SECS,
            )

            # Seed initial PLI from T-Beam's cached nodedb so markers
            # appear immediately without waiting for the first broadcast.
            # No age filter on initial seed — show all known nodes.
            _seed_from_nodedb(iface, queue, loop)

            pub.subscribe(on_disconnect, "meshtastic.connection.lost")

            last_poll = time.monotonic()
            my_node_num = getattr(
                getattr(iface, "myInfo", None), "my_node_num", None,
            )

            while not disconnected.wait(timeout=MESH_HEARTBEAT_SECS):
                try:
                    iface.sendHeartbeat()
                except Exception:
                    logger.warning("Heartbeat failed, connection likely dead")
                    break

                # Refresh PLI from nodedb and poll remote nodes.
                # Meshtastic firmware suppresses position packets to the
                # API when the data hasn't changed, so fixed-position nodes
                # never generate pubsub events after boot. We re-read the
                # nodedb and only include nodes heard within STALE_MINUTES,
                # so offline nodes' markers expire naturally.
                now = time.monotonic()
                if now - last_poll >= POSITION_POLL_SECS:
                    last_poll = now
                    _seed_from_nodedb(
                        iface, queue, loop,
                        max_age_secs=STALE_MINUTES * 60,
                    )
                    for nid in list(iface.nodes):
                        node = iface.nodes[nid]
                        if node.get("num") == my_node_num:
                            continue
                        try:
                            iface.sendData(
                                mesh_pb2.Position(),
                                destinationId=nid,
                                portNum=portnums_pb2.PortNum.POSITION_APP,
                                wantResponse=True,
                            )
                        except Exception as exc:
                            logger.debug("Position poll to %s failed: %s", nid, exc)

            pub.unsubscribe(on_disconnect, "meshtastic.connection.lost")
            logger.warning(
                "Mesh connection lost, reconnecting in %ds", RECONNECT_DELAY,
            )
        except Exception as exc:
            logger.warning("Mesh connect failed: %s", exc)

        if iface:
            try:
                iface.close()
            except Exception:
                pass

        time.sleep(RECONNECT_DELAY)


# --- Main loop ---

async def main() -> None:
    fts = FtsClient(FTS_HOST, FTS_PORT)
    await fts.connect()

    queue: asyncio.Queue = asyncio.Queue()
    loop = asyncio.get_running_loop()

    # Daemon thread auto-reconnects to mesh node independently
    mesh = threading.Thread(
        target=_mesh_thread, args=(queue, loop), daemon=True,
    )
    mesh.start()

    stop = asyncio.Event()

    def on_signal():
        logger.info("Shutdown signal received")
        stop.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, on_signal)

    logger.info("Mesh relay running")

    try:
        while not stop.is_set():
            try:
                data = await asyncio.wait_for(queue.get(), timeout=30.0)
            except asyncio.TimeoutError:
                try:
                    await fts.refresh_sa()
                except Exception:
                    logger.exception("SA refresh error")
                continue

            cot = build_pli(**data)
            ok = await fts.send(cot)
            if ok:
                logger.debug("Forwarded PLI for %s", data["callsign"])
            else:
                logger.error("Failed to forward PLI for %s", data["callsign"])
    finally:
        await fts.close()
        logger.info("Mesh relay stopped")


if __name__ == "__main__":
    asyncio.run(main())
