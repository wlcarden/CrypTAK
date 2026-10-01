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


# --- Inbound drain (real sockets) -------------------------------------------
# FTS broadcasts every CoT event to every registered client. A client that
# never reads lets its receive window close, and FTS's send queue to it stalls
# permanently (seen in production as 920 KB notsent, rwnd_limited 100%).

_BURST = b"<event uid='flood' type='a-f-G-U-C'/>\n" * 512  # ~19 KB
_TARGET = 4 * 1024 * 1024  # 4 MB, well beyond any socket buffer


class _FloodingFts:
    def __init__(self, target=_TARGET, drop_first=False):
        self.target = target
        self.drop_first = drop_first
        self.sent = 0
        self.stalled = False
        self.connections = 0
        self.received: list[bytes] = []
        self.first_closed = asyncio.Event()
        self.sa_seen = asyncio.Event()
        self._server = None
        self.port = 0

    async def start(self):
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        self.port = self._server.sockets[0].getsockname()[1]

    async def _handle(self, reader, writer):
        self.connections += 1
        try:
            await self._serve(reader, writer)
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except (ConnectionResetError, BrokenPipeError, OSError):
                pass

    async def _serve(self, reader, writer):
        try:
            sa = await asyncio.wait_for(reader.readline(), timeout=5)
            self.received.append(sa)
        except asyncio.TimeoutError:
            return
        finally:
            self.sa_seen.set()
        if self.drop_first and self.connections == 1:
            writer.close()
            await writer.wait_closed()
            self.first_closed.set()
            return
        try:
            if self.target:
                while self.sent < self.target:
                    writer.write(_BURST)
                    try:
                        await asyncio.wait_for(writer.drain(), timeout=5)
                    except asyncio.TimeoutError:
                        self.stalled = True
                        return
                    self.sent += len(_BURST)
            while True:
                line = await reader.readline()
                if not line:
                    return
                self.received.append(line)
        except (ConnectionResetError, BrokenPipeError, OSError):
            return

    async def wait_done(self, timeout=30.0):
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while self.sent < self.target and not self.stalled and loop.time() < deadline:
            await asyncio.sleep(0.05)

    async def close(self):
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()


class TestInboundDrain:
    async def test_flood_is_drained_and_window_stays_open(self):
        fts = _FloodingFts()
        await fts.start()
        client = FtsClient("127.0.0.1", fts.port)
        try:
            await client.connect()
            await asyncio.wait_for(fts.sa_seen.wait(), timeout=5)
            await fts.wait_done()
            ok = await client.send("<event uid='probe' type='a-f-G-U-C'/>")
            assert not fts.stalled, "receive window closed: client is not draining"
            assert fts.sent >= _TARGET
            assert client._rx_bytes >= _TARGET * 0.9
            assert ok, "could not publish while being flooded"
            assert client._drain_task is not None and not client._drain_task.done()
        finally:
            await client.close()
            await fts.close()
        assert client._drain_task is None, "drain task not cleaned up on close"

    async def test_peer_close_tears_down_writer_and_send_reconnects(self):
        fts = _FloodingFts(target=0, drop_first=True)
        await fts.start()
        client = FtsClient("127.0.0.1", fts.port)
        try:
            await client.connect()
            await asyncio.wait_for(fts.first_closed.wait(), timeout=5)
            for _ in range(50):
                if client._writer is None:
                    break
                await asyncio.sleep(0.02)
            assert client._writer is None, "writer not torn down after peer EOF"
            assert client._last_sa is None

            ok = await client.send("<event uid='after-close' type='a-f-G-U-C'/>")
            assert ok
            for _ in range(50):
                if any(b"after-close" in r for r in fts.received):
                    break
                await asyncio.sleep(0.02)
            assert any(b"after-close" in r for r in fts.received), (
                "event published after FTS-side close was lost"
            )
            assert fts.connections == 2
        finally:
            await client.close()
            await fts.close()

    async def test_mock_reader_does_not_spin(self):
        """A non-bytes read (test double) must end the drain, not loop forever."""
        writer = _mock_writer()
        reader = _mock_reader()
        with patch("asyncio.open_connection",
                   new_callable=AsyncMock, return_value=(reader, writer)):
            client = FtsClient("localhost", 8087)
            await client.connect()
            await asyncio.sleep(0)  # let the drain task run once
        assert client._drain_task is not None and client._drain_task.done()
        assert client._writer is writer, "mock reader must not trigger teardown"


class TestConnectSettle:
    async def test_first_event_after_connect_gets_its_own_read(self, monkeypatch):
        """FTS discards anything sharing its registration read; keep the SA alone."""
        import time
        from src.cot import fts_client as mod
        monkeypatch.setattr(mod, "_CONNECT_SETTLE_SECS", 0.2)
        reads: list[bytes] = []

        async def fts(reader, writer):
            try:
                while True:
                    data = await reader.read(65536)
                    if not data:
                        return
                    reads.append(data)
            except (ConnectionResetError, OSError):
                return
            finally:
                writer.close()                           # 3.12: wait_closed() waits for this

        server = await asyncio.start_server(fts, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        client = FtsClient("127.0.0.1", port)
        try:
            await client.connect()                       # returns immediately
            t0 = time.monotonic()
            assert await client.send("<event uid='first' type='a-f-G-U-C'/>")
            assert time.monotonic() - t0 >= 0.19         # the wait happens on the first send
            await asyncio.sleep(0.1)
            assert len(reads) >= 2 and b"first" not in reads[0]
            t1 = time.monotonic()
            assert await client.send("<event uid='second' type='a-f-G-U-C'/>")
            assert time.monotonic() - t1 < 0.1           # later sends are not delayed
        finally:
            await client.close()
            server.close()
            await server.wait_closed()
