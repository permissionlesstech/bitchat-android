#!/usr/bin/env python3
"""Create, record, validate, and archive the physical release gate.

The host-side CLI is the control channel. It coordinates operators over USB or
local files and never sends control traffic through the mesh under test.
Artifacts intentionally contain logical device aliases and aggregate evidence,
not device identifiers, addresses, peer IDs, or message contents.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = 1
RESULT_STATUSES = {"pending", "pass", "fail", "blocked", "unsupported"}
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
SAFE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
DISALLOWED_KEYS = {
    "serial",
    "serial_number",
    "udid",
    "imei",
    "bluetooth_address",
    "mac_address",
    "ip_address",
    "peer_id",
    "username",
    "user_name",
    "email",
    "account",
    "device_name",
}
HASH_VALUE_KEYS = {
    "commit",
    "sha256",
    "digest",
    "scenario_manifest_sha256",
    "fixture_sha256",
    "vector_manifest_digest",
    "corpus_digest",
}
SENSITIVE_PATTERNS = (
    re.compile(r"(?:^|[\s/])Users/[^/\s]+"),
    re.compile(r"(?:^|[\s/])home/[^/\s]+"),
    re.compile(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b"),
    re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"),
    re.compile(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b"),
    re.compile(r"\b[0-9A-Fa-f]{16,}\b"),
)
MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024


class GateError(ValueError):
    """A release-gate artifact violated an executable contract."""


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GateError(f"could not read JSON {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise GateError(f"{path.name} must contain a JSON object")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_digest(value: dict[str, Any]) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return sha256_bytes(encoded)


def validate_privacy(value: Any, path: tuple[str, ...] = ()) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = str(key).lower()
            if normalized in DISALLOWED_KEYS:
                raise GateError(f"disallowed identifying field: {'.'.join(path + (str(key),))}")
            validate_privacy(child, path + (str(key),))
        return
    if isinstance(value, list):
        for index, child in enumerate(value):
            validate_privacy(child, path + (str(index),))
        return
    if not isinstance(value, str):
        return
    final_key = path[-1].lower().replace("-", "_") if path else ""
    for index, pattern in enumerate(SENSITIVE_PATTERNS):
        if index == len(SENSITIVE_PATTERNS) - 1 and (
            final_key in HASH_VALUE_KEYS
            or final_key.endswith("_digest")
            or final_key.endswith("_sha256")
        ):
            continue
        if pattern.search(value):
            raise GateError(f"potential identifying value at {'.'.join(path)}")


def validate_manifest(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise GateError("unsupported scenario schema_version")
    scenarios = manifest.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        raise GateError("scenario manifest must contain scenarios")
    by_id: dict[str, dict[str, Any]] = {}
    for scenario in scenarios:
        if not isinstance(scenario, dict):
            raise GateError("each scenario must be an object")
        scenario_id = scenario.get("id")
        if not isinstance(scenario_id, str) or not re.fullmatch(
            r"[A-Z0-9]{3}-\d{3}", scenario_id
        ):
            raise GateError(f"invalid scenario id: {scenario_id!r}")
        if scenario_id in by_id:
            raise GateError(f"duplicate scenario id: {scenario_id}")
        if scenario.get("category") not in {
            "transport",
            "android-platform",
            "android-to-android",
            "cross-client",
            "endurance",
        }:
            raise GateError(f"invalid category for {scenario_id}")
        if scenario.get("required") is not True:
            raise GateError(f"release scenario {scenario_id} must be required")
        if not scenario.get("participants") or not scenario.get("evidence"):
            raise GateError(f"scenario {scenario_id} lacks participants or evidence")
        by_id[scenario_id] = scenario
    validate_privacy(manifest)
    return by_id


def validate_matrix(
    matrix: dict[str, Any],
    expected_commit: str | None = None,
    *,
    allow_placeholders: bool = False,
) -> dict[str, dict[str, Any]]:
    if matrix.get("schema_version") != SCHEMA_VERSION:
        raise GateError("unsupported matrix schema_version")
    commit = matrix.get("commit")
    if not isinstance(commit, str) or not COMMIT_RE.fullmatch(commit):
        raise GateError("matrix commit must be a lowercase full Git commit")
    if expected_commit is not None and commit != expected_commit:
        raise GateError("matrix commit does not match the release candidate")
    clients = matrix.get("clients")
    required_clients = {"android-current", "android-legacy", "ios-current"}
    if not isinstance(clients, dict) or not required_clients.issubset(clients):
        raise GateError("matrix must version current Android, legacy Android, and current iOS")
    for client_name in required_clients:
        client = clients.get(client_name)
        if not isinstance(client, dict) or not isinstance(client.get("version"), str):
            raise GateError(f"{client_name} must declare a version")
        if not allow_placeholders and client["version"].startswith("replace-with-"):
            raise GateError(f"{client_name} still contains a template version")
    current_commit = clients["android-current"].get("commit")
    if current_commit != commit and not (allow_placeholders and commit == "0" * 40):
        raise GateError("current Android client commit must match the matrix commit")
    lab_capabilities = matrix.get("lab_capabilities")
    if not isinstance(lab_capabilities, list) or any(
        not isinstance(capability, str) or not SAFE_ID_RE.fullmatch(capability)
        for capability in lab_capabilities
    ):
        raise GateError("matrix must declare logical lab_capabilities")
    devices = matrix.get("devices")
    if not isinstance(devices, list):
        raise GateError("matrix devices must be a list")
    by_alias: dict[str, dict[str, Any]] = {}
    for device in devices:
        if not isinstance(device, dict):
            raise GateError("each device must be an object")
        alias = device.get("alias")
        if not isinstance(alias, str) or not SAFE_ID_RE.fullmatch(alias):
            raise GateError(f"invalid logical device alias: {alias!r}")
        if alias in by_alias:
            raise GateError(f"duplicate device alias: {alias}")
        if device.get("physical") is not True:
            raise GateError(f"{alias} is not a physical device")
        if device.get("platform") not in {"android", "ios"}:
            raise GateError(f"{alias} has an unsupported platform")
        if not isinstance(device.get("capabilities"), list):
            raise GateError(f"{alias} must declare capabilities")
        if not allow_placeholders and (
            not isinstance(device.get("model"), str)
            or device["model"].startswith("replace-with-")
        ):
            raise GateError(f"{alias} still contains template values")
        by_alias[alias] = device
    android = [device for device in devices if device.get("platform") == "android"]
    if len(android) < 3:
        raise GateError("three physical Android devices are required for relay scenarios")
    api_levels = {device.get("api_level") for device in android}
    manufacturers = {device.get("manufacturer_class") for device in android}
    if len(api_levels) < 2 or not all(isinstance(level, int) for level in api_levels):
        raise GateError("Android matrix must cover at least two API levels")
    if len(manufacturers) < 2 or None in manufacturers:
        raise GateError("Android matrix must cover at least two manufacturer classes")
    for device in android:
        capabilities = set(device["capabilities"])
        if not {"ble-central", "ble-peripheral"}.issubset(capabilities):
            raise GateError(f"{device['alias']} lacks required BLE roles")
    if not any("wifi-aware" in device["capabilities"] for device in android):
        raise GateError("at least one Android device must support Wi-Fi Aware")
    if not any(device.get("platform") == "ios" for device in devices):
        raise GateError("a physical iOS device is required")
    validate_privacy(matrix)
    return by_alias


def _fixture_bytes(seed: bytes, size: int) -> bytes:
    output = bytearray()
    counter = 0
    while len(output) < size:
        output.extend(hashlib.sha256(seed + counter.to_bytes(4, "big")).digest())
        counter += 1
    return bytes(output[:size])


def create_fixtures(directory: Path, run_id: str) -> dict[str, Any]:
    fixture_directory = directory / "fixtures"
    fixture_directory.mkdir()
    definitions = (
        ("empty.bin", 0, False),
        ("small.bin", 4 * 1024, False),
        ("lab-résumé-秘密.bin", 256 * 1024, False),
        ("maximum.bin", MAX_FILE_SIZE_BYTES, True),
        ("oversized.bin", MAX_FILE_SIZE_BYTES + 1, True),
    )
    fixtures: list[dict[str, Any]] = []
    for name, size, sparse in definitions:
        path = fixture_directory / name
        if sparse:
            with path.open("wb") as stream:
                stream.truncate(size)
        else:
            path.write_bytes(_fixture_bytes(run_id.encode("utf-8"), size))
        fixtures.append(
            {
                "name": name,
                "size_bytes": size,
                "sparse": sparse,
                "fixture_sha256": sha256_file(path),
            }
        )
    manifest = {"schema_version": SCHEMA_VERSION, "fixtures": fixtures}
    write_json(fixture_directory / "manifest.json", manifest)
    return manifest


def initialize_run(
    manifest: dict[str, Any],
    matrix: dict[str, Any],
    output: Path,
    commit: str,
    run_id: str,
    *,
    started_at: str | None = None,
) -> dict[str, Any]:
    scenarios = validate_manifest(manifest)
    devices = validate_matrix(matrix, commit)
    if not SAFE_ID_RE.fullmatch(run_id):
        raise GateError("run id must be a non-identifying lowercase logical id")
    missing_aliases = {
        participant
        for scenario in scenarios.values()
        for participant in scenario["participants"]
        if participant not in devices
    }
    if missing_aliases:
        raise GateError(f"matrix lacks scenario aliases: {sorted(missing_aliases)}")
    for scenario_id, scenario in scenarios.items():
        available = set(matrix["lab_capabilities"])
        for participant in scenario["participants"]:
            available.update(devices[participant]["capabilities"])
        missing_capabilities = set(scenario["capabilities"]) - available
        if missing_capabilities:
            raise GateError(
                f"{scenario_id} lacks capabilities: {sorted(missing_capabilities)}"
            )
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / "manifest.json", manifest)
    write_json(output / "matrix.json", matrix)
    fixtures = create_fixtures(output, run_id)
    results = {
        "schema_version": SCHEMA_VERSION,
        "run_id": run_id,
        "commit": commit,
        "started_at": started_at or utc_now(),
        "completed_at": None,
        "scenario_manifest_sha256": canonical_json_digest(manifest),
        "fixture_manifest_sha256": canonical_json_digest(fixtures),
        "scenario_results": {
            scenario_id: {
                "status": "pending",
                "participants": scenario["participants"],
                "evidence": {},
                "reason_code": None,
                "updated_at": None,
                "history": [],
            }
            for scenario_id, scenario in scenarios.items()
        },
    }
    write_json(output / "results.json", results)
    (output / "trace.jsonl").write_text("", encoding="utf-8")
    return results


def _parse_scalar(value: str) -> Any:
    lowered = value.lower()
    if lowered in {"true", "false"}:
        return lowered == "true"
    try:
        return int(value)
    except ValueError:
        try:
            return float(value)
        except ValueError:
            return value


def parse_evidence(values: Iterable[str]) -> dict[str, Any]:
    evidence: dict[str, Any] = {}
    for value in values:
        if "=" not in value:
            raise GateError("evidence must use key=value")
        key, raw = value.split("=", 1)
        if not SAFE_ID_RE.fullmatch(key):
            raise GateError(f"invalid evidence key: {key!r}")
        evidence[key] = _parse_scalar(raw)
    validate_privacy(evidence)
    return evidence


def record_result(
    run_directory: Path,
    scenario_id: str,
    status: str,
    evidence: dict[str, Any],
    reason_code: str | None,
    *,
    updated_at: str | None = None,
) -> dict[str, Any]:
    manifest = load_json(run_directory / "manifest.json")
    scenarios = validate_manifest(manifest)
    if scenario_id not in scenarios:
        raise GateError(f"unknown scenario: {scenario_id}")
    if status not in RESULT_STATUSES - {"pending"}:
        raise GateError(f"invalid terminal status: {status}")
    if reason_code is not None and not SAFE_ID_RE.fullmatch(reason_code):
        raise GateError("reason code must be a non-identifying stable code")
    if status == "pass" and not evidence:
        raise GateError("passing a scenario requires structured evidence")
    if status == "pass" and reason_code is not None:
        raise GateError("passing a scenario cannot have a failure reason code")
    if status in {"fail", "blocked", "unsupported"} and reason_code is None:
        raise GateError(f"{status} requires a stable reason code")
    if any(not SAFE_ID_RE.fullmatch(str(key)) for key in evidence):
        raise GateError("evidence keys must be stable logical identifiers")
    validate_privacy(evidence)
    results = load_json(run_directory / "results.json")
    result = results["scenario_results"][scenario_id]
    terminal_update = {
        "status": status,
        "evidence": evidence,
        "reason_code": reason_code,
        "updated_at": updated_at or utc_now(),
    }
    result.setdefault("history", []).append(terminal_update.copy())
    result.update(
        terminal_update
    )
    results["completed_at"] = (
        result["updated_at"]
        if all(
            item.get("status") == "pass"
            for item in results["scenario_results"].values()
        )
        else None
    )
    write_json(run_directory / "results.json", results)
    return results


def append_trace_event(
    run_directory: Path,
    scenario_id: str,
    source_alias: str,
    event: str,
    outcome: str,
    reason_code: str | None,
    metrics: dict[str, Any],
    *,
    timestamp: str | None = None,
) -> dict[str, Any]:
    manifest = load_json(run_directory / "manifest.json")
    matrix = load_json(run_directory / "matrix.json")
    scenarios = validate_manifest(manifest)
    devices = validate_matrix(matrix, allow_placeholders=False)
    if scenario_id not in scenarios:
        raise GateError(f"unknown scenario: {scenario_id}")
    if source_alias not in devices:
        raise GateError(f"unknown source alias: {source_alias}")
    for label, value in (("event", event), ("outcome", outcome)):
        if not SAFE_ID_RE.fullmatch(value):
            raise GateError(f"invalid {label}")
    if reason_code is not None and not SAFE_ID_RE.fullmatch(reason_code):
        raise GateError("invalid reason code")
    if any(not SAFE_ID_RE.fullmatch(str(key)) for key in metrics):
        raise GateError("trace metric keys must be stable logical identifiers")
    if any(
        not isinstance(value, (int, float, bool))
        or isinstance(value, float) and not math.isfinite(value)
        for value in metrics.values()
    ):
        raise GateError("trace metrics must be numeric or boolean aggregates")
    trace = {
        "timestamp": timestamp or utc_now(),
        "scenario_id": scenario_id,
        "source_alias": source_alias,
        "event": event,
        "outcome": outcome,
        "reason_code": reason_code,
        "metrics": metrics,
    }
    validate_privacy(trace)
    with (run_directory / "trace.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(trace, sort_keys=True, ensure_ascii=False) + "\n")
    return trace


def _validate_trace(
    run_directory: Path,
    scenarios: dict[str, dict[str, Any]],
    devices: dict[str, dict[str, Any]],
) -> tuple[int, set[str]]:
    path = run_directory / "trace.jsonl"
    if not path.exists():
        raise GateError("trace.jsonl is missing")
    count = 0
    traced_scenarios: set[str] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        try:
            event = json.loads(line)
        except json.JSONDecodeError as error:
            raise GateError(f"invalid trace line {line_number}") from error
        if not isinstance(event, dict):
            raise GateError(f"trace line {line_number} must be an object")
        if event.get("scenario_id") not in scenarios:
            raise GateError(f"trace line {line_number} has an unknown scenario")
        if event.get("source_alias") not in devices:
            raise GateError(f"trace line {line_number} has an unknown source")
        if set(event) != {
            "timestamp",
            "scenario_id",
            "source_alias",
            "event",
            "outcome",
            "reason_code",
            "metrics",
        }:
            raise GateError(f"trace line {line_number} has unexpected fields")
        if not isinstance(event.get("metrics"), dict) or any(
            not isinstance(value, (int, float, bool))
            for value in event["metrics"].values()
        ):
            raise GateError(f"trace line {line_number} has invalid metrics")
        validate_privacy(event)
        count += 1
        traced_scenarios.add(event["scenario_id"])
    return count, traced_scenarios


def validate_run(run_directory: Path, *, allow_incomplete: bool = False) -> dict[str, Any]:
    manifest = load_json(run_directory / "manifest.json")
    matrix = load_json(run_directory / "matrix.json")
    results = load_json(run_directory / "results.json")
    scenarios = validate_manifest(manifest)
    devices = validate_matrix(matrix, results.get("commit"))
    if results.get("schema_version") != SCHEMA_VERSION:
        raise GateError("unsupported results schema_version")
    if results.get("scenario_manifest_sha256") != canonical_json_digest(manifest):
        raise GateError("scenario manifest changed after run initialization")
    fixture_manifest = load_json(run_directory / "fixtures" / "manifest.json")
    if results.get("fixture_manifest_sha256") != canonical_json_digest(fixture_manifest):
        raise GateError("fixture manifest changed after run initialization")
    actual = results.get("scenario_results")
    if not isinstance(actual, dict) or set(actual) != set(scenarios):
        raise GateError("results must contain exactly every declared scenario")
    validate_privacy(results)
    for scenario_id, scenario in scenarios.items():
        result = actual[scenario_id]
        if result.get("participants") != scenario["participants"]:
            raise GateError(f"{scenario_id} participants changed")
        if any(alias not in devices for alias in result["participants"]):
            raise GateError(f"{scenario_id} references an unknown device")
        available = set(matrix["lab_capabilities"])
        for participant in result["participants"]:
            available.update(devices[participant]["capabilities"])
        missing_capabilities = set(scenario["capabilities"]) - available
        if missing_capabilities:
            raise GateError(
                f"{scenario_id} lacks capabilities: {sorted(missing_capabilities)}"
            )
        status = result.get("status")
        if status not in RESULT_STATUSES:
            raise GateError(f"{scenario_id} has invalid status")
        if not allow_incomplete and status != "pass":
            raise GateError(f"{scenario_id} is not passing: {status}")
        if status == "pass":
            evidence = result.get("evidence")
            missing = set(scenario["evidence"]) - set(evidence or {})
            if missing:
                raise GateError(f"{scenario_id} lacks evidence: {sorted(missing)}")
            if scenario.get("minimum_duration_minutes") is not None and (
                evidence.get("duration-minutes", 0)
                < scenario["minimum_duration_minutes"]
            ):
                raise GateError(f"{scenario_id} did not meet minimum duration")
            if scenario.get("minimum_cycles") is not None and (
                evidence.get("cycle-count", 0) < scenario["minimum_cycles"]
            ):
                raise GateError(f"{scenario_id} did not meet minimum cycles")
    trace_events, traced_scenarios = _validate_trace(run_directory, scenarios, devices)
    if not allow_incomplete:
        missing_traces = set(scenarios) - traced_scenarios
        if missing_traces:
            raise GateError(
                f"passing scenarios lack structured traces: {sorted(missing_traces)}"
            )
        if not results.get("completed_at"):
            raise GateError("complete results must record completed_at")
    summary = {
        status: sum(
            1 for result in actual.values() if result.get("status") == status
        )
        for status in sorted(RESULT_STATUSES)
    }
    summary["trace_events"] = trace_events
    summary["complete"] = all(
        result.get("status") == "pass" for result in actual.values()
    )
    return summary


def render_summary(run_directory: Path) -> str:
    results = load_json(run_directory / "results.json")
    summary = validate_run(run_directory, allow_incomplete=True)
    rows = [
        "# Physical release-gate result",
        "",
        f"- Run: `{results['run_id']}`",
        f"- Commit: `{results['commit']}`",
        f"- Complete: `{str(summary['complete']).lower()}`",
        f"- Trace events: {summary['trace_events']}",
        "",
        "| Status | Count |",
        "|---|---:|",
    ]
    rows.extend(
        f"| {status} | {summary[status]} |" for status in sorted(RESULT_STATUSES)
    )
    return "\n".join(rows) + "\n"


def create_bundle(run_directory: Path, output: Path) -> None:
    validate_run(run_directory)
    if output.exists():
        raise GateError("refusing to overwrite an existing release-gate bundle")
    members = [
        Path("manifest.json"),
        Path("matrix.json"),
        Path("results.json"),
        Path("trace.jsonl"),
        Path("fixtures/manifest.json"),
    ]
    generated = {"summary.md": render_summary(run_directory).encode("utf-8")}
    checksums: list[str] = []
    payloads: dict[str, bytes] = {}
    for member in members:
        payload = (run_directory / member).read_bytes()
        payloads[member.as_posix()] = payload
    payloads.update(generated)
    for name in sorted(payloads):
        checksums.append(f"{sha256_bytes(payloads[name])}  {name}")
    payloads["SHA256SUMS"] = ("\n".join(checksums) + "\n").encode("utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(payloads):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, payloads[name])


def _default_manifest() -> Path:
    return Path(__file__).with_name("scenarios.json")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    manifest = subparsers.add_parser("validate-manifest")
    manifest.add_argument("--manifest", type=Path, default=_default_manifest())

    matrix = subparsers.add_parser("validate-matrix")
    matrix.add_argument("--matrix", type=Path, required=True)
    matrix.add_argument("--commit")
    matrix.add_argument("--allow-template", action="store_true")

    initialize = subparsers.add_parser("init")
    initialize.add_argument("--manifest", type=Path, default=_default_manifest())
    initialize.add_argument("--matrix", type=Path, required=True)
    initialize.add_argument("--output", type=Path, required=True)
    initialize.add_argument("--commit", required=True)
    initialize.add_argument("--run-id", required=True)

    record = subparsers.add_parser("record")
    record.add_argument("--run", type=Path, required=True)
    record.add_argument("--scenario", required=True)
    record.add_argument("--status", choices=sorted(RESULT_STATUSES - {"pending"}), required=True)
    record.add_argument("--evidence", action="append", default=[])
    record.add_argument("--reason-code")

    trace = subparsers.add_parser("trace")
    trace.add_argument("--run", type=Path, required=True)
    trace.add_argument("--scenario", required=True)
    trace.add_argument("--source", required=True)
    trace.add_argument("--event", required=True)
    trace.add_argument("--outcome", required=True)
    trace.add_argument("--reason-code")
    trace.add_argument("--metric", action="append", default=[])

    validate = subparsers.add_parser("validate")
    validate.add_argument("--run", type=Path, required=True)
    validate.add_argument("--allow-incomplete", action="store_true")

    summary = subparsers.add_parser("summary")
    summary.add_argument("--run", type=Path, required=True)

    bundle = subparsers.add_parser("bundle")
    bundle.add_argument("--run", type=Path, required=True)
    bundle.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "validate-manifest":
            scenarios = validate_manifest(load_json(args.manifest))
            print(f"valid scenarios: {len(scenarios)}")
        elif args.command == "validate-matrix":
            devices = validate_matrix(
                load_json(args.matrix),
                args.commit,
                allow_placeholders=args.allow_template,
            )
            print(f"valid devices: {len(devices)}")
        elif args.command == "init":
            initialize_run(
                load_json(args.manifest),
                load_json(args.matrix),
                args.output,
                args.commit,
                args.run_id,
            )
            print(args.output)
        elif args.command == "record":
            record_result(
                args.run,
                args.scenario,
                args.status,
                parse_evidence(args.evidence),
                args.reason_code,
            )
        elif args.command == "trace":
            append_trace_event(
                args.run,
                args.scenario,
                args.source,
                args.event,
                args.outcome,
                args.reason_code,
                parse_evidence(args.metric),
            )
        elif args.command == "validate":
            print(
                json.dumps(
                    validate_run(args.run, allow_incomplete=args.allow_incomplete),
                    sort_keys=True,
                )
            )
        elif args.command == "summary":
            print(render_summary(args.run), end="")
        elif args.command == "bundle":
            create_bundle(args.run, args.output)
            print(args.output)
        return 0
    except GateError as error:
        print(f"release gate error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
