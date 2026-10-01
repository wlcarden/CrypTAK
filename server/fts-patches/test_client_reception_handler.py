"""Tests for ClientReceptionHandler_patched.py (FTS 2.2.1 CoT framing fix).

The patched module is loaded inside a fake package with the FreeTAKServer
imports stubbed, so these run without an FTS install. The burst mirrors what
ATAK sends on connect: an <?xml?>-prefixed self-SA, then the persisted
markers, delivered in TCP segments of the phone's 1228-byte MSS.
"""

from __future__ import annotations

import importlib.util
import logging
import socket
import sys
import types
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

HERE = Path(__file__).parent
MSS = 1228


def _stub(name: str, **attrs):
    mod = types.ModuleType(name)
    for k, v in attrs.items():
        setattr(mod, k, v)
    sys.modules[name] = mod
    return mod


@pytest.fixture()
def handler_module():
    class _Config:
        DataReceptionBuffer = 1024
        MaxReceptionTime = 0.05   # upstream spins recv() until this elapses after EOF
        instance = classmethod(lambda cls: cls())

    class _Logger:
        def __init__(self, *a, **k): ...
        def getLogger(self): return logging.getLogger("test-fts")

    class _RawCoT:
        pass

    pkg_fts = _stub("FreeTAKServer"); pkg_fts.__path__ = []
    _stub("FreeTAKServer.core"); _stub("FreeTAKServer.core.configuration")
    _stub("FreeTAKServer.core.configuration.MainConfig", MainConfig=_Config)
    _stub("FreeTAKServer.core.configuration.CreateLoggerController", CreateLoggerController=_Logger)
    _stub("FreeTAKServer.core.configuration.LoggingConstants", LoggingConstants=lambda **k: object())
    _stub("FreeTAKServer.core.configuration.ClientReceptionLoggingConstants",
          ClientReceptionLoggingConstants=lambda: types.SimpleNamespace(
              CLIENTRECEPTIONHANDLERSTARTUPERROR="", CLIENTRECEPTIONHANDLERMONITORFORDATAERRORD="",
              CLIENTRECEPTIONHANDLERRETURNRECEIVEDDATAERROR=""))
    _stub("FreeTAKServer.model"); _stub("FreeTAKServer.model.RawCoT", RawCoT=_RawCoT)
    _stub("defusedxml", ElementTree=ET); _stub("defusedxml.ElementTree", **{k: getattr(ET, k) for k in ("fromstring", "tostring")})
    # the module's relative import: from ..configuration.tcp_cot_service_constants import DATA_RECEPTION_BUFFER_SIZE
    svc = _stub("fts_svc"); svc.__path__ = []
    ctl = _stub("fts_svc.controllers"); ctl.__path__ = []
    cfg = _stub("fts_svc.configuration"); cfg.__path__ = []
    _stub("fts_svc.configuration.tcp_cot_service_constants", DATA_RECEPTION_BUFFER_SIZE=1024)

    spec = importlib.util.spec_from_file_location(
        "fts_svc.controllers.ClientReceptionHandler", HERE / "ClientReceptionHandler_patched.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    mod._PARTIAL.clear()
    yield mod
    mod._PARTIAL.clear()


class FakeSocket:
    """A byte stream: recv() returns up to n bytes, raises timeout when drained,
    and returns b"" forever once the peer has closed (like a real socket)."""

    def __init__(self):
        self.buf = bytearray()
        self.pos = 0
        self.eof = False

    def settimeout(self, _): ...

    def push(self, *segs: bytes):
        for seg in segs:
            if seg == b"":
                self.eof = True
            else:
                self.buf += seg

    def recv(self, n: int) -> bytes:
        if self.pos >= len(self.buf):
            if self.eof:
                return b""
            raise socket.timeout()
        chunk = bytes(self.buf[self.pos:self.pos + n])   # honour BUFF_SIZE like a real socket
        self.pos += len(chunk)
        if self.pos >= len(self.buf):
            self.buf, self.pos = bytearray(), 0
        return chunk


PROLOG = b'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'


def event(uid: str, callsign: str = "UNIT", pad: int = 700) -> bytes:
    body = (f'<event version="2.0" uid="{uid}" type="a-f-G-U-C" time="2026-10-01T00:00:00.000Z" '
            f'start="2026-10-01T00:00:00.000Z" stale="2026-10-01T00:10:00.000Z" how="h-g-i-g-o">'
            f'<point lat="38.8" lon="-77.3" hae="60" ce="9999999" le="9999999"/>'
            f'<detail><contact callsign="{callsign}"/><remarks>{"x" * pad}</remarks></detail></event>\n')
    return body.encode("utf-8")


def segments_of(stream: bytes, size: int = MSS) -> list[bytes]:
    return [stream[i:i + size] for i in range(0, len(stream), size)]


def run_polls(mod, sock: FakeSocket, polls: int) -> list:
    """Drive monitorForData the way the service does: a fresh handler per poll."""
    out = []
    for _ in range(polls):
        h = mod.ClientReceptionHandler()
        h.clientInformationArray = {"tak-01": (sock, None)}
        h.monitorForData(queue=None)
        out.extend(h.dataPipe)
    return out


def uids(pipe) -> list:
    return [ET.fromstring(r.xmlString).get("uid") for r in pipe if r.xmlString != b""]


def disconnects(pipe) -> int:
    return sum(1 for r in pipe if r.xmlString == b"")


def test_burst_split_by_mss_is_reassembled_without_disconnect(handler_module):
    mod = handler_module
    burst = PROLOG + event("M1", "Skinwalker") + PROLOG + event("M2", "Four Chuds") + PROLOG + event("M3", "N.30")
    segs = segments_of(burst)
    assert len(segs) >= 3, "burst must cross segment boundaries to exercise the fix"
    sock = FakeSocket()
    sock.push(segs[0]); pipe = run_polls(mod, sock, 1)          # first segment: ends mid-event
    sock.push(*segs[1:]); pipe += run_polls(mod, sock, 2)       # the rest, over later polls
    assert uids(pipe) == ["M1", "M2", "M3"]
    assert disconnects(pipe) == 0
    assert mod._PARTIAL == {}


def test_whole_burst_in_one_read_delivers_every_event(handler_module):
    mod = handler_module
    sock = FakeSocket()
    sock.push(PROLOG + event("M1") + PROLOG + event("M2") + PROLOG + event("M3"))
    pipe = run_polls(mod, sock, 1)
    assert uids(pipe) == ["M1", "M2", "M3"]
    assert disconnects(pipe) == 0


def test_multibyte_character_split_across_reads_survives(handler_module):
    mod = handler_module
    ev = PROLOG + event("M1", "Équipe Bêta")
    cut = ev.index("É".encode("utf-8")) + 1                     # inside the 2-byte sequence
    sock = FakeSocket()
    sock.push(ev[:cut]); pipe = run_polls(mod, sock, 1)
    assert pipe == [] and mod._PARTIAL["tak-01"] == ev[:cut]    # held, not decoded, not dropped
    sock.push(ev[cut:]); pipe += run_polls(mod, sock, 1)
    assert uids(pipe) == ["M1"]
    assert ET.fromstring(pipe[0].xmlString).find("detail/contact").get("callsign") == "Équipe Bêta"


def test_client_that_never_completes_an_event_is_disconnected(handler_module):
    mod = handler_module
    sock = FakeSocket()
    sock.push(b"<event " + b"a" * (mod.MAX_PARTIAL_BYTES + 1))
    pipe = run_polls(mod, sock, 1)
    assert disconnects(pipe) == 1 and uids(pipe) == []
    assert "tak-01" not in mod._PARTIAL


def test_peer_close_clears_carried_bytes(handler_module):
    mod = handler_module
    sock = FakeSocket()
    sock.push(PROLOG + event("M1")[:300])                        # partial, then EOF
    sock.push(b"")
    pipe = run_polls(mod, sock, 2)
    assert disconnects(pipe) == 1
    assert "tak-01" not in mod._PARTIAL


def test_prune_forgets_clients_no_longer_connected(handler_module):
    mod = handler_module
    mod._PARTIAL["ghost"] = b"<event "
    run_polls(mod, FakeSocket(), 1)
    assert "ghost" not in mod._PARTIAL
