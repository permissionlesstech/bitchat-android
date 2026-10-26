"""Check that the physical scenario cannot report success for unrelated hook failures.

These host-side tests use synthetic fake endpoints and do not validate radio delivery.
"""

import unittest

from tools.release_gate import mesh_lab


class SyntheticSender:
    def cmd_ok(self, command, timeout_ms=60_000, **extras):
        if command == "whoami":
            return {"peer_id": "synthetic-a"}
        if command != "broadcast_msg":
            raise AssertionError("Unexpected synthetic sender command")
        return {"status": "ok"}


class SyntheticReceiver:
    def __init__(self, junk_delivered=False, ordinary_delivered=True, unrelated_error=False):
        self.junk_delivered = junk_delivered
        self.ordinary_delivered = ordinary_delivered
        self.unrelated_error = unrelated_error

    def cmd(self, command, timeout_ms, **extras):
        assert command == "msg_recv"
        assert extras["include_existing"] is True
        content = extras["contains"]
        ordinary = content.startswith("This ordinary synthetic example")
        delivered = self.ordinary_delivered if ordinary else self.junk_delivered
        if delivered:
            return {"status": "ok", "from": "synthetic-a", "content": content}
        error = "worker failed" if self.unrelated_error else f"timeout after {timeout_ms}ms"
        return {"status": "error", "error": error}


class GibberishScenarioTest(unittest.TestCase):
    def test_accepts_only_absent_junk_and_delivered_ordinary_control(self):
        result = mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver())
        self.assertEqual([False, False, True], result["delivered"])

    def test_fails_when_gibberish_reaches_receiver(self):
        with self.assertRaises(mesh_lab.MeshLabError):
            mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver(junk_delivered=True))

    def test_fails_when_ordinary_control_is_missing(self):
        with self.assertRaises(mesh_lab.MeshLabError):
            mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver(ordinary_delivered=False))

    def test_unrelated_receiver_error_cannot_satisfy_negative_assertion(self):
        with self.assertRaisesRegex(mesh_lab.MeshLabError, "observation window"):
            mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver(unrelated_error=True))


if __name__ == "__main__":
    unittest.main()
