from datetime import datetime, timezone

from src.analysis.keyword_filter import KeywordFilter
from src.models import RawIncident


def _make_incident(title: str, desc: str = "", structured: bool = False) -> RawIncident:
    return RawIncident(
        source_name="test",
        title=title,
        description=desc,
        url="https://example.com",
        published=datetime.now(timezone.utc),
        structured=structured,
    )


class TestKeywordFilter:
    def test_matches_title(self):
        kf = KeywordFilter(["shooting"])
        result = kf.filter([_make_incident("Shooting reported downtown")])
        assert len(result) == 1

    def test_matches_description(self):
        kf = KeywordFilter(["explosion"])
        result = kf.filter([_make_incident("Breaking news", "Large explosion at warehouse")])
        assert len(result) == 1

    def test_case_insensitive(self):
        kf = KeywordFilter(["FIRE"])
        result = kf.filter([_make_incident("Structure fire on Main St")])
        assert len(result) == 1

    def test_no_match_filtered(self):
        kf = KeywordFilter(["shooting", "fire"])
        result = kf.filter([_make_incident("Local park opens new playground")])
        assert len(result) == 0

    def test_empty_keywords_passes_all(self):
        kf = KeywordFilter([])
        incidents = [_make_incident("Anything"), _make_incident("Everything")]
        result = kf.filter(incidents)
        assert len(result) == 2

    def test_structured_always_passes(self):
        kf = KeywordFilter(["shooting"])
        inc = _make_incident("Earthquake M4.2", structured=True)
        result = kf.filter([inc])
        assert len(result) == 1

    def test_regex_special_chars_escaped(self):
        kf = KeywordFilter(["fire (residential)"])
        inc = _make_incident("fire (residential) on elm street")
        result = kf.filter([inc])
        assert len(result) == 1

    def test_multiple_keywords_any_match(self):
        kf = KeywordFilter(["shooting", "robbery", "assault"])
        result = kf.filter([
            _make_incident("Robbery at convenience store"),
            _make_incident("Weather forecast today"),
        ])
        assert len(result) == 1
        assert result[0].title == "Robbery at convenience store"
