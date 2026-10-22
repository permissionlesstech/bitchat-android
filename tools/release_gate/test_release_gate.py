import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from tools.release_gate import android_lab
from tools.release_gate.release_gate import (
    GateError,
    append_trace_event,
    canonical_json_digest,
    create_bundle,
    initialize_run,
    load_json,
    parse_evidence,
    record_result,
    validate_manifest,
    validate_matrix,
    validate_privacy,
    validate_run,
)


COMMIT = "1" * 40
TOOL_DIRECTORY = Path(__file__).parent


def valid_matrix():
    return {
        "schema_version": 1,
        "commit": COMMIT,
        "clients": {
            "android-current": {"version": "2.0.0-rc1", "commit": COMMIT},
            "android-legacy": {"version": "1.9.0"},
            "ios-current": {"version": "2.0.0"},
        },
        "lab_capabilities": ["local-relay", "tor"],
        "devices": [
            {
                "alias": "android-low",
                "platform": "android",
                "model": "model-low",
                "manufacturer_class": "vendor-a",
                "api_level": 28,
                "physical": True,
                "capabilities": ["ble-central", "ble-peripheral"],
            },
            {
                "alias": "android-current",
                "platform": "android",
                "model": "model-current",
                "manufacturer_class": "vendor-b",
                "api_level": 35,
                "physical": True,
                "capabilities": ["ble-central", "ble-peripheral", "wifi-aware"],
            },
            {
                "alias": "android-relay",
                "platform": "android",
                "model": "model-relay",
                "manufacturer_class": "vendor-c",
                "api_level": 33,
                "physical": True,
                "capabilities": ["ble-central", "ble-peripheral"],
            },
            {
                "alias": "android-aware",
                "platform": "android",
                "model": "model-aware",
                "manufacturer_class": "vendor-b",
                "api_level": 35,
                "physical": True,
                "capabilities": ["ble-central", "ble-peripheral", "wifi-aware"],
            },
            {
                "alias": "android-legacy",
                "platform": "android",
                "model": "model-legacy",
                "manufacturer_class": "vendor-a",
                "api_level": 28,
                "physical": True,
                "capabilities": ["ble-central", "ble-peripheral"],
            },
            {
                "alias": "ios-current",
                "platform": "ios",
                "model": "ios-model",
                "manufacturer_class": "apple",
                "physical": True,
                "capabilities": ["ble-central", "ble-peripheral"],
            },
        ],
    }


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.manifest = load_json(TOOL_DIRECTORY / "scenarios.json")

    def test_manifest_covers_every_release_category(self):
        scenarios = validate_manifest(self.manifest)
        self.assertEqual(27, len(scenarios))
        self.assertEqual(
            {
                "transport",
                "android-platform",
                "android-to-android",
                "cross-client",
                "endurance",
            },
            {scenario["category"] for scenario in scenarios.values()},
        )

    def test_documented_matrix_template_is_schema_valid_but_not_runnable(self):
        template = load_json(TOOL_DIRECTORY / "device-matrix.example.json")
        self.assertEqual(6, len(validate_matrix(template, allow_placeholders=True)))
        with self.assertRaises(GateError):
            validate_matrix(template)

    def test_matrix_enforces_distinct_api_manufacturer_and_counterpart_clients(self):
        matrix = valid_matrix()
        self.assertEqual(6, len(validate_matrix(matrix, COMMIT)))

        for device in matrix["devices"]:
            if device["platform"] == "android":
                device["manufacturer_class"] = "one-vendor"
        with self.assertRaisesRegex(GateError, "manufacturer"):
            validate_matrix(matrix, COMMIT)

    def test_privacy_policy_rejects_identifiers_paths_addresses_and_long_ids(self):
        rejected = (
            {"serial": "device-selector"},
            {"note": "/" + "home/operator/result"},
            {"note": "operator@example.test"},
            {"note": "192.0.2.1"},
            {"note": "aa:bb:cc:dd:ee:ff"},
            {"note": "0123456789abcdef"},
        )
        for value in rejected:
            with self.subTest(value=value), self.assertRaises(GateError):
                validate_privacy(value)
        validate_privacy({"commit": COMMIT, "packet-correlation-count": 3})

    def test_initialize_creates_disposable_fixtures_and_pending_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = Path(temporary) / "rc-run"
            results = initialize_run(
                self.manifest,
                valid_matrix(),
                run,
                COMMIT,
                "rc-run",
                started_at="2026-01-01T00:00:00+00:00",
            )
            self.assertTrue(all(
                result["status"] == "pending"
                for result in results["scenario_results"].values()
            ))
            fixtures = load_json(run / "fixtures" / "manifest.json")["fixtures"]
            sizes = {fixture["name"]: fixture["size_bytes"] for fixture in fixtures}
            self.assertEqual(0, sizes["empty.bin"])
            self.assertEqual(50 * 1024 * 1024, sizes["maximum.bin"])
            self.assertEqual(50 * 1024 * 1024 + 1, sizes["oversized.bin"])
            self.assertEqual(
                results["scenario_manifest_sha256"],
                canonical_json_digest(self.manifest),
            )
            summary = validate_run(run, allow_incomplete=True)
            self.assertEqual(27, summary["pending"])
            self.assertFalse(summary["complete"])

    def test_record_requires_structured_evidence_and_rejects_pii(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = Path(temporary) / "rc-run"
            initialize_run(self.manifest, valid_matrix(), run, COMMIT, "rc-run")
            with self.assertRaises(GateError):
                record_result(run, "A2A-001", "pass", {}, None)
            with self.assertRaises(GateError):
                record_result(
                    run,
                    "A2A-001",
                    "pass",
                    {"connection-transitions": "operator@example.test"},
                    None,
                )
            with self.assertRaisesRegex(GateError, "reason code"):
                record_result(run, "A2A-001", "fail", {}, None)
            recorded = record_result(
                run,
                "A2A-001",
                "blocked",
                {},
                "counterpart-unavailable",
                updated_at="2026-01-01T00:00:00+00:00",
            )
            self.assertEqual(
                ["blocked"],
                [
                    item["status"]
                    for item in recorded["scenario_results"]["A2A-001"]["history"]
                ],
            )

    def test_complete_run_requires_all_evidence_traces_and_endurance_bounds(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = Path(temporary) / "rc-run"
            initialize_run(self.manifest, valid_matrix(), run, COMMIT, "rc-run")
            scenarios = validate_manifest(self.manifest)
            for scenario_id, scenario in scenarios.items():
                evidence = {key: 1 for key in scenario["evidence"]}
                if "duration-minutes" in evidence:
                    evidence["duration-minutes"] = 240
                if "cycle-count" in evidence:
                    evidence["cycle-count"] = 50
                record_result(
                    run,
                    scenario_id,
                    "pass",
                    evidence,
                    None,
                    updated_at="2026-01-01T04:00:00+00:00",
                )
                append_trace_event(
                    run,
                    scenario_id,
                    scenario["participants"][0],
                    "scenario-terminal",
                    "pass",
                    None,
                    {"assertion-count": len(evidence)},
                    timestamp="2026-01-01T04:00:00+00:00",
                )
            summary = validate_run(run)
            self.assertTrue(summary["complete"])
            self.assertEqual(27, summary["pass"])
            self.assertEqual(27, summary["trace_events"])

            bundle = Path(temporary) / "release-gate.zip"
            create_bundle(run, bundle)
            with zipfile.ZipFile(bundle) as archive:
                self.assertEqual(
                    {
                        "SHA256SUMS",
                        "fixtures/manifest.json",
                        "manifest.json",
                        "matrix.json",
                        "results.json",
                        "summary.md",
                        "trace.jsonl",
                    },
                    set(archive.namelist()),
                )
            with self.assertRaisesRegex(GateError, "overwrite"):
                create_bundle(run, bundle)

    def test_manifest_tampering_after_initialization_is_detected(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = Path(temporary) / "rc-run"
            initialize_run(self.manifest, valid_matrix(), run, COMMIT, "rc-run")
            manifest = load_json(run / "manifest.json")
            manifest["scenario_version"] = "tampered"
            (run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(GateError, "changed"):
                validate_run(run, allow_incomplete=True)

    def test_trace_accepts_only_aggregate_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = Path(temporary) / "rc-run"
            initialize_run(self.manifest, valid_matrix(), run, COMMIT, "rc-run")
            with self.assertRaisesRegex(GateError, "numeric"):
                append_trace_event(
                    run,
                    "A2A-001",
                    "android-current",
                    "packet",
                    "observed",
                    None,
                    {"raw-packet": "payload"},
                )

    def test_evidence_parser_is_typed_and_privacy_checked(self):
        self.assertEqual(
            {"count": 3, "ratio": 0.5, "clean": True},
            parse_evidence(["count=3", "ratio=0.5", "clean=true"]),
        )
        with self.assertRaises(GateError):
            parse_evidence(["note=10.0.0.1"])

    @mock.patch("tools.release_gate.android_lab.find_adb", return_value="adb")
    def test_adb_device_count_never_returns_selectors(self, _find_adb):
        completed = subprocess.CompletedProcess(
            ["adb", "devices"],
            0,
            "List of devices attached\nselector-one\tdevice\nselector-two\toffline\n",
            "",
        )
        count = android_lab.count_connected_devices(runner=lambda *args, **kwargs: completed)
        self.assertEqual(1, count)

    @mock.patch("tools.release_gate.android_lab.run_adb")
    def test_adb_probe_emits_only_logical_device_metadata(self, run_adb):
        run_adb.side_effect = [
            "35",
            "feature:android.hardware.wifi.aware",
            "Vendor",
            "Model",
        ]
        probe = android_lab.probe_device("ephemeral-selector", "android-current")
        self.assertEqual("android-current", probe["alias"])
        self.assertNotIn("serial", probe)
        self.assertIn("wifi-aware", probe["capabilities"])

    @mock.patch("tools.release_gate.android_lab.run_adb")
    def test_disposable_cleanup_targets_the_real_application_id(self, run_adb):
        run_adb.side_effect = ["", "Success"]

        android_lab.prepare_disposable_device("ephemeral-selector", confirmed=True)

        self.assertEqual(
            [
                mock.call(
                    "ephemeral-selector",
                    ["shell", "am", "force-stop", "com.bitchat.droid"],
                ),
                mock.call(
                    "ephemeral-selector",
                    ["shell", "pm", "clear", "com.bitchat.droid"],
                ),
            ],
            run_adb.call_args_list,
        )

    @mock.patch("tools.release_gate.android_lab.run_adb")
    def test_resource_snapshot_returns_only_aggregate_metrics(self, run_adb):
        run_adb.side_effect = [
            "123",
            "TOTAL 2048",
            "7",
            "11",
            "WakeLock com.bitchat.droid\nWakeLock another.package",
            "level: 73",
        ]
        metrics = android_lab.collect_resource_snapshot("ephemeral-selector")
        self.assertEqual(
            {
                "process-running": True,
                "total-pss-kb": 2048,
                "thread-count": 7,
                "fd-count": 11,
                "app-wakelock-count": 1,
                "battery-level-percent": 73,
            },
            metrics,
        )
        self.assertEqual(
            mock.call(
                "ephemeral-selector",
                ["shell", "pidof", "com.bitchat.droid"],
            ),
            run_adb.call_args_list[0],
        )


if __name__ == "__main__":
    unittest.main()
