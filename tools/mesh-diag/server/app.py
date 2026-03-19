"""FastAPI backend for mesh-diag — radio status, diagnostics, config management."""

from __future__ import annotations

import asyncio
import json
import logging
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from server.models import (
    ConfigBackup,
    DiagnosticFinding,
    LogEntry,
    ModifyRequest,
    ModifyResult,
    RadioState,
)
from server.radio import RadioManager
from server.diagnostics import run_diagnostics
from server import config_backup

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)

# ── Session log ──────────────────────────────────────────────────────────

LOG_DIR = Path(__file__).resolve().parent.parent / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)

session_log: list[LogEntry] = []


def log_event(event: str, node_id: str = "", **details: Any) -> LogEntry:
    entry = LogEntry(event=event, node_id=node_id, details=details)
    session_log.append(entry)
    return entry


# ── WebSocket connections ────────────────────────────────────────────────

ws_clients: set[WebSocket] = set()


async def broadcast(msg_type: str, data: Any) -> None:
    """Send a JSON message to all connected WebSocket clients."""
    payload = json.dumps({"type": msg_type, "data": data}, default=str)
    logger.info("Broadcasting %s to %d clients", msg_type, len(ws_clients))
    stale = set()
    for ws in ws_clients:
        try:
            await ws.send_text(payload)
        except Exception as e:
            logger.warning("WebSocket send failed: %s", e)
            stale.add(ws)
    ws_clients.difference_update(stale)


# ── Radio manager callbacks ─────────────────────────────────────────────

_loop: asyncio.AbstractEventLoop | None = None


def _on_radio_state_change(event: str, state: RadioState) -> None:
    """Called from the radio polling thread when state changes."""
    logger.info("Radio state change: %s (loop=%s)", event, _loop is not None)
    if _loop is None:
        return
    data = state.model_dump(mode="json")
    log_event(event, node_id=state.node_id)
    future = asyncio.run_coroutine_threadsafe(broadcast(event, data), _loop)
    try:
        future.result(timeout=5)
    except Exception as e:
        logger.error("Broadcast failed: %s", e)


radio = RadioManager(on_state_change=_on_radio_state_change)

# ── Lifespan ─────────────────────────────────────────────────────────────


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _loop
    _loop = asyncio.get_running_loop()
    radio.start_polling()
    logger.info("mesh-diag server started")
    yield
    radio.stop_polling()
    logger.info("mesh-diag server stopped")


# ── App ──────────────────────────────────────────────────────────────────

app = FastAPI(title="mesh-diag", version="0.1.0", lifespan=lifespan)

# Serve UI static files
UI_DIR = Path(__file__).resolve().parent.parent / "ui"
app.mount("/ui", StaticFiles(directory=str(UI_DIR)), name="ui")


@app.get("/")
async def index():
    return FileResponse(UI_DIR / "index.html")


# ── API endpoints ────────────────────────────────────────────────────────


@app.get("/api/status")
async def get_status() -> dict:
    """Current radio state, or {connected: false} if no radio."""
    state = await asyncio.to_thread(radio.refresh_state) if radio.connected else RadioState()
    return state.model_dump(mode="json")


@app.get("/api/diagnose")
async def get_diagnose() -> list[dict]:
    """Run diagnostics on current radio state."""
    if not radio.connected:
        raise HTTPException(status_code=404, detail="No radio connected")
    state = await asyncio.to_thread(radio.refresh_state)
    findings = run_diagnostics(state)
    log_event("diagnose", node_id=state.node_id, finding_count=len(findings))
    return [f.model_dump(mode="json") for f in findings]


@app.post("/api/modify")
async def post_modify(req: ModifyRequest) -> dict:
    """Apply a config change. Auto-backs up config first."""
    if not radio.connected:
        raise HTTPException(status_code=404, detail="No radio connected")

    state_before = await asyncio.to_thread(radio.refresh_state)

    # Auto-backup before any modification
    try:
        config_data = await asyncio.to_thread(radio.export_config)
        backup = config_backup.save_backup(
            config_data,
            node_id=state_before.node_id,
            node_name=state_before.long_name,
        )
        log_event("backup", node_id=state_before.node_id, backup_id=backup.id)
    except Exception as e:
        logger.error("Auto-backup failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Auto-backup failed: {e}")

    # Apply the modification
    try:
        result = await _apply_modification(req, state_before)
        result.backup_id = backup.id
    except Exception as e:
        logger.error("Modify failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))

    state_after = await asyncio.to_thread(radio.refresh_state)
    result.before = state_before.model_dump(mode="json")
    result.after = state_after.model_dump(mode="json")

    log_event(
        "modify",
        node_id=state_before.node_id,
        action=req.action,
        params=req.params,
        success=result.success,
    )

    # Broadcast updated state
    await broadcast("status_update", state_after.model_dump(mode="json"))

    return result.model_dump(mode="json")


async def _apply_modification(req: ModifyRequest, state: RadioState) -> ModifyResult:
    """Route a modify request to the appropriate radio operation."""
    action = req.action
    params = req.params

    if action == "set_position_precision":
        ch_index = int(params.get("channel_index", 0))
        value = int(params.get("value", 32))
        await asyncio.to_thread(radio.set_channel_setting, ch_index, "position_precision", value)
        return ModifyResult(success=True, message=f"Set position_precision={value} on channel {ch_index}")

    elif action == "set_psk":
        ch_index = int(params.get("channel_index", 0))
        psk_b64 = params.get("psk", "")
        if not psk_b64:
            return ModifyResult(success=False, message="PSK value required")
        await asyncio.to_thread(radio.set_channel_setting, ch_index, "psk", psk_b64)
        return ModifyResult(success=True, message=f"Set PSK on channel {ch_index}")

    elif action == "set_hop_limit":
        value = int(params.get("value", 3))
        await asyncio.to_thread(radio.apply_setting, "lora.hop_limit", value)
        return ModifyResult(success=True, message=f"Set hop_limit={value}")

    elif action == "set_is_managed":
        value = bool(params.get("value", False))
        await asyncio.to_thread(radio.apply_setting, "security.is_managed", value)
        return ModifyResult(success=True, message=f"Set is_managed={value}")

    elif action == "set_serial_enabled":
        value = bool(params.get("value", True))
        await asyncio.to_thread(radio.apply_setting, "device.serial_enabled", value)
        return ModifyResult(success=True, message=f"Set serial_enabled={value}")

    elif action == "set_fixed_position":
        lat = float(params.get("latitude", 0))
        lon = float(params.get("longitude", 0))
        alt = int(params.get("altitude", 0))
        await asyncio.to_thread(radio.apply_setting, "position.fixed_position", True)
        await asyncio.to_thread(radio.apply_setting, "position.latitude_i", int(lat * 1e7))
        await asyncio.to_thread(radio.apply_setting, "position.longitude_i", int(lon * 1e7))
        await asyncio.to_thread(radio.apply_setting, "position.altitude", alt)
        return ModifyResult(success=True, message=f"Set fixed position: {lat}, {lon}")

    elif action == "set_region":
        value = params.get("value", "")
        await asyncio.to_thread(radio.apply_setting, "lora.region", value)
        return ModifyResult(success=True, message=f"Set region={value}")

    elif action == "set_channel_name":
        ch_index = int(params.get("channel_index", 0))
        name = params.get("value", "")
        await asyncio.to_thread(radio.set_channel_setting, ch_index, "name", name)
        return ModifyResult(success=True, message=f"Set channel {ch_index} name='{name}'")

    elif action == "set_any":
        # Generic setting change: params = {"field": "lora.region", "value": "US"}
        field = params.get("field", "")
        value = params.get("value")
        if not field:
            return ModifyResult(success=False, message="'field' parameter required")
        # Try to coerce value to appropriate type
        if isinstance(value, str):
            if value.lower() in ("true", "false"):
                value = value.lower() == "true"
            else:
                try:
                    value = int(value)
                except (ValueError, TypeError):
                    try:
                        value = float(value)
                    except (ValueError, TypeError):
                        pass  # keep as string
        await asyncio.to_thread(radio.apply_setting, field, value)
        return ModifyResult(success=True, message=f"Set {field}={value}")

    elif action == "set_channel_field":
        # Generic channel setting: params = {"channel_index": 0, "field": "name", "value": "MyChannel"}
        ch_index = int(params.get("channel_index", 0))
        field = params.get("field", "")
        value = params.get("value")
        if not field:
            return ModifyResult(success=False, message="'field' parameter required")
        if isinstance(value, str) and value.isdigit():
            value = int(value)
        await asyncio.to_thread(radio.set_channel_setting, ch_index, field, value)
        return ModifyResult(success=True, message=f"Set channel {ch_index} {field}={value}")

    elif action == "factory_reset":
        await asyncio.to_thread(radio.factory_reset)
        return ModifyResult(success=True, message="Factory reset sent. Device will reboot.")

    elif action == "reboot":
        await asyncio.to_thread(radio.reboot)
        return ModifyResult(success=True, message="Reboot command sent.")

    elif action == "set_owner":
        long_name = params.get("long_name", "")
        short_name = params.get("short_name", "")
        await asyncio.to_thread(radio.set_owner, long_name, short_name)
        return ModifyResult(success=True, message=f"Set owner: {long_name} ({short_name})")

    elif action == "export_backup_only":
        return ModifyResult(success=True, message="Config backup exported")

    else:
        return ModifyResult(success=False, message=f"Unknown action: {action}")


@app.get("/api/settings")
async def get_settings() -> dict:
    """Get all config settings as a flat key-value dict."""
    if not radio.connected:
        raise HTTPException(status_code=404, detail="No radio connected")
    return await asyncio.to_thread(radio.get_all_settings)


@app.get("/api/backups")
async def get_backups() -> list[dict]:
    """List all config backups."""
    backups = config_backup.list_backups()
    return [b.model_dump(mode="json") for b in backups]


@app.post("/api/restore/{backup_id}")
async def post_restore(backup_id: str) -> dict:
    """Restore a config backup to the connected radio."""
    if not radio.connected:
        raise HTTPException(status_code=404, detail="No radio connected")

    try:
        config_data = config_backup.load_backup(backup_id)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"Backup not found: {backup_id}")

    # Backup current config before restoring
    state = await asyncio.to_thread(radio.refresh_state)
    try:
        current_config = await asyncio.to_thread(radio.export_config)
        pre_restore_backup = config_backup.save_backup(
            current_config,
            node_id=state.node_id,
            node_name=state.long_name,
        )
        log_event("backup", node_id=state.node_id, backup_id=pre_restore_backup.id, reason="pre-restore")
    except Exception:
        pass  # Best-effort backup before restore

    try:
        await asyncio.to_thread(radio.import_config, config_data)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Restore failed: {e}")

    new_state = await asyncio.to_thread(radio.refresh_state)
    log_event("restore", node_id=state.node_id, backup_id=backup_id)
    await broadcast("status_update", new_state.model_dump(mode="json"))

    return {"success": True, "message": f"Restored config from {backup_id}"}


@app.get("/api/log")
async def get_log() -> list[dict]:
    """Return session log entries."""
    return [e.model_dump(mode="json") for e in session_log]


# ── WebSocket ────────────────────────────────────────────────────────────


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    await ws.accept()
    ws_clients.add(ws)
    logger.info("WebSocket client connected (%d total)", len(ws_clients))

    # Send current state immediately
    state = radio.state
    await ws.send_text(json.dumps({
        "type": "status_update",
        "data": state.model_dump(mode="json"),
    }, default=str))

    try:
        while True:
            # Keep connection alive; handle client messages if needed
            data = await ws.receive_text()
            msg = json.loads(data)
            if msg.get("type") == "refresh":
                state = await asyncio.to_thread(radio.refresh_state) if radio.connected else RadioState()
                await ws.send_text(json.dumps({
                    "type": "status_update",
                    "data": state.model_dump(mode="json"),
                }, default=str))
    except WebSocketDisconnect:
        pass
    except Exception:
        logger.debug("WebSocket error", exc_info=True)
    finally:
        ws_clients.discard(ws)
        logger.info("WebSocket client disconnected (%d remaining)", len(ws_clients))


# ── Main ─────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=5555, log_level="info")
