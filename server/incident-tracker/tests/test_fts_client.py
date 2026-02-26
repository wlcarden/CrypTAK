"""Tests for the FTS TCP client — connection, send, backoff, SA refresh."""

import asyncio
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.cot.fts_client import FtsClient, _build_sa_cot, _CLIENT_UID


class TestBuildSaCot:
    def test_valid_xml(self):
        xml_str = _build_sa_cot()
        root = ET.fromstring(xml_str)
        assert root.tag == "event"

    def test_event_attributes(self):
        root = ET.fromstring(_build_sa_cot())
        assert root.get("version") == "2.0"
        assert root.get("uid") == _CLIENT_UID
        assert root.get("type") == "a-f-G-U-C"
        assert root.get("how") == "m-g"

    def test_point_at_origin(self):
        root = ET.fromstring(_build_sa_cot())
        point = root.find("point")
        assert point is not None
        assert point.get("lat") == "0"
        assert point.get("lon") == "0"

    def test_callsign(self):
        root = ET.fromstring(_build_sa_cot())
        contact = root.find("detail/contact")
        assert contact is not None
        assert contact.get("callsign") == "IncidentTracker"

    def test_group_element(self):
        root = ET.fromstring(_build_sa_cot())
        group = root.find("detail/__group")
        assert group is not None
        assert group.get("name") == "Cyan"
        assert group.get("role") == "Team Member"

    def test_stale_after_start(self):
        root = ET.fromstring(_build_sa_cot())
        assert root.get("stale") > root.get("start")

    def test_timestamps_are_utc(self):
        root = ET.fromstring(_build_sa_cot())
        for attr in ("time", "start", "stale"):
            assert root.get(attr).endswith("Z")


def _mock_writer():
    """Create a mock asyncio StreamWriter."""
    writer = AsyncMock()
    writer.write = MagicMock()  # write is sync, drain is async
    writer.drain = AsyncMock()
    writer.close = MagicMock()
    writer.wait_closed = AsyncMock()
    return writer


def _mock_reader():
    return AsyncMock()


class TestFtsClientConnect:
    @pytest.mark.asyncio
    async def test_connect_success(self):
        writer = _mock_writer()
        reader = _mock_reader()

        with patch("asyncio.open_connection",
                    new_callable=AsyncMock, return_value=(reader, writer)):
            client = FtsClient("localhost", 8087)
            await client.connect()

        # Should have sent SA CoT on connect
        writer.write.assert_called_once()
        data = writer.write.call_args[0][0]
        assert b"IncidentTracker" in data
        assert data.endswith(b"\n")
        writer.drain.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_connect_retries_on_failure(self):
        """Connection failures should retry with backoff before succeeding."""
        writer = _mock_writer()
        reader = _mock_reader()
        call_count = 0

        async def open_with_failures(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                raise ConnectionRefusedError("Connection refused")
            return reader, writer

        with patch("asyncio.open_connection", side_effect=open_with_failures):
            with patch("asyncio.sleep", new_callable=AsyncMock):
                client = FtsClient("localhost", 8087)
                await client.connect()

        assert call_count == 3

    @pytest.mark.asyncio
    async def test_backoff_increases_on_failure(self):
        """Backoff duration should increase with each failure."""
        sleep_durations = []

        async def capture_sleep(duration):
            sleep_durations.append(duration)

        call_count = 0

        async def open_with_failures(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count < 4:
                raise OSError("Network unreachable")
            return _mock_reader(), _mock_writer()

        with patch("asyncio.open_connection", side_effect=open_with_failures):
            with patch("asyncio.sleep", side_effect=capture_sleep):
                client = FtsClient("localhost", 8087)
                await client.connect()

        assert len(sleep_durations) == 3
        # Exponential: 1.0, 2.0, 4.0
        assert sleep_durations[0] == 1.0
        assert sleep_durations[1] == 2.0
        assert sleep_durations[2] == 4.0

    @pytest.mark.asyncio
    async def test_backoff_resets_after_success(self):
        """Backoff should reset to initial value after successful connect."""
        writer = _mock_writer()

        with patch("asyncio.open_connection",
                    new_callable=AsyncMock, return_value=(_mock_reader(), writer)):
            client = FtsClient("localhost", 8087)
            client._backoff = 32.0  # simulate previous failures
            await client.connect()

        assert client._backoff == 1.0

    @pytest.mark.asyncio
    async def test_backoff_capped_at_max(self):
        """Backoff should not exceed _MAX_BACKOFF (60s)."""
        call_count = 0
        sleep_durations = []

        async def capture_sleep(duration):
            sleep_durations.append(duration)

        async def open_with_failures(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count < 10:
                raise ConnectionRefusedError()
            return _mock_reader(), _mock_writer()

        with patch("asyncio.open_connection", side_effect=open_with_failures):
            with patch("asyncio.sleep", side_effect=capture_sleep):
                client = FtsClient("localhost", 8087)
                await client.connect()

        # All sleep durations should be <= 60
        assert all(d <= 60.0 for d in sleep_durations)
        # The later ones should be capped at 60
        assert sleep_durations[-1] == 60.0


class TestFtsClientSend:
    @pytest.mark.asyncio
    async def test_send_writes_cot_with_newline(self):
        writer = _mock_writer()
        client = FtsClient("localhost", 8087)
        client._writer = writer
        client._reader = _mock_reader()
        client._last_sa = datetime.now(timezone.utc)

        result = await client.send("<event/>")

        assert result is True
        writer.write.assert_called_with(b"<event/>\n")

    @pytest.mark.asyncio
    async def test_send_connects_if_no_writer(self):
        writer = _mock_writer()

        with patch("asyncio.open_connection",
                    new_callable=AsyncMock, return_value=(_mock_reader(), writer)):
            client = FtsClient("localhost", 8087)
            result = await client.send("<event/>")

        assert result is True

    @pytest.mark.asyncio
    async def test_send_reconnects_on_broken_pipe(self):
        """Send should reconnect and retry once on BrokenPipeError."""
        good_writer = _mock_writer()

        client = FtsClient("localhost", 8087)
        client._reader = _mock_reader()
        client._last_sa = datetime.now(timezone.utc)

        # First writer breaks on the CoT write
        broken_writer = _mock_writer()
        broken_writer.write = MagicMock(side_effect=BrokenPipeError("Broken pipe"))
        client._writer = broken_writer

        with patch("asyncio.open_connection",
                    new_callable=AsyncMock,
                    return_value=(_mock_reader(), good_writer)):
            result = await client.send("<event/>")

        assert result is True
        # good_writer received SA (from connect) + retry CoT
        assert good_writer.write.call_count == 2

    @pytest.mark.asyncio
    async def test_send_returns_false_on_double_failure(self):
        """Two consecutive send failures should return False."""
        client = FtsClient("localhost", 8087)
        client._reader = _mock_reader()
        client._last_sa = datetime.now(timezone.utc)

        # First writer raises on CoT write
        bad_writer = _mock_writer()
        bad_writer.write = MagicMock(side_effect=ConnectionResetError())
        client._writer = bad_writer

        # Reconnect succeeds (SA write works), but second CoT write fails.
        # Simulate: SA write succeeds, CoT write fails.
        call_count = 0

        def reconnect_writer_write(data):
            nonlocal call_count
            call_count += 1
            if call_count > 1:  # first call is SA, second is CoT retry
                raise ConnectionResetError()

        reconnect_writer = _mock_writer()
        reconnect_writer.write = MagicMock(side_effect=reconnect_writer_write)

        with patch("asyncio.open_connection",
                    new_callable=AsyncMock,
                    return_value=(_mock_reader(), reconnect_writer)):
            result = await client.send("<event/>")

        assert result is False

    @pytest.mark.asyncio
    async def test_send_refreshes_sa_when_stale(self):
        """Send should re-send SA if the last SA is older than refresh interval."""
        writer = _mock_writer()
        client = FtsClient("localhost", 8087)
        client._writer = writer
        client._reader = _mock_reader()
        client._last_sa = datetime.now(timezone.utc) - timedelta(minutes=5)

        await client.send("<event/>")

        # Two writes: SA refresh + the CoT
        assert writer.write.call_count == 2
        first_write = writer.write.call_args_list[0][0][0]
        assert b"IncidentTracker" in first_write


class TestSaRefresh:
    @pytest.mark.asyncio
    async def test_no_refresh_when_recent(self):
        writer = _mock_writer()
        client = FtsClient("localhost", 8087)
        client._writer = writer
        client._reader = _mock_reader()
        client._last_sa = datetime.now(timezone.utc)

        await client._refresh_sa_if_needed()

        writer.write.assert_not_called()

    @pytest.mark.asyncio
    async def test_refresh_when_stale(self):
        writer = _mock_writer()
        client = FtsClient("localhost", 8087)
        client._writer = writer
        client._reader = _mock_reader()
        client._last_sa = datetime.now(timezone.utc) - timedelta(minutes=5)

        await client._refresh_sa_if_needed()

        writer.write.assert_called_once()
        data = writer.write.call_args[0][0]
        assert b"IncidentTracker" in data

    @pytest.mark.asyncio
    async def test_no_refresh_when_never_sent(self):
        writer = _mock_writer()
        client = FtsClient("localhost", 8087)
        client._writer = writer
        client._reader = _mock_reader()
        client._last_sa = None

        await client._refresh_sa_if_needed()

        writer.write.assert_not_called()


class TestFtsClientClose:
    @pytest.mark.asyncio
    async def test_close_cleans_up(self):
        writer = _mock_writer()
        client = FtsClient("localhost", 8087)
        client._writer = writer
        client._reader = _mock_reader()

        await client.close()

        writer.close.assert_called_once()
        writer.wait_closed.assert_awaited_once()
        assert client._writer is None
        assert client._reader is None

    @pytest.mark.asyncio
    async def test_close_when_not_connected(self):
        client = FtsClient("localhost", 8087)
        await client.close()  # should not raise

    @pytest.mark.asyncio
    async def test_close_writer_suppresses_errors(self):
        writer = _mock_writer()
        writer.close = MagicMock(side_effect=OSError("already closed"))
        client = FtsClient("localhost", 8087)
        client._writer = writer
        client._reader = _mock_reader()

        await client._close_writer()  # should not raise

        assert client._writer is None
