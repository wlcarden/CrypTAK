"""Meshtastic radio connection manager with auto-detection and hot-plug."""

from __future__ import annotations

import base64
import glob
import hashlib
import json
import logging
import threading
import time
from typing import Callable, Optional

from meshtastic.serial_interface import SerialInterface
from meshtastic.protobuf.channel_pb2 import Channel as MeshChannel

from server.models import (
    ChannelInfo,
    DeviceMetrics,
    GpsInfo,
    NeighborInfo,
    NodeInfo,
    PowerInfo,
    RadioState,
    SecurityInfo,
)

logger = logging.getLogger(__name__)

SERIAL_PATTERNS = ["/dev/ttyACM*", "/dev/ttyUSB*"]
POLL_INTERVAL = 2.0

# Ports to skip — known non-Meshtastic devices (e.g. cellular modems)
# Detected by checking if a port belongs to a known USB VID:PID
SKIP_VID_PIDS = {
    "2c7c",  # Quectel (EC25, EG25, etc.)
    "12d1",  # Huawei modems
    "1199",  # Sierra Wireless
}

def _is_modem_port(port: str) -> bool:
    """Check if a serial port belongs to a known cellular modem."""
    import subprocess
    try:
        out = subprocess.check_output(
            ["udevadm", "info", "--query=property", port],
            timeout=3, stderr=subprocess.DEVNULL, text=True,
        )
        for line in out.splitlines():
            if line.startswith("ID_VENDOR_ID="):
                vid = line.split("=", 1)[1].strip()
                if vid in SKIP_VID_PIDS:
                    return True
    except Exception:
        pass
    return False


def _classify_psk(psk_bytes: bytes) -> tuple[str, str]:
    """Classify a PSK and return (type, hash_prefix)."""
    if not psk_bytes or psk_bytes == b"\x00":
        return "none", ""
    if psk_bytes == b"\x01" or base64.b64encode(psk_bytes) == b"AQ==":
        return "default", ""
    if len(psk_bytes) == 1:
        return "simple", ""
    h = hashlib.sha256(psk_bytes).hexdigest()[:8]
    return "custom", h


class RadioManager:
    """Manages connection to a single Meshtastic radio with auto-detection."""

    def __init__(self, on_state_change: Optional[Callable[[str, RadioState], None]] = None):
        self._interface: Optional[SerialInterface] = None
        self._lock = threading.Lock()
        self._current_port: Optional[str] = None
        self._state = RadioState()
        self._on_state_change = on_state_change
        self._polling = False
        self._poll_thread: Optional[threading.Thread] = None

    @property
    def state(self) -> RadioState:
        with self._lock:
            return self._state.model_copy()

    @property
    def connected(self) -> bool:
        with self._lock:
            return self._interface is not None

    def start_polling(self) -> None:
        """Start background thread that polls for serial devices."""
        if self._polling:
            return
        self._polling = True
        self._poll_thread = threading.Thread(target=self._poll_loop, daemon=True)
        self._poll_thread.start()
        logger.info("Radio auto-detection started")

    def stop_polling(self) -> None:
        """Stop background polling."""
        self._polling = False
        if self._poll_thread:
            self._poll_thread.join(timeout=5)
            self._poll_thread = None

    def _poll_loop(self) -> None:
        while self._polling:
            try:
                self._check_connection()
            except Exception:
                logger.exception("Error in radio poll loop")
            time.sleep(POLL_INTERVAL)

    def _find_serial_ports(self) -> list[str]:
        ports = []
        for pattern in SERIAL_PATTERNS:
            ports.extend(sorted(glob.glob(pattern)))
        # Filter out known non-Meshtastic ports (cellular modems, etc.)
        filtered = [p for p in ports if not _is_modem_port(p)]
        if len(filtered) < len(ports):
            skipped = set(ports) - set(filtered)
            logger.debug("Skipping non-Meshtastic ports: %s", skipped)
        return filtered

    def _check_connection(self) -> None:
        with self._lock:
            has_interface = self._interface is not None
            current_port = self._current_port

        # Check if current connection is still alive
        if has_interface and current_port:
            if current_port not in self._find_serial_ports():
                logger.info("Radio disconnected (port %s gone)", current_port)
                self._disconnect()
                return
            # Verify interface is still responsive
            try:
                with self._lock:
                    if self._interface and self._interface.myInfo:
                        pass  # Still alive
            except Exception:
                logger.warning("Radio connection lost")
                self._disconnect()
                return

        # Try to connect if not connected
        if not has_interface:
            for port in self._find_serial_ports():
                if self._try_connect(port):
                    break

    def _try_connect(self, port: str) -> bool:
        """Attempt to connect to a Meshtastic device on the given port."""
        try:
            logger.info("Probing %s for Meshtastic radio...", port)
            iface = SerialInterface(port, timeout=10)
            with self._lock:
                self._interface = iface
                self._current_port = port
            state = self._extract_state()
            with self._lock:
                self._state = state
            logger.info(
                "Connected to %s (%s) on %s",
                state.long_name,
                state.node_id,
                port,
            )
            if self._on_state_change:
                self._on_state_change("connected", state)
            return True
        except Exception:
            logger.debug("No Meshtastic device on %s", port)
            return False

    def _disconnect(self) -> None:
        """Clean up current connection."""
        with self._lock:
            iface = self._interface
            self._interface = None
            self._current_port = None
            self._state = RadioState()
        # Notify UI BEFORE attempting close (close can hang on dead ports)
        if self._on_state_change:
            self._on_state_change("disconnected", RadioState())
        if iface:
            # Close in a daemon thread with timeout to avoid blocking poll loop
            def _close():
                try:
                    iface.close()
                except Exception:
                    pass
            t = threading.Thread(target=_close, daemon=True)
            t.start()
            t.join(timeout=5)
            if t.is_alive():
                logger.warning("iface.close() timed out — abandoned")

    def connect(self, port: str) -> RadioState:
        """Manually connect to a specific port. Raises on failure."""
        self._disconnect()
        iface = SerialInterface(port, timeout=10)
        with self._lock:
            self._interface = iface
            self._current_port = port
        state = self._extract_state()
        with self._lock:
            self._state = state
        if self._on_state_change:
            self._on_state_change("connected", state)
        return state

    def disconnect(self) -> None:
        """Manually disconnect."""
        self._disconnect()

    def refresh_state(self) -> RadioState:
        """Re-read full state from connected radio."""
        with self._lock:
            if not self._interface:
                return RadioState()
        state = self._extract_state()
        with self._lock:
            self._state = state
        if self._on_state_change:
            self._on_state_change("status_update", state)
        return state

    def _extract_state(self) -> RadioState:
        """Extract full radio state from the meshtastic interface."""
        with self._lock:
            iface = self._interface
            port = self._current_port or ""
        if not iface:
            return RadioState()

        my_info = iface.myInfo
        node = iface.getMyNodeInfo() if hasattr(iface, "getMyNodeInfo") else {}
        my_node_num = my_info.my_node_num if my_info else 0
        node_id = f"!{my_node_num:08x}" if my_node_num else ""

        # Identity
        user = node.get("user", {})
        long_name = user.get("longName", "")
        short_name = user.get("shortName", "")
        hw_model = user.get("hwModel", "UNKNOWN")

        # Firmware
        metadata = {}
        try:
            metadata = iface.getMetadata()
            if metadata is None:
                metadata = {}
        except Exception:
            pass
        firmware = metadata.get("firmwareVersion", "")

        # Config
        lora_config = {}
        device_config = {}
        position_config = {}
        security_config = {}
        try:
            local_config = iface.localNode.localConfig
            if local_config:
                lora_config = {
                    "region": str(local_config.lora.region) if local_config.lora else "",
                    "modem_preset": str(local_config.lora.modem_preset) if local_config.lora else "",
                    "hop_limit": local_config.lora.hop_limit if local_config.lora else 3,
                }
                if local_config.device:
                    device_config = {
                        "role": str(local_config.device.role),
                        "serial_enabled": local_config.device.serial_enabled,
                    }
                if local_config.position:
                    position_config = {
                        "gps_mode": str(local_config.position.gps_mode),
                        "fixed_position": local_config.position.fixed_position,
                    }
                if local_config.security:
                    sec = local_config.security
                    security_config = {
                        "is_managed": sec.is_managed,
                        "admin_key": list(sec.admin_key) if sec.admin_key else [],
                        "serial_enabled": device_config.get("serial_enabled", True),
                        "admin_channel_enabled": sec.admin_channel_enabled if hasattr(sec, "admin_channel_enabled") else False,
                    }
        except Exception:
            logger.debug("Could not read localConfig", exc_info=True)

        # Channels
        channels: list[ChannelInfo] = []
        try:
            for ch in iface.localNode.channels:
                if ch and ch.role != MeshChannel.Role.DISABLED:
                    psk_bytes = ch.settings.psk if ch.settings else b""
                    psk_type, psk_hash = _classify_psk(bytes(psk_bytes))
                    module_settings = ch.settings.module_settings if ch.settings and hasattr(ch.settings, "module_settings") else None
                    pos_prec = module_settings.position_precision if module_settings and hasattr(module_settings, "position_precision") else 0
                    channels.append(ChannelInfo(
                        index=ch.index,
                        name=ch.settings.name if ch.settings else "",
                        psk_type=psk_type,
                        psk_hash=psk_hash,
                        role=str(ch.role),
                        position_precision=pos_prec,
                        uplink_enabled=ch.settings.uplink_enabled if ch.settings else False,
                        downlink_enabled=ch.settings.downlink_enabled if ch.settings else False,
                    ))
        except Exception:
            logger.debug("Could not read channels", exc_info=True)

        # GPS
        position = node.get("position", {})
        gps = GpsInfo(
            fix_type=str(position.get("fixType", "NO_FIX")),
            latitude=position.get("latitude"),
            longitude=position.get("longitude"),
            altitude=position.get("altitude"),
            satellites=position.get("satsInView", 0),
            last_fix_age_s=position.get("lastFixAge"),
        )

        # Power / device metrics
        dm = node.get("deviceMetrics", {})
        power = PowerInfo(
            battery_pct=dm.get("batteryLevel"),
            voltage=dm.get("voltage"),
            uptime_s=dm.get("uptimeSeconds", 0),
        )

        device_metrics = DeviceMetrics(
            channel_utilization=dm.get("channelUtilization", 0.0),
            air_util_tx=dm.get("airUtilTx", 0.0),
            voltage=dm.get("voltage"),
            battery_level=dm.get("batteryLevel"),
        )

        # Security
        security = SecurityInfo(
            is_managed=security_config.get("is_managed", False),
            serial_enabled=security_config.get("serial_enabled", True),
            admin_channel_enabled=security_config.get("admin_channel_enabled", False),
            admin_key_set=bool(security_config.get("admin_key")),
        )

        # Nodedb
        nodedb: list[NodeInfo] = []
        neighbors: list[NeighborInfo] = []
        try:
            for nid_str, n in (iface.nodes or {}).items():
                u = n.get("user", {})
                ni = NodeInfo(
                    node_id=nid_str,
                    long_name=u.get("longName", ""),
                    short_name=u.get("shortName", ""),
                    hw_model=u.get("hwModel", "UNKNOWN"),
                    snr=n.get("snr"),
                    last_heard=n.get("lastHeard"),
                )
                nodedb.append(ni)
                if n.get("snr") is not None and nid_str != node_id:
                    neighbors.append(NeighborInfo(
                        node_id=nid_str,
                        snr=n.get("snr", 0.0),
                        long_name=u.get("longName", ""),
                        short_name=u.get("shortName", ""),
                    ))
        except Exception:
            logger.debug("Could not read nodedb", exc_info=True)

        region = lora_config.get("region", "")
        # Strip enum prefixes like "RegionCode."
        if "." in region:
            region = region.split(".")[-1]
        modem_preset = lora_config.get("modem_preset", "")
        if "." in modem_preset:
            modem_preset = modem_preset.split(".")[-1]
        role = device_config.get("role", "")
        if "." in role:
            role = role.split(".")[-1]

        return RadioState(
            connected=True,
            port=port,
            node_id=node_id,
            long_name=long_name,
            short_name=short_name,
            hw_model=hw_model,
            firmware_version=firmware,
            region=region,
            modem_preset=modem_preset,
            hop_limit=lora_config.get("hop_limit", 3),
            role=role,
            channels=channels,
            gps=gps,
            power=power,
            security=security,
            device_metrics=device_metrics,
            nodedb=nodedb,
            neighbors=neighbors,
        )

    def export_config(self) -> dict:
        """Export full config as a JSON-serializable dict for backup."""
        with self._lock:
            iface = self._interface
        if not iface:
            raise RuntimeError("No radio connected")

        state = self._extract_state()
        config: dict = {"state": state.model_dump(mode="json")}

        # Export raw protobufs as dicts
        try:
            local_config = iface.localNode.localConfig
            if local_config:
                from google.protobuf.json_format import MessageToDict
                config["localConfig"] = MessageToDict(local_config)
        except Exception:
            pass
        try:
            module_config = iface.localNode.moduleConfig
            if module_config:
                from google.protobuf.json_format import MessageToDict
                config["moduleConfig"] = MessageToDict(module_config)
        except Exception:
            pass

        return config

    def apply_setting(self, field_path: str, value) -> None:
        """Apply a single setting change via the meshtastic library.

        field_path is dot-separated, e.g. "lora.region" or "device.role".
        """
        with self._lock:
            iface = self._interface
        if not iface:
            raise RuntimeError("No radio connected")

        # Use setPref for simple config changes
        iface.localNode.setConfig(field_path, value)
        iface.localNode.writeConfig(field_path.split(".")[0])

    def set_channel_setting(self, ch_index: int, field: str, value) -> None:
        """Modify a channel setting."""
        with self._lock:
            iface = self._interface
        if not iface:
            raise RuntimeError("No radio connected")

        ch = iface.localNode.channels[ch_index]
        if field == "psk":
            ch.settings.psk = base64.b64decode(value) if isinstance(value, str) else value
        elif field == "name":
            ch.settings.name = value
        elif field == "position_precision":
            ch.settings.module_settings.position_precision = int(value)
        else:
            setattr(ch.settings, field, value)
        iface.localNode.writeChannel(ch_index)

    def factory_reset(self) -> None:
        """Send factory reset command."""
        with self._lock:
            iface = self._interface
        if not iface:
            raise RuntimeError("No radio connected")
        iface.localNode.factoryReset()

    def reboot(self) -> None:
        """Send reboot command."""
        with self._lock:
            iface = self._interface
        if not iface:
            raise RuntimeError("No radio connected")
        iface.localNode.reboot()

    def set_owner(self, long_name: str, short_name: str) -> None:
        """Set the node owner name."""
        with self._lock:
            iface = self._interface
        if not iface:
            raise RuntimeError("No radio connected")
        iface.localNode.setOwner(long_name=long_name, short_name=short_name)

    def get_all_settings(self) -> dict:
        """Get all config settings as a flat dict for the settings browser."""
        with self._lock:
            iface = self._interface
        if not iface:
            return {}
        result = {}
        try:
            local_config = iface.localNode.localConfig
            if local_config:
                from google.protobuf.json_format import MessageToDict
                cfg = MessageToDict(local_config, preserving_proto_field_name=True)
                for section, values in cfg.items():
                    if isinstance(values, dict):
                        for k, v in values.items():
                            result[f"{section}.{k}"] = v
                    else:
                        result[section] = values
        except Exception:
            pass
        try:
            module_config = iface.localNode.moduleConfig
            if module_config:
                from google.protobuf.json_format import MessageToDict
                cfg = MessageToDict(module_config, preserving_proto_field_name=True)
                for section, values in cfg.items():
                    if isinstance(values, dict):
                        for k, v in values.items():
                            result[f"{section}.{k}"] = v
                    else:
                        result[section] = values
        except Exception:
            pass
        return result

    def import_config(self, config: dict) -> None:
        """Restore config from a backup dict. Only restores localConfig and moduleConfig."""
        with self._lock:
            iface = self._interface
        if not iface:
            raise RuntimeError("No radio connected")

        from google.protobuf.json_format import ParseDict

        if "localConfig" in config:
            local_config = iface.localNode.localConfig
            ParseDict(config["localConfig"], local_config)
            for section in ["device", "position", "power", "network", "display", "lora", "bluetooth", "security"]:
                try:
                    iface.localNode.writeConfig(section)
                except Exception:
                    logger.debug("Could not write config section %s", section)

        if "moduleConfig" in config:
            module_config = iface.localNode.moduleConfig
            ParseDict(config["moduleConfig"], module_config)
            for section in ["mqtt", "serial", "extNotification", "storeForward", "rangeTest", "telemetry", "cannedMessage", "audio", "remoteHardware", "neighborInfo", "ambientLighting", "detectionSensor", "paxcounter"]:
                try:
                    iface.localNode.writeConfig(section)
                except Exception:
                    logger.debug("Could not write module config section %s", section)
