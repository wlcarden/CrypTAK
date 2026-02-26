import xml.etree.ElementTree as ET
from datetime import datetime, timezone

from src.cot.builder import build_cot
from src.models import AnalyzedIncident


def _make_analyzed(**overrides) -> AnalyzedIncident:
    defaults = {
        "source_name": "TestSource",
        "title": "Test Incident",
        "summary": "A test incident occurred",
        "url": "https://example.com/article",
        "published": datetime.now(timezone.utc),
        "lat": 35.9606,
        "lon": -83.9207,
        "category": "fire",
        "severity": "high",
        "cot_type": "a-n-G-I-i-f",
        "stale_minutes": 120,
        "uid": "incident-tracker-test-uid",
    }
    defaults.update(overrides)
    return AnalyzedIncident(**defaults)


class TestBuildCot:
    def test_valid_xml(self):
        inc = _make_analyzed()
        xml_str = build_cot(inc)
        root = ET.fromstring(xml_str)
        assert root.tag == "event"

    def test_event_attributes(self):
        inc = _make_analyzed()
        root = ET.fromstring(build_cot(inc))
        assert root.get("version") == "2.0"
        assert root.get("uid") == "incident-tracker-test-uid"
        assert root.get("type") == "a-n-G-I-i-f"
        assert root.get("how") == "m-g"

    def test_point_element(self):
        inc = _make_analyzed(lat=36.1234, lon=-84.5678)
        root = ET.fromstring(build_cot(inc))
        point = root.find("point")
        assert point is not None
        assert point.get("lat") == "36.123400"
        assert point.get("lon") == "-84.567800"
        assert point.get("hae") == "0"
        assert point.get("ce") == "9999999"

    def test_callsign_has_incident_type_and_timestamp(self):
        inc = _make_analyzed(title="ROBBERY: 1300 BLOCK OF K ST NW")
        root = ET.fromstring(build_cot(inc))
        contact = root.find("detail/contact")
        assert contact is not None
        cs = contact.get("callsign")
        assert "ROBBERY" in cs
        assert "/" in cs  # date from MM/DD HH:MM

    def test_composed_hostile_type(self):
        inc = _make_analyzed(cot_type="a-h-G-I-i-l")
        root = ET.fromstring(build_cot(inc))
        assert root.get("type") == "a-h-G-I-i-l"

    def test_composed_suspect_type(self):
        inc = _make_analyzed(cot_type="a-s-G-I-i-l")
        root = ET.fromstring(build_cot(inc))
        assert root.get("type") == "a-s-G-I-i-l"

    def test_remarks_content(self):
        inc = _make_analyzed()
        root = ET.fromstring(build_cot(inc))
        remarks = root.find("detail/remarks")
        assert remarks is not None
        assert "A test incident occurred" in remarks.text
        assert "TestSource" in remarks.text
        assert "https://example.com/article" in remarks.text

    def test_stale_time_calculation(self):
        inc = _make_analyzed(stale_minutes=60)
        root = ET.fromstring(build_cot(inc))
        start = root.get("start")
        stale = root.get("stale")
        assert start is not None
        assert stale is not None
        assert stale > start

    def test_special_chars_escaped(self):
        inc = _make_analyzed(summary='Fire at "Joe\'s Bar & Grill" <downtown>')
        xml_str = build_cot(inc)
        root = ET.fromstring(xml_str)
        remarks = root.find("detail/remarks")
        assert "&" in remarks.text or "&amp;" in ET.tostring(root, encoding="unicode")

    def test_remarks_pipe_delimited_format(self):
        inc = _make_analyzed(
            title="ROBBERY: 1300 BLOCK OF K ST NW",
            summary="Armed robbery reported",
            source_name="DC Crime",
            severity="high",
            url="https://example.com/crime",
        )
        root = ET.fromstring(build_cot(inc))
        remarks = root.find("detail/remarks")
        parts = remarks.text.split(" | ")
        assert len(parts) == 6
        assert parts[0] == "Armed robbery reported"
        assert parts[1] == "1300 BLOCK OF K ST NW"
        assert parts[2] == "DC Crime"
        assert parts[3] == "high"
        assert parts[4] == "https://example.com/crime"
        assert parts[5].endswith("Z")  # published_utc ISO timestamp

    def test_remarks_no_location_when_no_colon(self):
        inc = _make_analyzed(title="Tornado Warning for Fairfax County")
        root = ET.fromstring(build_cot(inc))
        remarks = root.find("detail/remarks")
        parts = remarks.text.split(" | ")
        assert parts[1] == ""  # no colon in title → empty location

    def test_callsign_uses_display_timezone(self):
        # Published at 20:00 UTC = 15:00 Eastern
        pub = datetime(2026, 2, 26, 20, 0, 0, tzinfo=timezone.utc)
        inc = _make_analyzed(published=pub, title="TEST: Location")
        root = ET.fromstring(build_cot(inc, display_tz="America/New_York"))
        cs = root.find("detail/contact").get("callsign")
        assert "15:00" in cs  # Eastern time, not 20:00 UTC

    def test_callsign_defaults_to_utc(self):
        pub = datetime(2026, 2, 26, 20, 0, 0, tzinfo=timezone.utc)
        inc = _make_analyzed(published=pub, title="TEST: Location")
        root = ET.fromstring(build_cot(inc))
        cs = root.find("detail/contact").get("callsign")
        assert "20:00" in cs  # UTC

    def test_remarks_published_utc_field(self):
        pub = datetime(2026, 2, 26, 20, 30, 0, tzinfo=timezone.utc)
        inc = _make_analyzed(published=pub)
        root = ET.fromstring(build_cot(inc))
        remarks = root.find("detail/remarks")
        parts = remarks.text.split(" | ")
        # Last field is published timestamp in UTC
        assert "2026-02-26T20:30:00" in parts[5]

    def test_start_and_time_are_current(self):
        """start and time must be ~now (FTS drops events with start in the past)."""
        old_pub = datetime(2026, 2, 1, 12, 0, 0, tzinfo=timezone.utc)
        inc = _make_analyzed(published=old_pub, stale_minutes=120)
        root = ET.fromstring(build_cot(inc))
        now = datetime.now(timezone.utc)
        for attr in ("time", "start"):
            dt = datetime.strptime(root.get(attr), "%Y-%m-%dT%H:%M:%S.%fZ")
            assert dt.year == now.year
            assert dt.month == now.month
            assert dt.day == now.day

    def test_no_embedded_newlines(self):
        inc = _make_analyzed(summary="Line one\nLine two")
        xml_str = build_cot(inc)
        assert xml_str.count("\n") <= 1

    def test_callsign_includes_prefix(self):
        inc = _make_analyzed(title="FIRE: 5th and Main")
        root = ET.fromstring(build_cot(inc, callsign_prefix="OPS"))
        cs = root.find("detail/contact").get("callsign")
        assert cs.startswith("OPS ")
        assert "FIRE" in cs

    def test_html_stripped_from_remarks(self):
        inc = _make_analyzed(
            summary='<script>alert("xss")</script>Real summary',
            title='<b>Bold</b>: Location',
        )
        root = ET.fromstring(build_cot(inc))
        remarks = root.find("detail/remarks").text
        assert "<script>" not in remarks
        assert "<b>" not in remarks
        assert "Real summary" in remarks

    def test_invalid_timezone_falls_back_to_utc(self):
        pub = datetime(2026, 2, 26, 20, 0, 0, tzinfo=timezone.utc)
        inc = _make_analyzed(published=pub, title="TEST: Location")
        root = ET.fromstring(build_cot(inc, display_tz="Invalid/Timezone"))
        cs = root.find("detail/contact").get("callsign")
        # Should fall back to UTC
        assert "20:00" in cs
