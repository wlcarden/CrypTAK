"""Tests for halow-bridge service."""

from __future__ import annotations

import asyncio
import xml.etree.ElementTree as ET

import pytest

from bridge import (
    CoTBuffer,
    CoTStreamReader,
    RemoteFtsClient,
    build_sa,
    connect_local_fts,
    event_key,
)


# --- Fixtures ---

def _make_event(uid: str = "test-1", type_: str = "a-f-G", time: str = "2026-03-05T12:00:00.000Z") -> str:
    """Build a minimal valid CoT event XML string."""
    return (
        f'<event version="2.0" uid="{uid}" type="{type_}" '
        f'time="{time}" start="{time}" stale="{time}" how="m-g">'
        f'<point lat="38.8" lon="-77.0" hae="0" ce="9999999" le="9999999"/>'
        f'<detail/>'
        f'</event>'
    )


# --- build_sa ---

class TestBuildSa:
    def test_returns_valid_xml(self):
        sa = build_sa()
        root = ET.fromstring(sa)
        assert root.tag == "event"
        assert root.get("version") == "2.0"

    def test_uses_default_uid(self):
        sa = build_sa()
        root = ET.fromstring(sa)
        assert root.get("uid") == "CrypTAK-HaLowBridge"

    def test_custom_uid_and_callsign(self):
        sa = build_sa(uid="custom-uid", callsign="TestBridge")
        root = ET.fromstring(sa)
        assert root.get("uid") == "custom-uid"
        contact = root.find(".//contact")
        assert contact is not None
        assert contact.get("callsign") == "TestBridge"

    def test_has_required_elements(self):
        sa = build_sa()
        root = ET.fromstring(sa)
        assert root.find("point") is not None
        assert root.find("detail") is not None
        assert root.find(".//contact") is not None
        assert root.find(".//__group") is not None
        assert root.find(".//takv") is not None


# --- event_key ---

class TestEventKey:
    def test_extracts_uid_and_time(self):
        cot = _make_event(uid="abc-123", time="2026-01-01T00:00:00.000Z")
        assert event_key(cot) == "abc-123:2026-01-01T00:00:00.000Z"

    def test_returns_none_for_invalid_xml(self):
        assert event_key("not xml") is None

    def test_returns_none_for_missing_uid(self):
        cot = '<event time="2026-01-01T00:00:00.000Z"><detail/></event>'
        assert event_key(cot) is None

    def test_returns_none_for_empty_string(self):
        assert event_key("") is None


# --- CoTBuffer ---

class TestCoTBuffer:
    def test_add_and_flush(self):
        buf = CoTBuffer(maxlen=100)
        e1 = _make_event(uid="a")
        e2 = _make_event(uid="b")
        buf.add(e1)
        buf.add(e2)
        assert buf.size == 2
        events = buf.flush()
        assert len(events) == 2
        assert buf.size == 0

    def test_flush_deduplicates_sent_events(self):
        buf = CoTBuffer(maxlen=100)
        e1 = _make_event(uid="a", time="2026-01-01T00:00:00.000Z")
        buf.mark_sent(e1)
        buf.add(e1)
        events = buf.flush()
        assert len(events) == 0

    def test_flush_allows_different_events(self):
        buf = CoTBuffer(maxlen=100)
        e1 = _make_event(uid="a", time="2026-01-01T00:00:00.000Z")
        e2 = _make_event(uid="b", time="2026-01-01T00:00:00.000Z")
        buf.mark_sent(e1)
        buf.add(e2)
        events = buf.flush()
        assert len(events) == 1

    def test_flush_allows_same_uid_different_time(self):
        buf = CoTBuffer(maxlen=100)
        e1 = _make_event(uid="a", time="2026-01-01T00:00:00.000Z")
        e2 = _make_event(uid="a", time="2026-01-01T00:01:00.000Z")
        buf.mark_sent(e1)
        buf.add(e2)
        events = buf.flush()
        assert len(events) == 1

    def test_ring_buffer_drops_oldest_when_full(self):
        buf = CoTBuffer(maxlen=3)
        for i in range(5):
            buf.add(_make_event(uid=f"e{i}"))
        assert buf.size == 3
        events = buf.flush()
        uids = [ET.fromstring(e).get("uid") for e in events]
        assert uids == ["e2", "e3", "e4"]

    def test_sent_tracking_evicts_oldest_keys(self):
        buf = CoTBuffer(maxlen=2)
        # maxlen=2 → sent_keys maxlen = 4
        for i in range(6):
            buf.mark_sent(_make_event(uid=f"s{i}", time=f"2026-01-01T00:0{i}:00.000Z"))
        # Oldest keys should be evicted, newest retained
        e_old = _make_event(uid="s0", time="2026-01-01T00:00:00.000Z")
        e_new = _make_event(uid="s5", time="2026-01-01T00:05:00.000Z")
        buf.add(e_old)
        buf.add(e_new)
        events = buf.flush()
        # s0 was evicted from sent set, so it should appear
        uids = [ET.fromstring(e).get("uid") for e in events]
        assert "s0" in uids
        # s5 is still in sent set, so it should be filtered
        assert "s5" not in uids

    def test_empty_flush(self):
        buf = CoTBuffer()
        assert buf.flush() == []
        assert buf.size == 0

    def test_mark_sent_idempotent(self):
        buf = CoTBuffer(maxlen=100)
        e = _make_event(uid="dup")
        buf.mark_sent(e)
        buf.mark_sent(e)
        buf.mark_sent(e)
        # Should not grow the deque with duplicates
        assert len(buf._sent_keys) == 1


# --- CoTStreamReader ---

class TestCoTStreamReader:
    @pytest.mark.asyncio
    async def test_reads_single_event(self):
        cot = _make_event(uid="stream-1")
        data = (cot + "\n").encode("utf-8")
        reader = asyncio.StreamReader()
        reader.feed_data(data)
        reader.feed_eof()
        stream = CoTStreamReader(reader)
        event = await stream.read_event()
        assert event is not None
        assert "stream-1" in event

    @pytest.mark.asyncio
    async def test_reads_multiple_events(self):
        e1 = _make_event(uid="m1")
        e2 = _make_event(uid="m2")
        data = (e1 + "\n" + e2 + "\n").encode("utf-8")
        reader = asyncio.StreamReader()
        reader.feed_data(data)
        reader.feed_eof()
        stream = CoTStreamReader(reader)
        ev1 = await stream.read_event()
        ev2 = await stream.read_event()
        assert "m1" in ev1
        assert "m2" in ev2

    @pytest.mark.asyncio
    async def test_handles_split_event(self):
        """Event split across two TCP frames."""
        cot = _make_event(uid="split-1")
        encoded = cot.encode("utf-8")
        mid = len(encoded) // 2

        reader = asyncio.StreamReader()
        reader.feed_data(encoded[:mid])
        # Schedule the second half after a brief delay
        loop = asyncio.get_event_loop()
        loop.call_later(0.01, reader.feed_data, encoded[mid:])
        loop.call_later(0.02, reader.feed_eof)

        stream = CoTStreamReader(reader)
        event = await stream.read_event()
        assert event is not None
        assert "split-1" in event

    @pytest.mark.asyncio
    async def test_returns_none_on_eof(self):
        reader = asyncio.StreamReader()
        reader.feed_eof()
        stream = CoTStreamReader(reader)
        event = await stream.read_event()
        assert event is None

    @pytest.mark.asyncio
    async def test_skips_garbage_before_event(self):
        cot = _make_event(uid="after-garbage")
        data = b"some junk data\n" + cot.encode("utf-8")
        reader = asyncio.StreamReader()
        reader.feed_data(data)
        reader.feed_eof()
        stream = CoTStreamReader(reader)
        event = await stream.read_event()
        assert event is not None
        assert "after-garbage" in event

    @pytest.mark.asyncio
    async def test_events_without_newline_delimiter(self):
        """FTS may send events back-to-back without newlines."""
        e1 = _make_event(uid="no-nl-1")
        e2 = _make_event(uid="no-nl-2")
        data = (e1 + e2).encode("utf-8")
        reader = asyncio.StreamReader()
        reader.feed_data(data)
        reader.feed_eof()
        stream = CoTStreamReader(reader)
        ev1 = await stream.read_event()
        ev2 = await stream.read_event()
        assert "no-nl-1" in ev1
        assert "no-nl-2" in ev2


# --- RemoteFtsClient ---

class TestRemoteFtsClient:
    @pytest.mark.asyncio
    async def test_not_connected_initially(self):
        client = RemoteFtsClient("127.0.0.1", 99999)
        assert not client.connected

    @pytest.mark.asyncio
    async def test_send_returns_false_when_not_connected(self):
        client = RemoteFtsClient("127.0.0.1", 99999)
        result = await client.send(_make_event())
        assert result is False

    @pytest.mark.asyncio
    async def test_try_connect_fails_on_bad_host(self):
        client = RemoteFtsClient("127.0.0.1", 1)
        result = await client.try_connect()
        assert result is False
        assert not client.connected

    @pytest.mark.asyncio
    async def test_connect_and_send(self):
        """Integration test: start a TCP server, connect client, send event."""
        received = []

        async def handler(reader, writer):
            data = await reader.read(8192)
            received.append(data.decode("utf-8"))
            writer.close()
            await writer.wait_closed()

        server = await asyncio.start_server(handler, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]

        client = RemoteFtsClient("127.0.0.1", port)
        ok = await client.try_connect()
        assert ok
        assert client.connected

        cot = _make_event(uid="send-test")
        result = await client.send(cot)
        assert result is True
        await client.close()
        server.close()
        await server.wait_closed()

        # Server should have received SA + event
        assert len(received) > 0
        assert "HaLowBridge" in received[0]


# --- connect_local_fts ---

class TestConnectLocalFts:
    @pytest.mark.asyncio
    async def test_connects_and_sends_sa(self):
        received = []

        async def handler(reader, writer):
            data = await reader.read(8192)
            received.append(data.decode("utf-8"))
            writer.close()
            await writer.wait_closed()

        server = await asyncio.start_server(handler, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]

        stream, writer = await connect_local_fts("127.0.0.1", port)
        assert stream is not None
        writer.close()
        await writer.wait_closed()
        server.close()
        await server.wait_closed()

        assert len(received) > 0
        assert "HaLowBridge-RX" in received[0]


# --- Integration: buffer → flush cycle ---

class TestBufferFlushCycle:
    def test_buffer_then_flush_preserves_order(self):
        buf = CoTBuffer(maxlen=100)
        events_in = [_make_event(uid=f"ord-{i}") for i in range(10)]
        for e in events_in:
            buf.add(e)
        events_out = buf.flush()
        uids_in = [ET.fromstring(e).get("uid") for e in events_in]
        uids_out = [ET.fromstring(e).get("uid") for e in events_out]
        assert uids_in == uids_out

    def test_partial_flush_rebuffer(self):
        """Simulate flush interrupted by send failure — re-buffer remaining."""
        buf = CoTBuffer(maxlen=100)
        for i in range(5):
            buf.add(_make_event(uid=f"pf-{i}"))

        events = buf.flush()
        assert len(events) == 5
        assert buf.size == 0

        # Simulate: first 2 sent successfully, rest need re-buffering
        for e in events[2:]:
            buf.add(e)
        assert buf.size == 3
        remaining = buf.flush()
        uids = [ET.fromstring(e).get("uid") for e in remaining]
        assert uids == ["pf-2", "pf-3", "pf-4"]

    @pytest.mark.asyncio
    async def test_end_to_end_forward(self):
        """Local FTS sends events → bridge reads → forwards to remote FTS."""
        forwarded = []

        async def remote_handler(reader, writer):
            while True:
                data = await reader.read(8192)
                if not data:
                    break
                forwarded.append(data.decode("utf-8"))
            writer.close()
            await writer.wait_closed()

        remote_server = await asyncio.start_server(
            remote_handler, "127.0.0.1", 0,
        )
        remote_port = remote_server.sockets[0].getsockname()[1]

        # Build a local FTS that sends some events
        e1 = _make_event(uid="e2e-1")
        e2 = _make_event(uid="e2e-2")

        async def local_handler(reader, writer):
            # Read client SA first
            await reader.readline()
            # Send events
            writer.write((e1 + "\n").encode("utf-8"))
            writer.write((e2 + "\n").encode("utf-8"))
            await writer.drain()
            # Keep connection open briefly then close
            await asyncio.sleep(0.1)
            writer.close()
            await writer.wait_closed()

        local_server = await asyncio.start_server(
            local_handler, "127.0.0.1", 0,
        )
        local_port = local_server.sockets[0].getsockname()[1]

        # Connect to local, read events, forward to remote
        stream, local_writer = await connect_local_fts("127.0.0.1", local_port)
        remote = RemoteFtsClient("127.0.0.1", remote_port)
        await remote.try_connect()

        for _ in range(2):
            cot = await asyncio.wait_for(stream.read_event(), timeout=2.0)
            if cot:
                await remote.send(cot)

        await remote.close()
        local_writer.close()
        try:
            await local_writer.wait_closed()
        except Exception:
            pass
        local_server.close()
        await local_server.wait_closed()
        remote_server.close()
        await remote_server.wait_closed()

        combined = "".join(forwarded)
        assert "e2e-1" in combined
        assert "e2e-2" in combined
