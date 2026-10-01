"""Tests for the mesh-relay FTS client.

Regression coverage for the inbound-drain wedge: FreeTAKServer broadcasts every
CoT event to all registered clients, so a client that only writes will let its
socket receive buffer fill until the TCP receive window closes. FTS's send queue
to that subscriber then stalls permanently -- observed in production as 784 KB
queued, rwnd_limited 100%, TCP backoff 15, and no read for over 15 hours.
"""

from __future__ import annotations

import asyncio

from relay import FtsClient

# One burst is large enough that a few hundred of them exceed any plausible
# socket buffer, so an undrained client is guaranteed to close its window.
BURST = b"<event uid='flood' type='a-f-G-U-C'/>\n" * 512  # ~19 KB
TARGET = 8 * 1024 * 1024  # 8 MB
DRAIN_TIMEOUT = 5.0  # writer.drain() blocking this long means the window closed


class _FakeFts:
    """Minimal stand-in for FTS: reads the client SA, then floods CoT at it."""

    def __init__(self, target: int = TARGET) -> None:
        self.target = target
        self.sent = 0
        self.stalled = False
        self.sa_received = False
        self._sa_seen = asyncio.Event()
        self._server: asyncio.base_events.Server | None = None
        self.port = 0

    async def start(self) -> None:
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        self.port = self._server.sockets[0].getsockname()[1]

    async def _handle(self, reader: asyncio.StreamReader,
                      writer: asyncio.StreamWriter) -> None:
        try:
            await asyncio.wait_for(reader.readline(), timeout=5)
            self.sa_received = True
        except asyncio.TimeoutError:
            return
        finally:
            self._sa_seen.set()

        try:
            while self.sent < self.target:
                writer.write(BURST)
                try:
                    await asyncio.wait_for(writer.drain(), timeout=DRAIN_TIMEOUT)
                except asyncio.TimeoutError:
                    self.stalled = True
                    return
                self.sent += len(BURST)
        except (ConnectionResetError, BrokenPipeError, OSError):
            pass

    async def wait_for_sa(self) -> None:
        await asyncio.wait_for(self._sa_seen.wait(), timeout=5)

    async def wait_until_done(self, timeout: float = 30.0) -> None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while self.sent < self.target and not self.stalled:
            if loop.time() > deadline:
                return
            await asyncio.sleep(0.05)

    def close(self) -> None:
        if self._server is not None:
            self._server.close()


async def _run_flood() -> tuple[_FakeFts, FtsClient, bool]:
    fts = _FakeFts()
    await fts.start()
    client = FtsClient("127.0.0.1", fts.port)
    await client.connect()
    await fts.wait_for_sa()
    await fts.wait_until_done()
    # The client must still be able to publish while being flooded.
    published = await client.send("<event uid='probe' type='a-f-G-U-C'/>")
    return fts, client, published


def test_inbound_stream_is_drained_so_window_stays_open() -> None:
    """A flooding server must never stall: the client drains continuously."""

    async def scenario() -> None:
        fts, client, published = await _run_flood()
        try:
            assert fts.sa_received, "client did not send its SA on connect"
            assert not fts.stalled, (
                "receive window closed: client is not draining FTS's stream"
            )
            assert fts.sent >= TARGET, (
                f"server only pushed {fts.sent} of {TARGET} bytes"
            )
            assert client._rx_bytes >= TARGET * 0.9, (
                f"client drained only {client._rx_bytes} of {fts.sent} bytes"
            )
            assert published, "client could not publish while being flooded"
        finally:
            await client.close()
            fts.close()

    asyncio.run(scenario())


def test_drain_task_runs_during_flood_and_is_cleaned_up_on_close() -> None:
    """The drain task lives for the connection and is cancelled on close."""

    async def scenario() -> None:
        fts, client, _ = await _run_flood()
        try:
            assert client._drain_task is not None, "drain task was never started"
            assert not client._drain_task.done(), "drain task died during flood"
        finally:
            await client.close()
            fts.close()
        assert client._drain_task is None, "drain task not cleaned up on close"
        assert client._reader is None, "reader not released on close"

    asyncio.run(scenario())


def test_reconnect_restarts_the_drain_task() -> None:
    """After a close, connecting again must install a fresh drain task."""

    async def scenario() -> None:
        fts = _FakeFts(target=len(BURST))
        await fts.start()
        client = FtsClient("127.0.0.1", fts.port)
        try:
            await client.connect()
            await fts.wait_for_sa()
            first = client._drain_task
            assert first is not None

            await client.close()
            assert client._drain_task is None

            await client.connect()
            assert client._drain_task is not None, "drain not restarted on reconnect"
            assert client._drain_task is not first, "stale drain task reused"
        finally:
            await client.close()
            fts.close()

    asyncio.run(scenario())


# --- MQTT node attribution ---------------------------------------------------
# Meshtastic MQTT JSON carries "from" (originating node) and "sender" (the
# gateway that uplinked it). Reading "sender" attributed the whole mesh to the
# gateway: GW01 is fixed-position but appeared to jump tens of km, and other
# nodes' battery/uptime collapsed onto it.

from relay import _mqtt_node_id

# Captured from the live broker: a foreign node relayed by GW01.
_REAL_GATEWAY_MSG = {
    "channel": 0,
    "from": 2733326664,           # 0xa2eb4148 -- the originating node
    "hop_start": 7,
    "hops_away": 1,
    "id": 127618520,
    "payload": {"hardware": 31, "id": "!a2eb4148", "longname": "BB-SE",
                "role": 12, "shortname": "BBSE"},
    "rssi": -52,
    "sender": "!087a29a4",        # GW01 -- only the gateway
    "snr": 5.75,
    "type": "nodeinfo",
}


def test_attributes_packet_to_originating_node_not_gateway() -> None:
    assert _mqtt_node_id(_REAL_GATEWAY_MSG) == "a2eb4148"
    # regression: must NOT be the gateway
    assert _mqtt_node_id(_REAL_GATEWAY_MSG) != "087a29a4"


def test_payload_id_agrees_with_decoded_from_field() -> None:
    """The decoded 'from' must match the node id Meshtastic puts in payload."""
    assert "!" + _mqtt_node_id(_REAL_GATEWAY_MSG) == _REAL_GATEWAY_MSG["payload"]["id"]


def test_gateway_own_packet_resolves_to_gateway() -> None:
    """A packet GW01 itself originated still attributes to GW01."""
    assert _mqtt_node_id({"from": 0x087A29A4, "sender": "!087a29a4"}) == "087a29a4"


def test_falls_back_to_sender_only_when_from_absent() -> None:
    assert _mqtt_node_id({"sender": "!087a29a4"}) == "087a29a4"
    assert _mqtt_node_id({"from": None, "sender": "!087a29a4"}) == "087a29a4"
    assert _mqtt_node_id({"from": 0, "sender": "!087a29a4"}) == "087a29a4"


def test_accepts_string_from_in_decimal_or_hex() -> None:
    assert _mqtt_node_id({"from": "2733326664"}) == "a2eb4148"
    assert _mqtt_node_id({"from": "!a2eb4148"}) == "a2eb4148"
    assert _mqtt_node_id({"from": "a2eb4148"}) == "a2eb4148"


def test_malformed_from_does_not_raise() -> None:
    assert _mqtt_node_id({"from": "zzzz", "sender": "!087a29a4"}) == "087a29a4"
    assert _mqtt_node_id({}) == ""


def test_id_is_zero_padded_to_eight_hex_digits() -> None:
    assert _mqtt_node_id({"from": 1}) == "00000001"


def test_peer_close_is_detected_and_next_send_reconnects_without_loss() -> None:
    """FTS closing its side must not swallow the next published event.

    Production shows FTS dropping a client shortly after registration. A write
    into the half-closed socket is accepted locally and lost; the drain task
    now notices EOF and tears the writer down so send() reconnects first.
    """

    async def scenario() -> None:
        received: list[bytes] = []
        connections = 0
        first_closed = asyncio.Event()

        async def fts(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
            nonlocal connections
            connections += 1
            sa = await asyncio.wait_for(reader.readline(), timeout=5)
            received.append(sa)
            if connections == 1:
                # behave like FTS: register the client, then drop it
                writer.close()
                await writer.wait_closed()
                first_closed.set()
                return
            try:
                while True:
                    line = await reader.readline()
                    if not line:
                        return
                    received.append(line)
            except (ConnectionResetError, OSError):
                return

        server = await asyncio.start_server(fts, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        client = FtsClient("127.0.0.1", port)
        try:
            await client.connect()
            await asyncio.wait_for(first_closed.wait(), timeout=5)
            # give the drain task a moment to observe EOF and tear down
            for _ in range(50):
                if client._writer is None:
                    break
                await asyncio.sleep(0.02)
            assert client._writer is None, "writer not torn down after peer EOF"
            assert client._drain_task is None, "drain task not cleared after EOF"

            ok = await client.send("<event uid='after-close' type='a-f-G-U-C'/>")
            assert ok, "send after peer close reported failure"
            for _ in range(50):
                if any(b"after-close" in r for r in received):
                    break
                await asyncio.sleep(0.02)
            assert any(b"after-close" in r for r in received), (
                "event published after FTS-side close was lost"
            )
            assert connections == 2, "client did not reconnect exactly once"
            assert client._drain_task is not None and not client._drain_task.done(), (
                "drain not restarted on the new connection"
            )
        finally:
            await client.close()
            server.close()

    asyncio.run(scenario())


# --- Idle keepalive -----------------------------------------------------------

def test_refresh_sa_after_peer_close_does_not_raise() -> None:
    """After an EOF teardown the writer is None; refresh_sa must be a no-op."""
    from datetime import datetime, timedelta, timezone

    async def scenario() -> None:
        client = FtsClient("127.0.0.1", 1)
        client._last_sa = datetime.now(timezone.utc) - timedelta(minutes=10)
        client._writer = None
        await client.refresh_sa()  # used to raise AttributeError on None.write

    asyncio.run(scenario())


def test_keepalive_reconnects_after_peer_close() -> None:
    """An idle relay must re-register with FTS without waiting for a position."""

    async def scenario() -> None:
        connections = 0
        first_closed = asyncio.Event()

        async def fts(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
            nonlocal connections
            connections += 1
            await asyncio.wait_for(reader.readline(), timeout=5)
            if connections == 1:
                writer.close()
                await writer.wait_closed()
                first_closed.set()
                return
            try:
                while await reader.readline():
                    pass
            except (ConnectionResetError, OSError):
                return

        server = await asyncio.start_server(fts, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        client = FtsClient("127.0.0.1", port)
        try:
            await client.connect()
            await asyncio.wait_for(first_closed.wait(), timeout=5)
            for _ in range(50):
                if client._writer is None:
                    break
                await asyncio.sleep(0.02)
            assert client._writer is None, "writer not torn down after peer EOF"

            await client.keepalive()  # what the main loop does on an idle queue
            assert connections == 2, "keepalive did not reconnect"
            assert client._writer is not None
            assert client._drain_task is not None and not client._drain_task.done()

            await client.keepalive()  # connected + SA fresh: must be a no-op
            assert connections == 2
        finally:
            await client.close()
            server.close()

    asyncio.run(scenario())


# --- nodes.yaml name cache ----------------------------------------------------

def test_resolve_callsign_reads_yaml_once_until_it_changes(tmp_path, monkeypatch) -> None:
    import os, time
    import relay as r
    y = tmp_path / "nodes.yaml"
    y.write_text("nodes:\n  GW01:\n    id: '!087a29a4'\n    longName: CrypTAK-GW01\n  SOL01:\n    id: '!c6eadff0'\n    shortName: CS01\n")
    monkeypatch.setattr(r, "_NODES_YAML", str(y))
    r._node_name_cache = (-1.0, {})

    assert r._resolve_callsign("!087a29a4") == "CrypTAK-GW01"
    assert r._resolve_callsign("c6eadff0") == "CS01"          # shortName fallback, bare id accepted
    assert r._resolve_callsign("!deadbeef") == "!deadbeef"    # unknown -> id as-is
    first = r._node_name_cache
    r._resolve_callsign("!087a29a4")
    assert r._node_name_cache is first, "unchanged file must not be re-parsed"

    y.write_text("nodes:\n  GW01:\n    id: '!087a29a4'\n    longName: Renamed-GW01\n")
    os.utime(y, (time.time() + 5, time.time() + 5))           # guarantee a different mtime
    assert r._resolve_callsign("!087a29a4") == "Renamed-GW01"


def test_resolve_callsign_without_yaml_returns_the_id(monkeypatch) -> None:
    import relay as r
    monkeypatch.setattr(r, "_NODES_YAML", "/nonexistent/nodes.yaml")
    r._node_name_cache = (-1.0, {})
    assert r._resolve_callsign("!087a29a4") == "!087a29a4"
