"""Diagnostic rules engine — evaluates radio state and produces findings."""

from __future__ import annotations

import time
from typing import Callable

from server.models import DiagnosticFinding, RadioState, Severity


# Each rule is a function: (RadioState) -> list[DiagnosticFinding]
RuleFunc = Callable[[RadioState], list[DiagnosticFinding]]

_rules: list[RuleFunc] = []


def rule(fn: RuleFunc) -> RuleFunc:
    """Decorator to register a diagnostic rule."""
    _rules.append(fn)
    return fn


def run_diagnostics(state: RadioState) -> list[DiagnosticFinding]:
    """Run all registered rules against the radio state. Returns sorted findings."""
    if not state.connected:
        return []
    findings: list[DiagnosticFinding] = []
    for rule_fn in _rules:
        try:
            findings.extend(rule_fn(state))
        except Exception:
            findings.append(DiagnosticFinding(
                id=f"rule_error_{rule_fn.__name__}",
                severity=Severity.WARNING,
                title=f"Rule error: {rule_fn.__name__}",
                description=f"Diagnostic rule '{rule_fn.__name__}' threw an exception.",
                recommendation="Report this bug.",
            ))
    # Sort: critical first, then warning, info, ok
    order = {Severity.CRITICAL: 0, Severity.WARNING: 1, Severity.INFO: 2, Severity.OK: 3}
    findings.sort(key=lambda f: order.get(f.severity, 99))
    return findings


# ── Rules ────────────────────────────────────────────────────────────────


@rule
def check_is_managed(state: RadioState) -> list[DiagnosticFinding]:
    if state.security.is_managed:
        return [DiagnosticFinding(
            id="is_managed",
            severity=Severity.CRITICAL,
            title="Device is managed",
            description=(
                "is_managed is true. Config changes via serial will be silently "
                "ignored — the device only accepts changes from its admin key holder."
            ),
            recommendation="Disable is_managed via the admin client, or use the admin key.",
            related_action="set_is_managed",
        )]
    return [DiagnosticFinding(
        id="is_managed",
        severity=Severity.OK,
        title="Device is not managed",
        description="Config changes via serial will be accepted.",
        recommendation="",
    )]


@rule
def check_admin_key(state: RadioState) -> list[DiagnosticFinding]:
    if not state.security.admin_key_set:
        return [DiagnosticFinding(
            id="admin_key",
            severity=Severity.INFO,
            title="No admin key set",
            description="Anyone with serial access can modify this device.",
            recommendation="Set an admin key for field-deployed devices.",
            related_action="set_admin_key",
        )]
    return []


@rule
def check_position_precision(state: RadioState) -> list[DiagnosticFinding]:
    findings = []
    for ch in state.channels:
        if ch.role == "DISABLED":
            continue
        prec = ch.position_precision
        if 0 < prec < 32:
            findings.append(DiagnosticFinding(
                id=f"position_precision_ch{ch.index}",
                severity=Severity.WARNING,
                title=f"Ch {ch.index}: position masked ({prec} bits)",
                description=(
                    f"Channel '{ch.name or ch.index}' has position_precision={prec}. "
                    f"Coordinates are truncated, which can offset positions by kilometers. "
                    f"TAK integration requires precision=32 for accurate mapping."
                ),
                recommendation="Set position_precision to 32 for full GPS accuracy.",
                related_action="set_position_precision",
            ))
        elif prec == 0:
            findings.append(DiagnosticFinding(
                id=f"position_precision_ch{ch.index}",
                severity=Severity.INFO,
                title=f"Ch {ch.index}: position sharing disabled",
                description=f"Channel '{ch.name or ch.index}' has position_precision=0 (no position sharing).",
                recommendation="Set to 32 if position sharing is needed for TAK.",
                related_action="set_position_precision",
            ))
    return findings


@rule
def check_gps_health(state: RadioState) -> list[DiagnosticFinding]:
    gps = state.gps
    if gps.fix_type in ("NO_FIX", "0", "NO_GPS"):
        return [DiagnosticFinding(
            id="gps_no_fix",
            severity=Severity.WARNING,
            title="No GPS fix",
            description="Device has no GPS fix. Position will not be broadcast.",
            recommendation="Move to open sky, check GPS antenna, or set a fixed position.",
            related_action="set_fixed_position",
        )]
    findings = []
    if gps.satellites < 4:
        findings.append(DiagnosticFinding(
            id="gps_low_sats",
            severity=Severity.WARNING,
            title=f"Low satellite count ({gps.satellites})",
            description="Fewer than 4 satellites. Fix may be inaccurate.",
            recommendation="Improve sky visibility or wait for satellite acquisition.",
        ))
    if gps.last_fix_age_s is not None and gps.last_fix_age_s > 300:
        age_min = int(gps.last_fix_age_s / 60)
        findings.append(DiagnosticFinding(
            id="gps_stale",
            severity=Severity.WARNING,
            title=f"GPS fix stale ({age_min}m ago)",
            description=f"Last GPS update was {age_min} minutes ago.",
            recommendation="Check GPS antenna connection or restart device.",
        ))
    if not findings:
        findings.append(DiagnosticFinding(
            id="gps_ok",
            severity=Severity.OK,
            title=f"GPS OK ({gps.satellites} sats, {gps.fix_type})",
            description="GPS has a valid fix.",
            recommendation="",
        ))
    return findings


@rule
def check_default_psk(state: RadioState) -> list[DiagnosticFinding]:
    findings = []
    for ch in state.channels:
        if ch.role == "DISABLED":
            continue
        if ch.psk_type == "default":
            findings.append(DiagnosticFinding(
                id=f"default_psk_ch{ch.index}",
                severity=Severity.CRITICAL,
                title=f"Ch {ch.index}: default PSK",
                description=(
                    f"Channel '{ch.name or ch.index}' uses the default public key (AQ==). "
                    f"All traffic is readable by anyone with a Meshtastic radio."
                ),
                recommendation="Set a custom PSK for operational use.",
                related_action="set_psk",
            ))
        elif ch.psk_type == "none":
            findings.append(DiagnosticFinding(
                id=f"no_psk_ch{ch.index}",
                severity=Severity.CRITICAL,
                title=f"Ch {ch.index}: no encryption",
                description=f"Channel '{ch.name or ch.index}' has no PSK. Traffic is unencrypted.",
                recommendation="Set a PSK for any operational channel.",
                related_action="set_psk",
            ))
        elif ch.psk_type == "simple":
            findings.append(DiagnosticFinding(
                id=f"simple_psk_ch{ch.index}",
                severity=Severity.WARNING,
                title=f"Ch {ch.index}: simple PSK",
                description=f"Channel '{ch.name or ch.index}' uses a simple 1-byte key. Easily brute-forced.",
                recommendation="Use a full 256-bit PSK for security.",
                related_action="set_psk",
            ))
    return findings


@rule
def check_battery(state: RadioState) -> list[DiagnosticFinding]:
    pwr = state.power
    if pwr.battery_pct is not None:
        if pwr.battery_pct <= 10:
            return [DiagnosticFinding(
                id="battery_critical",
                severity=Severity.CRITICAL,
                title=f"Battery critical ({pwr.battery_pct}%)",
                description=f"Battery at {pwr.battery_pct}%, {pwr.voltage:.2f}V." if pwr.voltage else f"Battery at {pwr.battery_pct}%.",
                recommendation="Charge device immediately.",
            )]
        elif pwr.battery_pct <= 25:
            return [DiagnosticFinding(
                id="battery_low",
                severity=Severity.WARNING,
                title=f"Battery low ({pwr.battery_pct}%)",
                description=f"Battery at {pwr.battery_pct}%.",
                recommendation="Consider charging soon.",
            )]
        elif pwr.battery_pct == 101:
            # 101 = powered externally / no battery
            return [DiagnosticFinding(
                id="battery_external",
                severity=Severity.OK,
                title="External power (no battery)",
                description="Device reports external power, no battery.",
                recommendation="",
            )]
    return []


@rule
def check_nodedb(state: RadioState) -> list[DiagnosticFinding]:
    # Exclude self from count
    other_nodes = [n for n in state.nodedb if n.node_id != state.node_id]
    if len(other_nodes) == 0:
        return [DiagnosticFinding(
            id="nodedb_empty",
            severity=Severity.WARNING,
            title="No other nodes heard",
            description="Nodedb is empty — this device hasn't received any packets from other mesh nodes.",
            recommendation="Check antenna connection, verify channel/region settings match other nodes.",
        )]
    # Check for stale nodes
    now = int(time.time())
    stale = [n for n in other_nodes if n.last_heard and (now - n.last_heard) > 3600]
    findings = [DiagnosticFinding(
        id="nodedb_populated",
        severity=Severity.OK,
        title=f"{len(other_nodes)} nodes in mesh",
        description=f"Hearing {len(other_nodes)} other node(s).",
        recommendation="",
    )]
    if stale:
        findings.append(DiagnosticFinding(
            id="nodedb_stale",
            severity=Severity.INFO,
            title=f"{len(stale)} stale nodes (>1h)",
            description=f"{len(stale)} node(s) haven't been heard in over an hour.",
            recommendation="These nodes may be offline or out of range.",
        ))
    return findings


@rule
def check_channel_utilization(state: RadioState) -> list[DiagnosticFinding]:
    util = state.device_metrics.channel_utilization
    if util > 50:
        return [DiagnosticFinding(
            id="channel_util_high",
            severity=Severity.CRITICAL,
            title=f"Channel utilization {util:.0f}%",
            description="Channel is heavily loaded. Expect packet loss and latency.",
            recommendation="Reduce transmission frequency or switch to a less busy channel.",
        )]
    elif util > 25:
        return [DiagnosticFinding(
            id="channel_util_moderate",
            severity=Severity.WARNING,
            title=f"Channel utilization {util:.0f}%",
            description="Channel utilization is moderate.",
            recommendation="Monitor for increasing congestion.",
        )]
    elif util > 0:
        return [DiagnosticFinding(
            id="channel_util_ok",
            severity=Severity.OK,
            title=f"Channel utilization {util:.0f}%",
            description="Channel utilization is healthy.",
            recommendation="",
        )]
    return []


@rule
def check_reboot_count(state: RadioState) -> list[DiagnosticFinding]:
    count = state.power.reboot_count if hasattr(state.power, "reboot_count") else 0
    if count >= 10:
        return [DiagnosticFinding(
            id="reboot_count_high",
            severity=Severity.WARNING,
            title=f"High reboot count ({count})",
            description=f"Device has rebooted {count} times. May indicate instability.",
            recommendation="Check power supply, firmware version, and device logs.",
        )]
    return []


@rule
def check_serial_enabled(state: RadioState) -> list[DiagnosticFinding]:
    if not state.security.serial_enabled:
        return [DiagnosticFinding(
            id="serial_disabled",
            severity=Severity.INFO,
            title="Serial console disabled",
            description="Serial is disabled in device config. This tool won't be able to modify settings after disconnect.",
            recommendation="Enable serial if you need diagnostic access.",
            related_action="set_serial_enabled",
        )]
    return []


@rule
def check_hop_limit(state: RadioState) -> list[DiagnosticFinding]:
    if state.hop_limit > 5:
        return [DiagnosticFinding(
            id="hop_limit_high",
            severity=Severity.WARNING,
            title=f"Hop limit high ({state.hop_limit})",
            description="High hop limit increases channel congestion from rebroadcasts.",
            recommendation="Set hop limit to 3-5 for most deployments.",
            related_action="set_hop_limit",
        )]
    return []
