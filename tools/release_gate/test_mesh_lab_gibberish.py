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
    def __init__(self, junk_delivered=False, ordinary_delivered=True, unrelated_error=False,
                 missing_prefix=None, wrong_sender=False):
        self.junk_delivered = junk_delivered
        self.ordinary_delivered = ordinary_delivered
        self.unrelated_error = unrelated_error
        self.missing_prefix = missing_prefix
        self.wrong_sender = wrong_sender

    def cmd(self, command, timeout_ms, **extras):
        assert command == "msg_recv"
        assert extras["include_existing"] is True
        content = extras["contains"]
        ordinary = not content.startswith(("9!qxjv", "vxqjzmp"))
        delivered = self.ordinary_delivered if ordinary else self.junk_delivered
        if self.missing_prefix and content.startswith(self.missing_prefix):
            delivered = False
        if delivered:
            return {"status": "ok", "from": "synthetic-other" if self.wrong_sender else "synthetic-a", "content": content}
        error = "worker failed" if self.unrelated_error else f"timeout after {timeout_ms}ms"
        return {"status": "error", "error": error}


class GibberishScenarioTest(unittest.TestCase):
    def test_accepts_only_absent_junk_and_delivered_ordinary_control(self):
        result = mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver())
        self.assertEqual([False, False, True, True, True, True, True], result["delivered"])
        self.assertEqual(5, result["ordinary_delivered"])

    def test_fails_when_gibberish_reaches_receiver(self):
        with self.assertRaises(mesh_lab.MeshLabError):
            mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver(junk_delivered=True))

    def test_fails_when_ordinary_control_is_missing(self):
        with self.assertRaises(mesh_lab.MeshLabError):
            mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver(ordinary_delivered=False))

    def test_unrelated_receiver_error_cannot_satisfy_negative_assertion(self):
        with self.assertRaisesRegex(mesh_lab.MeshLabError, "observation window"):
            mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver(unrelated_error=True))

    def test_missing_native_script_control_fails_the_scenario(self):
        with self.assertRaises(mesh_lab.MeshLabError):
            mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver(missing_prefix="هذا"))

    def test_control_from_an_unexpected_sender_fails_the_scenario(self):
        with self.assertRaisesRegex(mesh_lab.MeshLabError, "synthetic sender"):
            mesh_lab.scenario_broadcast_gibberish(SyntheticSender(), SyntheticReceiver(wrong_sender=True))


if __name__ == "__main__":
    unittest.main()
