#!/usr/bin/env python3
"""USB/ADB control helpers for the physical release gate.

Device selectors are accepted only as ephemeral command inputs. They are never
printed or written to artifacts; all output uses the operator-assigned alias.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from tools.release_gate.release_gate import (
    GateError,
    SAFE_ID_RE,
    append_trace_event,
)


APPLICATION_ID = "com.bitchat.droid"


def find_adb() -> str:
    direct = shutil.which("adb")
    if direct:
        return direct
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        candidate = Path(android_home) / "platform-tools" / "adb"
        if candidate.is_file():
            return str(candidate)
    raise GateError("adb was not found; set ANDROID_HOME or add adb to PATH")


def run_adb(
    serial: str,
    arguments: list[str],
    *,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> str:
    result = runner(
        [find_adb(), "-s", serial, *arguments],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0:
        raise GateError("ADB command failed for the selected logical device")
    return result.stdout.strip()


def count_connected_devices(
    *,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> int:
    result = runner(
        [find_adb(), "devices"],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0:
        raise GateError("could not enumerate ADB devices")
    return sum(
        1
        for line in result.stdout.splitlines()[1:]
        if line.strip().endswith("\tdevice")
    )


def probe_device(serial: str, alias: str) -> dict[str, object]:
    if not SAFE_ID_RE.fullmatch(alias):
        raise GateError("device alias must be a lowercase logical identifier")
    api_text = run_adb(serial, ["shell", "getprop", "ro.build.version.sdk"])
    if not api_text.isdigit():
        raise GateError("selected device returned an invalid API level")
    features = run_adb(serial, ["shell", "pm", "list", "features"])
    manufacturer = run_adb(
        serial, ["shell", "getprop", "ro.product.manufacturer"]
    ).strip().lower()
    model = run_adb(serial, ["shell", "getprop", "ro.product.model"]).strip()
    capabilities = ["ble-central", "ble-peripheral"]
    if "android.hardware.wifi.aware" in features:
        capabilities.append("wifi-aware")
    return {
        "alias": alias,
        "platform": "android",
        "model": model,
        "manufacturer_class": re.sub(r"[^a-z0-9._-]", "-", manufacturer)[:64],
        "api_level": int(api_text),
        "physical": True,
        "capabilities": capabilities,
    }


def prepare_disposable_device(serial: str, confirmed: bool) -> None:
    if not confirmed:
        raise GateError("prepare requires --confirm-disposable-app-data")
    run_adb(serial, ["shell", "am", "force-stop", APPLICATION_ID])
    output = run_adb(serial, ["shell", "pm", "clear", APPLICATION_ID])
    if "Success" not in output:
        raise GateError("could not clear disposable app data")


def collect_resource_snapshot(serial: str) -> dict[str, int | bool]:
    pid_text = run_adb(serial, ["shell", "pidof", APPLICATION_ID])
    pid = pid_text.split()[0] if pid_text else ""
    metrics: dict[str, int | bool] = {"process-running": bool(pid)}
    if not pid.isdigit():
        return metrics
    meminfo = run_adb(serial, ["shell", "dumpsys", "meminfo", APPLICATION_ID])
    total_match = re.search(r"TOTAL\s+(\d+)", meminfo)
    metrics["total-pss-kb"] = int(total_match.group(1)) if total_match else -1
    thread_text = run_adb(
        serial,
        ["shell", "sh", "-c", f"find /proc/{pid}/task -mindepth 1 -maxdepth 1 | wc -l"],
    )
    fd_text = run_adb(
        serial,
        ["shell", "sh", "-c", f"find /proc/{pid}/fd -mindepth 1 -maxdepth 1 | wc -l"],
    )
    metrics["thread-count"] = int(thread_text) if thread_text.isdigit() else -1
    metrics["fd-count"] = int(fd_text) if fd_text.isdigit() else -1
    power = run_adb(serial, ["shell", "dumpsys", "power"])
    metrics["app-wakelock-count"] = sum(
        1
        for line in power.splitlines()
        if APPLICATION_ID in line and "WakeLock" in line
    )
    battery = run_adb(serial, ["shell", "dumpsys", "battery"])
    battery_level = re.search(r"^\s*level:\s*(\d+)", battery, re.MULTILINE)
    metrics["battery-level-percent"] = (
        int(battery_level.group(1)) if battery_level else -1
    )
    return metrics


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("count")

    probe = commands.add_parser("probe")
    probe.add_argument("--serial", required=True, help=argparse.SUPPRESS)
    probe.add_argument("--alias", required=True)

    prepare = commands.add_parser("prepare")
    prepare.add_argument("--serial", required=True, help=argparse.SUPPRESS)
    prepare.add_argument("--confirm-disposable-app-data", action="store_true")

    cleanup = commands.add_parser("cleanup")
    cleanup.add_argument("--serial", required=True, help=argparse.SUPPRESS)
    cleanup.add_argument("--confirm-disposable-app-data", action="store_true")

    snapshot = commands.add_parser("snapshot")
    snapshot.add_argument("--serial", required=True, help=argparse.SUPPRESS)
    snapshot.add_argument("--alias", required=True)
    snapshot.add_argument("--run", type=Path, required=True)
    snapshot.add_argument("--scenario", required=True)
    snapshot.add_argument("--event", default="resource-snapshot")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "count":
            print(json.dumps({"authorized-device-count": count_connected_devices()}))
        elif args.command == "probe":
            print(json.dumps(probe_device(args.serial, args.alias), sort_keys=True))
        elif args.command in {"prepare", "cleanup"}:
            prepare_disposable_device(
                args.serial, args.confirm_disposable_app_data
            )
            print(json.dumps({"status": "clean", "application": APPLICATION_ID}))
        elif args.command == "snapshot":
            metrics = collect_resource_snapshot(args.serial)
            append_trace_event(
                args.run,
                args.scenario,
                args.alias,
                args.event,
                "observed",
                None,
                metrics,
            )
            print(json.dumps({"source_alias": args.alias, "metrics": metrics}, sort_keys=True))
        return 0
    except (GateError, OSError, subprocess.SubprocessError) as error:
        print(f"android lab error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
