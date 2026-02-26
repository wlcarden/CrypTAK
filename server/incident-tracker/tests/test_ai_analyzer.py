import json
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.analysis.ai_analyzer import AiAnalyzer
from src.config import AiConfig, CategoryDef
from src.models import RawIncident


def _make_config(enabled: bool = True) -> AiConfig:
    return AiConfig(
        enabled=enabled,
        model="claude-haiku-4-5-20251001",
        max_calls_per_hour=10,
        criteria_prompt="Test criteria",
    )


def _make_categories() -> list[CategoryDef]:
    return [
        CategoryDef(name="fire", description="Fires", affiliation="neutral", icon_type="fire"),
        CategoryDef(
            name="traffic", description="Accidents",
            affiliation="unknown", icon_type="traffic",
        ),
    ]


def _make_incident() -> RawIncident:
    return RawIncident(
        source_name="test",
        title="Fire on Main Street",
        description="A large fire broke out at a warehouse on Main Street.",
        url="https://example.com",
        published=datetime.now(timezone.utc),
    )


class TestAiAnalyzer:
    @pytest.mark.asyncio
    async def test_disabled_returns_none(self):
        analyzer = AiAnalyzer(_make_config(enabled=False), _make_categories())
        result = await analyzer.analyze(_make_incident())
        assert result is None

    @pytest.mark.asyncio
    async def test_relevant_response_parsed(self):
        response_json = json.dumps({
            "relevant": True,
            "category": "fire",
            "location": "123 Main St, Knoxville, TN",
            "severity": "high",
            "summary": "Warehouse fire on Main Street",
        })

        mock_message = MagicMock()
        mock_message.content = [MagicMock(text=response_json)]

        mock_client = AsyncMock()
        mock_client.messages.create = AsyncMock(return_value=mock_message)

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(_make_config(), _make_categories())
            analyzer._client = mock_client
            result = await analyzer.analyze(_make_incident())

        assert result is not None
        assert result["relevant"] is True
        assert result["category"] == "fire"
        assert result["location"] == "123 Main St, Knoxville, TN"

    @pytest.mark.asyncio
    async def test_irrelevant_response_returns_none(self):
        response_json = json.dumps({"relevant": False})

        mock_message = MagicMock()
        mock_message.content = [MagicMock(text=response_json)]

        mock_client = AsyncMock()
        mock_client.messages.create = AsyncMock(return_value=mock_message)

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(_make_config(), _make_categories())
            analyzer._client = mock_client
            result = await analyzer.analyze(_make_incident())

        assert result is None

    @pytest.mark.asyncio
    async def test_malformed_json_returns_none(self):
        mock_message = MagicMock()
        mock_message.content = [MagicMock(text="not valid json at all")]

        mock_client = AsyncMock()
        mock_client.messages.create = AsyncMock(return_value=mock_message)

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(_make_config(), _make_categories())
            analyzer._client = mock_client
            result = await analyzer.analyze(_make_incident())

        assert result is None

    @pytest.mark.asyncio
    async def test_rate_limit_enforced(self):
        response_json = json.dumps({
            "relevant": True,
            "category": "fire",
            "location": "Main St",
            "severity": "high",
            "summary": "Fire",
        })

        mock_message = MagicMock()
        mock_message.content = [MagicMock(text=response_json)]

        mock_client = AsyncMock()
        mock_client.messages.create = AsyncMock(return_value=mock_message)

        config = _make_config()
        config.max_calls_per_hour = 2

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(config, _make_categories())
            analyzer._client = mock_client

            # First two should work
            r1 = await analyzer.analyze(_make_incident())
            r2 = await analyzer.analyze(_make_incident())
            # Third should be rate-limited
            r3 = await analyzer.analyze(_make_incident())

        assert r1 is not None
        assert r2 is not None
        assert r3 is None

    @pytest.mark.asyncio
    async def test_missing_fields_returns_none(self):
        response_json = json.dumps({
            "relevant": True,
            "category": "fire",
            # Missing: location, severity, summary
        })

        mock_message = MagicMock()
        mock_message.content = [MagicMock(text=response_json)]

        mock_client = AsyncMock()
        mock_client.messages.create = AsyncMock(return_value=mock_message)

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(_make_config(), _make_categories())
            analyzer._client = mock_client
            result = await analyzer.analyze(_make_incident())

        assert result is None

    @pytest.mark.asyncio
    async def test_markdown_fence_stripped(self):
        """AI sometimes wraps JSON in ```json ... ``` fencing."""
        fenced = '```json\n{"relevant": true, "category": "fire", "location": "Main St", "severity": "high", "summary": "Fire"}\n```'

        mock_message = MagicMock()
        mock_message.content = [MagicMock(text=fenced)]

        mock_client = AsyncMock()
        mock_client.messages.create = AsyncMock(return_value=mock_message)

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(_make_config(), _make_categories())
            analyzer._client = mock_client
            result = await analyzer.analyze(_make_incident())

        assert result is not None
        assert result["category"] == "fire"

    @pytest.mark.asyncio
    async def test_api_exception_returns_none(self):
        mock_client = AsyncMock()
        mock_client.messages.create = AsyncMock(side_effect=RuntimeError("API down"))

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(_make_config(), _make_categories())
            analyzer._client = mock_client
            result = await analyzer.analyze(_make_incident())

        assert result is None

    @pytest.mark.asyncio
    async def test_get_client_raises_without_api_key(self):
        with patch.dict("os.environ", {}, clear=True):
            analyzer = AiAnalyzer(_make_config(), _make_categories())
            with pytest.raises(RuntimeError, match="ANTHROPIC_API_KEY"):
                await analyzer._get_client()

    @pytest.mark.asyncio
    async def test_rate_limit_resets_after_hour(self):
        """After 3600s the hourly counter should reset."""
        response_json = json.dumps({
            "relevant": True,
            "category": "fire",
            "location": "Main St",
            "severity": "high",
            "summary": "Fire",
        })

        mock_message = MagicMock()
        mock_message.content = [MagicMock(text=response_json)]

        mock_client = AsyncMock()
        mock_client.messages.create = AsyncMock(return_value=mock_message)

        config = _make_config()
        config.max_calls_per_hour = 1

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(config, _make_categories())
            analyzer._client = mock_client

            r1 = await analyzer.analyze(_make_incident())
            r2 = await analyzer.analyze(_make_incident())  # rate limited
            assert r1 is not None
            assert r2 is None

            # Simulate hour passing
            analyzer._hour_start -= 3601
            r3 = await analyzer.analyze(_make_incident())
            assert r3 is not None

    @pytest.mark.asyncio
    async def test_close_cleans_up(self):
        mock_client = AsyncMock()

        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
            analyzer = AiAnalyzer(_make_config(), _make_categories())
            analyzer._client = mock_client
            await analyzer.close()

        mock_client.close.assert_awaited_once()
        assert analyzer._client is None

    @pytest.mark.asyncio
    async def test_close_when_no_client(self):
        analyzer = AiAnalyzer(_make_config(), _make_categories())
        await analyzer.close()  # should not raise

    def test_build_system_prompt_includes_categories(self):
        analyzer = AiAnalyzer(_make_config(), _make_categories())
        prompt = analyzer._build_system_prompt()
        assert "fire" in prompt
        assert "traffic" in prompt
        assert "JSON" in prompt
