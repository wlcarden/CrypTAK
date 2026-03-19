"""Pydantic models for mesh-diag radio state, diagnostics, and config."""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class ChannelInfo(BaseModel):
    index: int
    name: str = ""
    psk_type: str = "unknown"  # "none", "default", "simple", "custom"
    psk_hash: str = ""  # first 8 chars of hex-encoded PSK for display
    role: str = "DISABLED"
    position_precision: int = 0
    uplink_enabled: bool = False
    downlink_enabled: bool = False


class GpsInfo(BaseModel):
    fix_type: str = "NO_FIX"
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    altitude: Optional[float] = None
    satellites: int = 0
    hdop: float = 0.0
    last_fix_age_s: Optional[float] = None


class PowerInfo(BaseModel):
    battery_pct: Optional[int] = None
    voltage: Optional[float] = None
    is_charging: bool = False
    usb_connected: bool = False
    uptime_s: int = 0
    reboot_count: int = 0


class SecurityInfo(BaseModel):
    is_managed: bool = False
    serial_enabled: bool = True
    admin_channel_enabled: bool = False
    admin_key_set: bool = False


class NeighborInfo(BaseModel):
    node_id: str
    snr: float = 0.0
    long_name: str = ""
    short_name: str = ""


class DeviceMetrics(BaseModel):
    channel_utilization: float = 0.0
    air_util_tx: float = 0.0
    voltage: Optional[float] = None
    battery_level: Optional[int] = None


class NodeInfo(BaseModel):
    node_id: str
    long_name: str = ""
    short_name: str = ""
    hw_model: str = "UNKNOWN"
    snr: Optional[float] = None
    last_heard: Optional[int] = None


class RadioState(BaseModel):
    connected: bool = False
    port: str = ""
    node_id: str = ""
    long_name: str = ""
    short_name: str = ""
    hw_model: str = ""
    firmware_version: str = ""
    region: str = ""
    modem_preset: str = ""
    hop_limit: int = 3
    role: str = ""
    channels: list[ChannelInfo] = Field(default_factory=list)
    gps: GpsInfo = Field(default_factory=GpsInfo)
    power: PowerInfo = Field(default_factory=PowerInfo)
    security: SecurityInfo = Field(default_factory=SecurityInfo)
    device_metrics: DeviceMetrics = Field(default_factory=DeviceMetrics)
    nodedb: list[NodeInfo] = Field(default_factory=list)
    neighbors: list[NeighborInfo] = Field(default_factory=list)
    timestamp: datetime = Field(default_factory=datetime.now)


class Severity(str, Enum):
    CRITICAL = "critical"
    WARNING = "warning"
    INFO = "info"
    OK = "ok"


class DiagnosticFinding(BaseModel):
    id: str
    severity: Severity
    title: str
    description: str
    recommendation: str
    related_action: Optional[str] = None  # key for Modify tab action


class ConfigBackup(BaseModel):
    id: str  # filename stem
    node_id: str
    node_name: str
    timestamp: datetime
    filename: str
    size_bytes: int


class ModifyRequest(BaseModel):
    action: str  # e.g. "set_position_precision", "set_psk", "apply_profile"
    params: dict = Field(default_factory=dict)


class ModifyResult(BaseModel):
    success: bool
    message: str
    backup_id: Optional[str] = None
    before: Optional[dict] = None
    after: Optional[dict] = None


class LogEntry(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.now)
    event: str  # "connect", "disconnect", "diagnose", "modify", "backup", "restore"
    node_id: str = ""
    details: dict = Field(default_factory=dict)
