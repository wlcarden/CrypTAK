from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from src.models import AnalyzedIncident

_DT_FMT = "%Y-%m-%dT%H:%M:%S.%fZ"
_HTML_TAG_RE = re.compile(r"<[^>]+>")


def _sanitize(text: str, max_len: int = 200) -> str:
    """Strip HTML tags and truncate. Prevents stored XSS via Node-RED popups."""
    clean = _HTML_TAG_RE.sub("", text)
    return clean[:max_len]


def build_cot(
    incident: AnalyzedIncident,
    callsign_prefix: str = "INCIDENT",
    display_tz: str = "UTC",
) -> str:
    """Build a CoT XML event string for an analyzed incident.

    The XML is a single line with no embedded newlines, suitable for
    sending over the FTS TCP protocol (terminated by \\n).
    """
    now = datetime.now(timezone.utc)
    stale = now + timedelta(minutes=incident.stale_minutes)

    # NOTE: start=now (not published) because FTS/Node-RED drops events with
    # start in the past. Real incident time is in remarks[5] (published_utc)
    # for Node-RED opacity/age calculations.
    event = ET.Element("event", {
        "version": "2.0",
        "uid": incident.uid,
        "type": incident.cot_type,
        "time": now.strftime(_DT_FMT),
        "start": now.strftime(_DT_FMT),
        "stale": stale.strftime(_DT_FMT),
        "how": "m-g",
    })

    ET.SubElement(event, "point", {
        "lat": f"{incident.lat:.6f}",
        "lon": f"{incident.lon:.6f}",
        "hae": "0",
        "ce": "9999999",
        "le": "9999999",
    })

    detail = ET.SubElement(event, "detail")

    # Callsign = at-a-glance "what and when" for the map label.
    # Uses incident.published (when it happened) in the AO's timezone.
    try:
        tz = ZoneInfo(display_tz)
    except (KeyError, Exception):
        tz = timezone.utc
    local_published = incident.published.astimezone(tz)
    ts = local_published.strftime("%m/%d %H:%M")
    label = _sanitize(incident.title.split(":")[0].strip(), 40)
    callsign = f"{callsign_prefix} {label} - {ts}"
    ET.SubElement(detail, "contact", {"callsign": callsign})

    # Remarks: pipe-delimited fields for Node-RED to parse into
    # tooltip (hover) and popup (click). Format:
    #   summary | location | source | severity | url | published_utc
    title_parts = incident.title.split(":", 1)
    location = title_parts[1].strip() if len(title_parts) > 1 else ""
    published_utc = incident.published.astimezone(timezone.utc).strftime(_DT_FMT)
    remarks_fields = [
        _sanitize(incident.summary),
        _sanitize(location, 100),
        _sanitize(incident.source_name, 50),
        _sanitize(incident.severity, 20),
        _sanitize(incident.url, 500),
        published_utc,
    ]
    remarks = ET.SubElement(detail, "remarks", {"source": "incident-tracker"})
    remarks.text = " | ".join(remarks_fields)

    ET.SubElement(detail, "link", {
        "uid": "incident-tracker",
        "type": "a-f-G-U-C",
        "relation": "p-p",
    })

    return ET.tostring(event, encoding="unicode")
