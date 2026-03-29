#!/usr/bin/env python3
"""Meshtastic -> FTS relay service.

Connects to a Meshtastic node (T-Beam bridge) via TCP or USB serial,
subscribes to position packets from the mesh network, converts them to
CoT (Cursor on Target) XML, and injects into FreeTAKServer over TCP.

Mesh nodes appear as friendly PLI markers on the TAK map.
Detection sensor alerts (GPIO triggers) are forwarded as CoT alarm
markers if the node has a known position, or logged only if not.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import signal
import socket
import threading
import time
import xml.etree.ElementTree as ET
import yaml
from datetime import datetime, timedelta, timezone

from meshtastic import mesh_pb2, portnums_pb2
from pubsub import pub

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("mesh-relay")

# --- Configuration ---

MESH_HOST = os.environ.get("MESH_HOST", "")
MESH_SERIAL = os.environ.get("MESH_SERIAL", "")
MQTT_ENABLED = os.environ.get("MQTT_ENABLED", "false").lower() == "true"
MQTT_HOST = os.environ.get("MQTT_HOST", "mosquitto")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
MQTT_USERNAME = os.environ.get("MQTT_USERNAME", "")
MQTT_PASSWORD = os.environ.get("MQTT_PASSWORD", "")
MQTT_TOPIC = os.environ.get("MQTT_TOPIC", "msh/US/2/2/json/LongFast/#")
MQTT_TOPICS = os.environ.get("MQTT_TOPICS", "")
FTS_HOST = os.environ.get("FTS_HOST", "freetakserver")
FTS_PORT = int(os.environ.get("FTS_PORT", "8087"))
CLIENT_UID = "CrypTAK-MeshRelay"
STALE_MINUTES = int(os.environ.get("STALE_MINUTES", "30"))
DETECTION_STALE_MINUTES = int(os.environ.get("DETECTION_STALE_MINUTES", "5"))
SA_REFRESH_MINUTES = 4
RECONNECT_DELAY = 10
MESH_HEARTBEAT_SECS = 30  # keep T-Beam TCP alive (firmware app-level idle ~130s)
POSITION_POLL_SECS = int(os.environ.get("POSITION_POLL_SECS", "120"))
# Nodes not heard within this window are skipped during nodedb seed.
# Initial seed on connect uses no age filter (to show all known nodes
# immediately), but periodic re-seeds apply this cutoff so markers for
# offline nodes expire naturally via the CoT stale timeout.
NODEDB_SEED_MAX_AGE_SECS = int(os.environ.get("NODEDB_SEED_MAX_AGE_SECS", "7200"))  # 2h

# Load node registry from nodes.yaml (single source of truth).
# Falls back to env vars for backward compatibility.
_NODES_YAML = os.environ.get("NODES_YAML", "/app/firmware/nodes.yaml")

def _load_node_registry(path: str) -> tuple[set[str], set[str], dict[str, str]]:
    """Parse nodes.yaml and return (friendly_ids, tracker_ids, cot_types).
    
    cot_types maps node_id (without !) to the cot_type string from yaml,
    e.g. {'55c6ddbc': 'a-f-G-E-X', '9aa4baf0': 'a-f-G-E-V'}.
    """
    friendly: set[str] = set()
    trackers: set[str] = set()
    cot_types: dict[str, str] = {}
    try:
        with open(path) as f:
            data = yaml.safe_load(f)
        nodes = (data or {}).get("nodes", {})
        for name, cfg in nodes.items():
            node_id = (cfg.get("id") or "").lstrip("!")
            if not node_id:
                continue
            friendly.add(node_id)  # all owned nodes are friendly
            if cfg.get("tracker"):
                trackers.add(node_id)
            if cfg.get("cot_type"):
                cot_types[node_id] = cfg["cot_type"]
        if friendly:
            logger.info("Loaded %d nodes from %s (%d trackers, %d cot_types)", 
                       len(friendly), path, len(trackers), len(cot_types))
    except FileNotFoundError:
        logger.warning("nodes.yaml not found at %s — falling back to env vars", path)
    except Exception as exc:
        logger.warning("Failed to parse %s: %s — falling back to env vars", path, exc)
    return friendly, trackers, cot_types

_yaml_friendly, _yaml_trackers, _yaml_cot_types = _load_node_registry(_NODES_YAML)

# Env var fallback (backward compat — used if nodes.yaml not found/empty)
_friendly_raw = os.environ.get("FRIENDLY_NODES", "")
_env_friendly: set[str] = {
    n.strip().lstrip("!") for n in _friendly_raw.split(",") if n.strip()
}
_tracker_raw = os.environ.get("TRACKER_NODES", "")
_env_trackers: set[str] = {
    n.strip().lstrip("!") for n in _tracker_raw.split(",") if n.strip()
}

FRIENDLY_NODES: set[str] = _yaml_friendly if _yaml_friendly else _env_friendly
TRACKER_NODES: set[str] = _yaml_trackers if _yaml_trackers else _env_trackers
COT_TYPES: dict[str, str] = _yaml_cot_types  # node_id → cot_type from nodes.yaml

# Telemetry cache — updated by telemetry handler, read during nodedb seed.
_telemetry_cache: dict[str, dict] = {}
_uptime_cache: dict[str, int] = {}
# Tracks when we last received a real GPS position per node (epoch seconds).
# Updated only by on_position callback — NOT by nodedb cache reads.
_last_position_ts: dict[str, float] = {}
# Tracks the last emitted CoT per node: (lat, lon, epoch_seconds).
# Used by nodedb seed to avoid refreshing stale markers when position hasn't
# changed.  Live on_position packets always emit (the node actively reported).
_last_emitted: dict[str, tuple[float, float, float]] = {}

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
    tracker: bool = False,
    bridge: bool = False,
    voltage: float = 0.0,
    channel_util: float = 0.0,
    air_util_tx: float = 0.0,
    uptime: int = 0,
    snr: float | None = None,
    hops_away: int | None = None,
) -> str:
    """Build a PLI CoT event for a mesh node position.

    Tracker nodes get type a-s-G-I-i-d (suspect/car icon) with a
    <__tracker/> detail tag so the WebMap can apply affiliation overrides
    and trail rendering.

    Known nodes (in FRIENDLY_NODES) get type a-f-G-E-S (friendly/blue).
    Unknown nodes get type a-n-G-E-S (neutral/green).
    G-E-S = Ground Equipment Sensor (MIL-STD-2525).
    """
    if tracker:
        aff = "s"  # suspect by default; WebMap can override
        type_suffix = "G-O-E"  # crosshairs icon
        group_name = "Yellow"
    else:
        friendly = node_id in FRIENDLY_NODES
        aff = "f" if friendly else "n"
        # Use cot_type from nodes.yaml if available, otherwise default sensor
        custom_cot = COT_TYPES.get(node_id, "")
        if custom_cot:
            # Parse cot_type like "a-f-G-E-V" → affiliation from char 2, suffix from chars 4+
            cot_parts = custom_cot.split("-")
            if len(cot_parts) >= 3:
                aff = cot_parts[1]  # use yaml-specified affiliation
                type_suffix = "-".join(cot_parts[2:])
            else:
                type_suffix = "G-E-S"
        else:
            type_suffix = "G-E-S"
        group_name = {"f": "Cyan", "n": "Green", "h": "Red", "s": "Yellow", "u": "Yellow"}.get(aff, "Green")

    now = datetime.now(timezone.utc)
    stale = now + timedelta(minutes=STALE_MINUTES)
    event = ET.Element("event", {
        "version": "2.0",
        "uid": f"tracker-{node_id}" if tracker else f"mesh-{node_id}",
        "type": f"a-{aff}-{type_suffix}",
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
    if tracker:
        ET.SubElement(detail, "__tracker")
    if bridge:
        ET.SubElement(detail, "__meshBridge")
    telem_attrs = {}
    if voltage > 0:
        telem_attrs["voltage"] = f"{voltage:.2f}"
    if channel_util > 0:
        telem_attrs["channelUtil"] = f"{channel_util:.1f}"
    if air_util_tx > 0:
        telem_attrs["airUtilTx"] = f"{air_util_tx:.1f}"
    if uptime > 0:
        telem_attrs["uptime"] = str(uptime)
    if snr is not None:
        telem_attrs["snr"] = f"{snr:.1f}"
    if hops_away is not None:
        telem_attrs["hopsAway"] = str(hops_away)
    if telem_attrs:
        ET.SubElement(detail, "__meshTelemetry", telem_attrs)
    ET.SubElement(detail, "__meshtastic")
    return ET.tostring(event, encoding="unicode")


def build_detection_alert(
    node_id: str,
    callsign: str,
    lat: float,
    lon: float,
    alert_text: str,
    alt: float = 0.0,
) -> str:
    """Build a CoT alarm marker for a DETECTION_SENSOR_APP event.

    Appears on the TAK map as a red unknown-ground marker at the sensor
    node's last known position, stale after DETECTION_STALE_MINUTES.
    """
    now = datetime.now(timezone.utc)
    stale = now + timedelta(minutes=DETECTION_STALE_MINUTES)
    uid = f"detection-{node_id}-{int(now.timestamp())}"
    event = ET.Element("event", {
        "version": "2.0",
        "uid": uid,
        "type": "a-u-G",          # unknown ground contact
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
    ET.SubElement(detail, "contact", {"callsign": callsign})
    ET.SubElement(detail, "uid", {"Droid": callsign})
    # Red marker so it stands out from PLI nodes
    ET.SubElement(detail, "color", {"argb": "-65536"})
    remarks = ET.SubElement(detail, "remarks")
    remarks.text = f"[SENSOR] {callsign}: {alert_text}"
    ET.SubElement(detail, "__meshDetection", {
        "nodeId": node_id,
        "alertText": alert_text,
    })
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
        if self._writer is None:
            # refresh_sa() discovered a broken connection and closed it.
            # Reconnect before attempting the write.
            await self.connect()
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

def _enqueue(queue, loop, data):
    """Thread-safe enqueue from the Meshtastic callback thread into asyncio.

    Uses call_soon_threadsafe to schedule the put on the event loop.
    If the queue is full (maxsize=100), drops the oldest item to prevent
    the Meshtastic thread from blocking on a full queue.
    """
    def _put():
        try:
            queue.put_nowait(data)
        except asyncio.QueueFull:
            logger.warning("Queue full, dropping oldest position")
            try:
                queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
            queue.put_nowait(data)
    loop.call_soon_threadsafe(_put)


def _seed_from_nodedb(iface, queue, loop, max_age_secs: int = 0):
    """Push cached positions from the T-Beam's nodedb into the queue.

    The Meshtastic firmware doesn't forward received position packets
    to the serial/TCP API if the position data hasn't changed (it
    updates lastHeard internally but suppresses duplicate content).
    This means fixed-position nodes never generate pubsub events after
    the initial boot broadcast.

    Compensates by periodically re-reading the nodedb and pushing
    PLI for nodes that are still alive. The lastHeard timestamp tells
    us when the T-Beam's radio last received ANY packet from a node.

    Args:
        iface: Active Meshtastic interface (serial or TCP).
        queue: asyncio.Queue for position data dicts.
        loop: The asyncio event loop (for thread-safe enqueue).
        max_age_secs: If > 0, skip nodes not heard within this window.
            Nodes that go offline have their markers expire naturally
            via the CoT stale timeout instead of being continuously
            refreshed as ghost markers.
    """
    my_node_num = getattr(
        getattr(iface, "myInfo", None), "my_node_num", None,
    )
    now_epoch = int(time.time())
    seeded = 0
    for nid, node in list(iface.nodes.items()):
        is_bridge = node.get("num") == my_node_num

        # Skip nodes not heard recently (they're offline).
        # Bridge node is always included (it's always "online").
        if max_age_secs > 0 and not is_bridge:
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

        # Skip nodes whose last real GPS position is older than max_age.
        # The nodedb caches positions indefinitely, but we only want to
        # re-emit CoT for nodes with recent GPS fixes. Without this,
        # a node that lost GPS lock keeps its marker fresh forever.
        node_id_raw = nid.replace("!", "")
        if max_age_secs > 0 and not is_bridge:
            last_pos = _last_position_ts.get(node_id_raw, 0)
            if last_pos and (now_epoch - last_pos) > max_age_secs:
                continue

        user = node.get("user", {})
        callsign = user.get("longName") or user.get("shortName") or nid
        node_id = nid.replace("!", "")
        battery = 0
        voltage = 0.0
        channel_util = 0.0
        air_util_tx = 0.0
        uptime_s = 0
        dm = node.get("deviceMetrics", {})
        if dm:
            battery = int(dm.get("batteryLevel", 0) or 0)
            voltage = float(dm.get("voltage", 0) or 0)
            channel_util = float(dm.get("channelUtilization", 0) or 0)
            air_util_tx = float(dm.get("airUtilTx", 0) or 0)
            uptime_s = int(dm.get("uptimeSeconds", 0) or 0)
        # Telemetry cache may have fresher values than nodedb snapshot
        telem = _telemetry_cache.get(node_id, {})
        if telem.get("battery"):
            battery = telem["battery"]
        if telem.get("voltage"):
            voltage = telem["voltage"]
        if telem.get("channelUtil"):
            channel_util = telem["channelUtil"]
        if telem.get("airUtilTx"):
            air_util_tx = telem["airUtilTx"]
        if telem.get("uptime"):
            uptime_s = telem["uptime"]
        snr_val = node.get("snr")
        hops_val = node.get("hopsAway")
        # Skip re-emission if we recently emitted the same position.
        # Only refresh when the previous CoT is about to go stale
        # (80% of STALE_MINUTES) or the position actually changed.
        prev = _last_emitted.get(node_id)
        if prev:
            prev_lat, prev_lon, prev_ts = prev
            age_secs = now_epoch - prev_ts
            refresh_threshold = STALE_MINUTES * 60 * 0.8
            pos_changed = abs(lat - prev_lat) > 1e-7 or abs(lon - prev_lon) > 1e-7
            if not pos_changed and age_secs < refresh_threshold:
                continue  # CoT still fresh in TAK, no need to re-emit

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
            "tracker": node_id in TRACKER_NODES and not is_bridge,
            "bridge": is_bridge,
            "voltage": voltage,
            "channel_util": channel_util,
            "air_util_tx": air_util_tx,
            "uptime": uptime_s,
            "snr": snr_val if snr_val is not None else None,
            "hops_away": hops_val if hops_val is not None else None,
        }
        _last_emitted[node_id] = (lat, lon, float(now_epoch))
        _enqueue(queue, loop, data)
        seeded += 1
        snr_str = " snr=%.1fdB" % snr_val if snr_val is not None else ""
        hops_str = " hops=%d" % hops_val if hops_val is not None else ""
        logger.debug(
            "Seeded from nodedb: %s at %.6f, %.6f bat=%d%%%s%s",
            callsign, lat, lon, battery, snr_str, hops_str,
        )
    if seeded:
        logger.info("Refreshed %d positions from nodedb", seeded)


def _mqtt_thread(queue: asyncio.Queue, loop: asyncio.AbstractEventLoop):
    """Background thread: subscribe to Meshtastic MQTT broker with auto-reconnect.

    Parses JSON position and telemetry messages published by the WisMesh
    Gateway and enqueues position data in the same format as _mesh_thread.
    Logs with [MQTT] prefix throughout.
    """
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        logger.error("[MQTT] paho-mqtt not installed — cannot start MQTT thread. "
                     "Add paho-mqtt>=2.0.0 to requirements.txt")
        return

    # Determine topic list: MQTT_TOPICS (comma-sep) takes precedence over MQTT_TOPIC.
    if MQTT_TOPICS:
        topics = [t.strip() for t in MQTT_TOPICS.split(",") if t.strip()]
    elif MQTT_TOPIC:
        topics = [MQTT_TOPIC]
    else:
        topics = ["msh/US/2/2/json/LongFast/#"]

    logger.info("[MQTT] Topics: %s", topics)

    def _resolve_callsign(sender: str) -> str:
        """Resolve callsign from sender hex ID via nodes.yaml, fallback to hex ID."""
        node_id = sender.lstrip("!")
        # Walk the raw nodes.yaml data to look up by id → get longName/shortName
        try:
            with open(_NODES_YAML) as f:
                data = yaml.safe_load(f)
            nodes = (data or {}).get("nodes", {})
            for name, cfg in nodes.items():
                cfg_id = (cfg.get("id") or "").lstrip("!")
                if cfg_id == node_id:
                    return cfg.get("longName") or cfg.get("shortName") or name or sender
        except Exception:
            pass
        return sender  # fallback: use hex ID as-is

    def on_connect(client, userdata, flags, reason_code, properties):
        if reason_code == 0:
            logger.info("[MQTT] Connected to %s:%d", MQTT_HOST, MQTT_PORT)
            for topic in topics:
                client.subscribe(topic)
                logger.info("[MQTT] Subscribed to %s", topic)
        else:
            logger.warning("[MQTT] Connect failed, reason_code=%s", reason_code)

    def on_disconnect(client, userdata, disconnect_flags, reason_code, properties):
        logger.warning("[MQTT] Disconnected (reason_code=%s), will auto-reconnect", reason_code)

    def on_message(client, userdata, msg):
        try:
            payload_str = msg.payload.decode("utf-8", errors="replace")
            data = json.loads(payload_str)
        except Exception as exc:
            logger.debug("[MQTT] Could not decode message on %s: %s", msg.topic, exc)
            return

        msg_type = data.get("type", "")

        # --- Telemetry ---
        if msg_type == "telemetry":
            try:
                sender = data.get("sender", "")
                node_id = sender.lstrip("!")
                payload = data.get("payload", {})
                battery_level = int(payload.get("battery_level", 0) or 0)
                voltage = float(payload.get("voltage", 0) or 0)
                channel_util = float(payload.get("channel_utilization", 0) or 0)
                air_util_tx = float(payload.get("air_util_tx", 0) or 0)
                uptime_s = int(payload.get("uptime_seconds", 0) or 0)
                if uptime_s > 0:
                    prev = _uptime_cache.get(node_id, 0)
                    if prev > 0 and uptime_s < prev:
                        logger.warning(
                            "[MQTT] Node %s rebooted (was up %ds, now %ds)",
                            node_id, prev, uptime_s,
                        )
                    _uptime_cache[node_id] = uptime_s
                _telemetry_cache[node_id] = {
                    "voltage": voltage,
                    "battery": battery_level,
                    "channelUtil": channel_util,
                    "airUtilTx": air_util_tx,
                    "uptime": uptime_s,
                }
                logger.debug(
                    "[MQTT] Telemetry from %s: %.2fV bat=%d%% ch=%.1f%% tx=%.1f%% up=%ds",
                    node_id, voltage, battery_level, channel_util, air_util_tx, uptime_s,
                )
            except Exception:
                logger.exception("[MQTT] Error processing telemetry message")
            return

        # --- Position ---
        if msg_type == "position":
            try:
                sender = data.get("sender", "")
                node_id = sender.lstrip("!")
                payload = data.get("payload", {})

                lat_i = payload.get("latitude_i", 0) or 0
                lon_i = payload.get("longitude_i", 0) or 0
                if lat_i == 0 and lon_i == 0:
                    logger.debug("[MQTT] Position from %s has no GPS fix, skipping", sender)
                    return
                lat = lat_i / 1e7
                lon = lon_i / 1e7

                alt = float(payload.get("altitude", 0) or 0)
                speed = float(payload.get("ground_speed", 0) or 0)
                course = float(payload.get("ground_track", 0) or 0)

                callsign = _resolve_callsign(sender)

                # Pull latest telemetry for this node
                telem = _telemetry_cache.get(node_id, {})
                battery = telem.get("battery", 0)
                voltage = telem.get("voltage", 0.0)
                channel_util = telem.get("channelUtil", 0.0)
                air_util_tx = telem.get("airUtilTx", 0.0)
                uptime_s = telem.get("uptime", 0)

                position_data = {
                    "node_id": node_id,
                    "callsign": callsign,
                    "lat": lat,
                    "lon": lon,
                    "alt": alt,
                    "speed": speed,
                    "course": course,
                    "battery": battery,
                    "hw_model": "",
                    "tracker": node_id in TRACKER_NODES,
                    "bridge": False,
                    "voltage": voltage,
                    "channel_util": channel_util,
                    "air_util_tx": air_util_tx,
                    "uptime": uptime_s,
                    "snr": None,
                    "hops_away": None,
                }
                _last_position_ts[node_id] = time.time()
                _last_emitted[node_id] = (lat, lon, time.time())
                _enqueue(queue, loop, position_data)
                bat_str = " bat=%d%%" % battery if battery else ""
                logger.info(
                    "[MQTT] Position from %s (%s): %.6f, %.6f alt=%dm%s",
                    sender, callsign, lat, lon, int(alt), bat_str,
                )
            except Exception:
                logger.exception("[MQTT] Error processing position message")
            return

        # Ignore all other message types silently
        logger.debug("[MQTT] Ignored message type=%r on %s", msg_type, msg.topic)

    while True:
        try:
            client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
            client.on_connect = on_connect
            client.on_disconnect = on_disconnect
            client.on_message = on_message

            if MQTT_USERNAME:
                client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)

            client.reconnect_delay_set(min_delay=1, max_delay=RECONNECT_DELAY)

            logger.info("[MQTT] Connecting to %s:%d", MQTT_HOST, MQTT_PORT)
            client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
            client.loop_forever()  # blocks; handles reconnect automatically
        except Exception as exc:
            logger.warning("[MQTT] Connection error: %s — retrying in %ds", exc, RECONNECT_DELAY)
            time.sleep(RECONNECT_DELAY)


def _mesh_thread(queue: asyncio.Queue, loop: asyncio.AbstractEventLoop):
    """Background thread: connect to mesh node with auto-reconnect.

    Subscribes to position packets and pushes them onto the asyncio
    queue. Automatically reconnects when the mesh node reboots or
    the TCP/serial connection drops.

    If MQTT_ENABLED is true and no explicit serial/TCP target is set,
    the thread exits immediately — MQTT is the primary data source.
    """
    if MQTT_ENABLED and not MESH_SERIAL and not MESH_HOST:
        logger.info(
            "Serial/TCP mesh thread skipped (MQTT_ENABLED=true with no MESH_HOST/MESH_SERIAL)"
        )
        return

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

            from_id = packet.get("fromId") or str(packet.get("from", ""))
            if not from_id:
                logger.warning("Position packet with no fromId — skipping")
                return
            node_info = interface.nodes.get(from_id, {})
            user = node_info.get("user", {})
            callsign = (
                user.get("longName")
                or user.get("shortName")
                or from_id
            )

            battery = 0
            voltage = 0.0
            channel_util = 0.0
            air_util_tx = 0.0
            uptime_s = 0
            device_metrics = node_info.get("deviceMetrics", {})
            if device_metrics:
                battery = int(device_metrics.get("batteryLevel", 0) or 0)
                voltage = float(device_metrics.get("voltage", 0) or 0)
                channel_util = float(device_metrics.get("channelUtilization", 0) or 0)
                air_util_tx = float(device_metrics.get("airUtilTx", 0) or 0)
                uptime_s = int(device_metrics.get("uptimeSeconds", 0) or 0)

            node_id = from_id.replace("!", "")
            hw_model = user.get("hwModel", "")

            telem = _telemetry_cache.get(node_id, {})
            if telem.get("battery"):
                battery = telem["battery"]
            if telem.get("voltage"):
                voltage = telem["voltage"]
            if telem.get("channelUtil"):
                channel_util = telem["channelUtil"]
            if telem.get("airUtilTx"):
                air_util_tx = telem["airUtilTx"]
            if telem.get("uptime"):
                uptime_s = telem["uptime"]
            snr_val = node_info.get("snr")
            hops_val = node_info.get("hopsAway")

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
                "tracker": node_id in TRACKER_NODES,
                "voltage": voltage,
                "channel_util": channel_util,
                "air_util_tx": air_util_tx,
                "uptime": uptime_s,
                "snr": snr_val if snr_val is not None else None,
                "hops_away": hops_val if hops_val is not None else None,
            }
            _last_position_ts[node_id] = time.time()
            _last_emitted[node_id] = (lat, lon, time.time())
            _enqueue(queue, loop, data)
            bat_str = " bat=%d%%" % battery if battery else ""
            snr_str = " snr=%.1fdB" % snr_val if snr_val is not None else ""
            hops_str = " hops=%d" % hops_val if hops_val is not None else ""
            logger.info(
                "Position from %s: %.6f, %.6f alt=%dm%s%s%s",
                callsign, lat, lon, int(data["alt"]), bat_str,
                snr_str, hops_str,
            )
        except Exception:
            logger.exception("Error processing position packet")

    # Global subscription — survives interface reconnects because
    # PyPubSub topics are not bound to a specific interface instance.
    def on_telemetry(packet, interface):
        try:
            decoded = packet.get("decoded", {})
            telemetry = decoded.get("telemetry", {})
            dm = telemetry.get("deviceMetrics", {})
            if not dm:
                return
            from_id = packet.get("fromId") or str(packet.get("from") or "unknown")
            node_id = from_id.replace("!", "")
            voltage = float(dm.get("voltage", 0) or 0)
            channel_util = float(dm.get("channelUtilization", 0) or 0)
            air_util_tx = float(dm.get("airUtilTx", 0) or 0)
            uptime_s = int(dm.get("uptimeSeconds", 0) or 0)
            # Reboot detection: uptime decreased → log only (no map marker).
            # The node's normal PLI will update its existing marker on the
            # next position broadcast.  Creating a separate CoT event caused
            # duplicate clients and noise on the TAK map.
            if uptime_s > 0:
                prev = _uptime_cache.get(node_id, 0)
                if prev > 0 and uptime_s < prev:
                    logger.warning(
                        "Node %s rebooted (was up %ds, now %ds)",
                        node_id, prev, uptime_s,
                    )
                _uptime_cache[node_id] = uptime_s
            battery_level = int(dm.get("batteryLevel", 0) or 0)
            _telemetry_cache[node_id] = {
                "voltage": voltage,
                "battery": battery_level,
                "channelUtil": channel_util,
                "airUtilTx": air_util_tx,
                "uptime": uptime_s,
            }
            logger.debug(
                "Telemetry from %s: %.2fV ch=%.1f%% tx=%.1f%% up=%ds",
                node_id, voltage, channel_util, air_util_tx, uptime_s,
            )
        except Exception:
            logger.exception("Error processing telemetry packet")

    def on_detection(packet, interface):
        try:
            from_id = packet.get("fromId", str(packet.get("from", "unknown")))
            decoded = packet.get("decoded", {})
            alert_text = decoded.get("text", "Detection triggered")

            node_info = interface.nodes.get(from_id, {})
            user = node_info.get("user", {})
            callsign = (
                user.get("longName")
                or user.get("shortName")
                or from_id
            )
            node_id = from_id.replace("!", "")

            pos = node_info.get("position", {})
            lat = pos.get("latitude", 0.0) or 0.0
            lon = pos.get("longitude", 0.0) or 0.0
            if lat == 0.0 and lon == 0.0:
                lat_i = pos.get("latitudeI", 0) or 0
                lon_i = pos.get("longitudeI", 0) or 0
                if lat_i and lon_i:
                    lat = lat_i / 1e7
                    lon = lon_i / 1e7

            if lat == 0.0 and lon == 0.0:
                logger.warning(
                    "DETECTION from %s (%s): %s — no position, cannot place CoT marker",
                    node_id, callsign, alert_text,
                )
                return

            alt = float(pos.get("altitude", 0) or 0)
            logger.info(
                "DETECTION from %s (%s) at %.6f, %.6f: %s",
                node_id, callsign, lat, lon, alert_text,
            )
            data = {
                "node_id": node_id,
                "callsign": callsign,
                "lat": lat,
                "lon": lon,
                "alt": alt,
                "alert_text": alert_text,
                "_type": "detection",
            }
            _enqueue(queue, loop, data)
        except Exception:
            logger.exception("Error processing detection packet")

    pub.subscribe(on_position, "meshtastic.receive.position")
    pub.subscribe(on_telemetry, "meshtastic.receive.telemetry")
    pub.subscribe(on_detection, "meshtastic.receive.detection")
    logger.info("Subscribed to mesh position + telemetry + detection packets")

    while True:
        disconnected = threading.Event()

        def on_disconnect(interface=None):
            disconnected.set()

        iface = None
        try:
            # Try serial first (more reliable — no WiFi dependency, no
            # firmware idle timeout). Fall back to TCP if serial device
            # is unavailable (e.g. USB path changed on re-enumeration).
            if MESH_SERIAL:
                try:
                    from meshtastic.serial_interface import SerialInterface
                    logger.info("Connecting to Meshtastic via serial: %s", MESH_SERIAL)
                    iface = SerialInterface(MESH_SERIAL)
                except Exception as serial_exc:
                    logger.warning(
                        "Serial connect failed (%s), falling back to TCP: %s",
                        serial_exc, MESH_HOST,
                    )
            if iface is None:
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

            # Seed initial PLI from T-Beam's cached nodedb.
            # Age filter applied from the start — no point showing positions
            # for nodes that haven't been heard recently. Stale nodes appear
            # when they come back online and start broadcasting again.
            _seed_from_nodedb(iface, queue, loop, max_age_secs=NODEDB_SEED_MAX_AGE_SECS)

            pub.subscribe(on_disconnect, "meshtastic.connection.lost")
            try:
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
                    # Age filter applied here: nodes not heard within
                    # NODEDB_SEED_MAX_AGE_SECS are skipped so their CoT
                    # markers expire naturally via the stale timeout instead
                    # of being continuously refreshed as ghost markers.
                    # The initial seed (above) has no age filter so all
                    # known positions appear immediately on connect.
                    now = time.monotonic()
                    if now - last_poll >= POSITION_POLL_SECS:
                        last_poll = now
                        _seed_from_nodedb(iface, queue, loop, max_age_secs=NODEDB_SEED_MAX_AGE_SECS)
                        for nid in list(iface.nodes):
                            node = iface.nodes.get(nid)
                            if node is None:
                                continue
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
                            try:
                                iface.sendTelemetry(
                                    destinationId=nid,
                                    wantResponse=True,
                                )
                            except Exception as exc:
                                logger.debug("Telemetry poll to %s failed: %s", nid, exc)
            finally:
                try:
                    pub.unsubscribe(on_disconnect, "meshtastic.connection.lost")
                except Exception:
                    pass
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
    """Entry point: connect to FTS, start mesh listener, relay positions.

    Runs the main event loop that consumes position/detection data from
    the Meshtastic thread's queue and forwards it to FTS as CoT XML.
    Handles graceful shutdown on SIGINT/SIGTERM.
    """
    fts = FtsClient(FTS_HOST, FTS_PORT)
    await fts.connect()

    queue: asyncio.Queue = asyncio.Queue(maxsize=100)
    loop = asyncio.get_running_loop()

    if MQTT_ENABLED:
        if MESH_SERIAL or MESH_HOST:
            logger.info(
                "MQTT_ENABLED=true: serial/TCP target also set (%s%s) — "
                "MQTT is primary, serial/TCP thread will be skipped",
                MESH_SERIAL or "", MESH_HOST or "",
            )
        logger.info("Starting MQTT listener thread")
        mesh = threading.Thread(
            target=_mqtt_thread, args=(queue, loop), daemon=True,
        )
    else:
        logger.info("Starting serial/TCP mesh listener thread")
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

            event_type = data.pop("_type", "pli")
            if event_type == "detection":
                cot = build_detection_alert(**data)
                label = f"detection from {data['callsign']}"
            else:
                cot = build_pli(**data)
                label = f"PLI for {data['callsign']}"
            ok = await fts.send(cot)
            if ok:
                logger.debug("Forwarded %s", label)
            else:
                logger.error("Failed to forward %s", label)
    finally:
        await fts.close()
        logger.info("Mesh relay stopped")


if __name__ == "__main__":
    asyncio.run(main())
