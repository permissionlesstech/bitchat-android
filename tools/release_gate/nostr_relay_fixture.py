"""Loopback-only, in-memory WebSocket relay for disposable Mesh Lab fixtures.

No external network, authentication, logging, or persistence. This is a test
fixture, not a production relay. Payloads remain opaque to the fixture.
"""
from __future__ import annotations

import base64
import hashlib
import json
import socket
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def matches(event: dict, query: dict) -> bool:
    return (
        (not query.get("kinds") or event.get("kind") in query["kinds"])
        and event.get("created_at", 0) >= query.get("since", 0)
        and event.get("created_at", 0) <= query.get("until", 2**63 - 1)
        and (not query.get("#p") or any(
            len(tag) >= 2 and tag[0] == "p" and tag[1] in query["#p"]
            for tag in event.get("tags", [])
        ))
    )


class LocalRelay:
    def __init__(self):
        self.events: dict[str, dict] = {}
        self.clients: set[RelayConnection] = set()
        self.lock = threading.Lock()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), RelayConnection)
        self.server.fixture = self
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def port(self):
        return self.server.server_port

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        with self.lock:
            clients = list(self.clients)
        for client in clients:
            try:
                client.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class RelayConnection(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def do_GET(self):
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            self.send_error(400)
            return
        accept = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
        ).digest()).decode("ascii")
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.send_lock = threading.Lock()
        self.queries: dict[str, list[dict]] = {}
        fixture = self.server.fixture
        with fixture.lock:
            fixture.clients.add(self)
        try:
            while True:
                header = self.rfile.read(2)
                if len(header) != 2:
                    return
                opcode, masked, size = header[0] & 15, header[1] & 128, header[1] & 127
                if size == 126:
                    size = struct.unpack("!H", self.rfile.read(2))[0]
                elif size == 127:
                    size = struct.unpack("!Q", self.rfile.read(8))[0]
                if size > 2_000_000:
                    return
                mask = self.rfile.read(4) if masked else b""
                payload = self.rfile.read(size)
                if masked:
                    payload = bytes(v ^ mask[i % 4] for i, v in enumerate(payload))
                if opcode == 8:
                    self.frame(payload, opcode=8)
                    return
                if opcode == 9:
                    self.frame(payload, opcode=10)
                elif opcode == 1:
                    self.dispatch(json.loads(payload))
        except (OSError, ValueError, IndexError, KeyError, struct.error):
            pass
        finally:
            with fixture.lock:
                fixture.clients.discard(self)
            self.close_connection = True

    def frame(self, payload: bytes, opcode=1):
        size = len(payload)
        header = bytes([128 | opcode]) + (
            bytes([size]) if size < 126 else
            b"\x7e" + struct.pack("!H", size) if size < 65536 else
            b"\x7f" + struct.pack("!Q", size)
        )
        with self.send_lock:
            self.wfile.write(header + payload)
            self.wfile.flush()

    def send(self, value):
        self.frame(json.dumps(value, separators=(",", ":")).encode())

    def dispatch(self, value):
        fixture = self.server.fixture
        if value[0] == "EVENT":
            event = value[1]
            with fixture.lock:
                fixture.events[event["id"]] = event
                deliveries = [(client, sub) for client in fixture.clients
                              for sub, queries in client.queries.items()
                              if any(matches(event, q) for q in queries)]
            self.send(["OK", event["id"], True, ""])
            for client, sub in deliveries:
                try:
                    client.send(["EVENT", sub, event])
                except OSError:
                    pass
        elif value[0] == "REQ":
            sub, queries = value[1], value[2:]
            with fixture.lock:
                self.queries[sub] = queries
                selected = {}
                for query in queries:
                    events = sorted((e for e in fixture.events.values() if matches(e, query)),
                                    key=lambda e: (e["created_at"], e["id"]), reverse=True)
                    selected.update((e["id"], e) for e in events[:query.get("limit", 500)])
            for event in selected.values():
                self.send(["EVENT", sub, event])
            self.send(["EOSE", sub])
        elif value[0] == "CLOSE":
            with fixture.lock:
                self.queries.pop(value[1], None)
