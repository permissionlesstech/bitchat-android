"""Deterministic loopback checks for the disposable relay's wire contract."""
import json
import socket
import struct
import unittest
from tools.release_gate.nostr_relay_fixture import LocalRelay


class RelayFixtureTest(unittest.TestCase):
    def test_publish_ack_and_paginated_history(self):
        with LocalRelay() as relay:
            with socket.create_connection(("127.0.0.1", relay.port), timeout=3) as connection:
                connection.sendall(
                    b"GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                    b"Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                    b"Sec-WebSocket-Key: c3ludGhldGljLWZpeHR1cmU=\r\n\r\n"
                )
                stream = connection.makefile("rb")
                self.assertIn(b"101", stream.readline())
                while stream.readline() != b"\r\n":
                    pass

                def send(value):
                    payload = json.dumps(value).encode()
                    size = len(payload)
                    head = bytes([0x81, 0x80 | size]) if size < 126 else b"\x81\xfe" + struct.pack("!H", size)
                    connection.sendall(head + b"\0\0\0\0" + payload)

                def receive():
                    header = stream.read(2)
                    size = header[1] & 127
                    if size == 126:
                        size = struct.unpack("!H", stream.read(2))[0]
                    elif size == 127:
                        size = struct.unpack("!Q", stream.read(8))[0]
                    return json.loads(stream.read(size))

                event = {"id": "synthetic", "kind": 1059, "created_at": 100,
                         "tags": [["p", "recipient"]], "content": "opaque"}
                send(["EVENT", event])
                self.assertEqual(["OK", "synthetic", True, ""], receive())
                send(["REQ", "history", {"kinds": [1059], "#p": ["recipient"], "since": 100, "until": 100, "limit": 1}])
                self.assertEqual(["EVENT", "history", event], receive())
                self.assertEqual(["EOSE", "history"], receive())
                send(["REQ", "other", {"#p": ["different-recipient"]}])
                self.assertEqual(["EOSE", "other"], receive())
                stream.close()


if __name__ == "__main__":
    unittest.main()
