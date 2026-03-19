"""Config backup manager — saves/lists/restores full radio config exports."""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime
from pathlib import Path

from server.models import ConfigBackup

logger = logging.getLogger(__name__)

BACKUP_DIR = Path(__file__).resolve().parent.parent / "backups"


def _ensure_dir() -> None:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)


def save_backup(config: dict, node_id: str, node_name: str) -> ConfigBackup:
    """Save a config export to disk. Returns the backup metadata."""
    _ensure_dir()
    ts = datetime.now()
    safe_name = node_name.replace(" ", "_").replace("/", "_")[:20] or "unknown"
    safe_id = node_id.lstrip("!") or "unknown"
    filename = f"{ts.strftime('%Y%m%d_%H%M%S')}_{safe_id}_{safe_name}.json"
    filepath = BACKUP_DIR / filename

    with open(filepath, "w") as f:
        json.dump(config, f, indent=2, default=str)

    size = filepath.stat().st_size
    backup = ConfigBackup(
        id=filepath.stem,
        node_id=node_id,
        node_name=node_name,
        timestamp=ts,
        filename=filename,
        size_bytes=size,
    )
    logger.info("Saved config backup: %s (%d bytes)", filename, size)
    return backup


def list_backups() -> list[ConfigBackup]:
    """List all saved backups, newest first."""
    _ensure_dir()
    backups = []
    for filepath in sorted(BACKUP_DIR.glob("*.json"), reverse=True):
        try:
            stat = filepath.stat()
            # Parse metadata from filename: YYYYMMDD_HHMMSS_nodeid_name.json
            parts = filepath.stem.split("_", 3)
            ts_str = f"{parts[0]}_{parts[1]}" if len(parts) >= 2 else ""
            node_id = f"!{parts[2]}" if len(parts) >= 3 else ""
            node_name = parts[3] if len(parts) >= 4 else ""
            try:
                ts = datetime.strptime(ts_str, "%Y%m%d_%H%M%S")
            except ValueError:
                ts = datetime.fromtimestamp(stat.st_mtime)

            backups.append(ConfigBackup(
                id=filepath.stem,
                node_id=node_id,
                node_name=node_name,
                timestamp=ts,
                filename=filepath.name,
                size_bytes=stat.st_size,
            ))
        except Exception:
            logger.debug("Could not parse backup file %s", filepath.name, exc_info=True)
    return backups


def load_backup(backup_id: str) -> dict:
    """Load a backup config dict by its ID (filename stem)."""
    _ensure_dir()
    filepath = BACKUP_DIR / f"{backup_id}.json"
    if not filepath.exists():
        raise FileNotFoundError(f"Backup not found: {backup_id}")
    with open(filepath) as f:
        return json.load(f)


def delete_backup(backup_id: str) -> bool:
    """Delete a backup by ID. Returns True if deleted."""
    filepath = BACKUP_DIR / f"{backup_id}.json"
    if filepath.exists():
        filepath.unlink()
        return True
    return False
