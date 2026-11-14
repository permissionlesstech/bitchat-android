#!/usr/bin/env python3
"""Arm and collect the opt-in, debug-only, device-local BLE test trace.

Examples:
  python tools/release_gate/ble_trace.py arm --serial DEVICE --package com.bitchat.droid.r40blefix --run-id R40-PF-01 --role phoneA
  python tools/release_gate/ble_trace.py collect --serial DEVICE --package com.bitchat.droid.r40blefix --out evidence/phoneA.jsonl
  python tools/release_gate/ble_trace.py disarm --serial DEVICE --package com.bitchat.droid.r40blefix

The helper reads only the dedicated BitchatBleTrace log tag. It never clears
logcat or gathers general device logs. Evidence stays in the caller's local
output directory.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from tools.release_gate.android_lab import find_adb

CONFIG_NAME = "ble-test-trace.json"
RUN_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
ROLE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$")
PACKAGE_RE = re.compile(r"^[A-Za-z0-9_.]+$")


class TraceError(RuntimeError):
    pass


def adb(serial: str, *args: str, timeout: int = 60) -> str:
    result = subprocess.run(
        [find_adb(), "-s", serial, *args],
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if result.returncode:
        raise TraceError(result.stderr.strip() or result.stdout.strip() or "adb command failed")
    return result.stdout


def validate(args: argparse.Namespace) -> None:
    if args.command == "arm":
        if not RUN_ID_RE.fullmatch(args.run_id or ""):
            raise TraceError("run-id must be a bounded neutral label")
        if not ROLE_RE.fullmatch(args.role or ""):
            raise TraceError("role must be a bounded neutral label")
    if not PACKAGE_RE.fullmatch(args.package):
        raise TraceError("invalid package name")


def arm(args: argparse.Namespace) -> dict[str, object]:
    config = json.dumps(
        {"enabled": True, "run_id": args.run_id, "role": args.role},
        separators=(",", ":"),
    ).encode("utf-8")
    with tempfile.NamedTemporaryFile(prefix="ble-trace-", suffix=".json", delete=False) as stream:
        local = Path(stream.name)
        stream.write(config)
    remote = "/data/local/tmp/ble-test-trace.json"
    try:
        adb(args.serial, "push", str(local), remote)
        # ADB pushes the file as shell-owned. Make only this synthetic config
        # readable so the app UID can copy it from the shared staging directory.
        adb(args.serial, "shell", "chmod", "644", remote)
        # `run-as` starts with cwd `/` on several Android builds, so never use a
        # relative `files/...` destination. `/data/data/<package>` is the stable
        # app-private alias that the package UID can write on supported devices.
        files_dir = f"/data/data/{args.package}/files"
        adb(args.serial, "shell", "run-as", args.package, "mkdir", "-p", files_dir)
        adb(
            args.serial,
            "shell",
            "run-as",
            args.package,
            "cp",
            remote,
            f"{files_dir}/{CONFIG_NAME}",
        )
    finally:
        local.unlink(missing_ok=True)
        subprocess.run(
            [find_adb(), "-s", args.serial, "shell", "rm", "-f", remote],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    return {"status": "armed", "run_id": args.run_id, "role": args.role}


def collect(args: argparse.Namespace) -> dict[str, object]:
    output = Path(args.out)
    output.parent.mkdir(parents=True, exist_ok=True)
    raw = adb(args.serial, "logcat", "-d", "-v", "raw", "-s", "BitchatBleTrace:I", "*:S", timeout=120)
    if len(raw.encode("utf-8")) > 8 * 1024 * 1024:
        raise TraceError("filtered trace exceeds 8 MiB; choose a shorter capture window")
    lines = [line for line in raw.splitlines() if line.strip()]
    output.write_text("".join(line + "\n" for line in lines), encoding="utf-8")
    return {"status": "collected", "events": len(lines), "bytes": output.stat().st_size, "out": str(output)}


def disarm(args: argparse.Namespace) -> dict[str, object]:
    adb(args.serial, "shell", "run-as", args.package, "rm", "-f", f"files/{CONFIG_NAME}")
    return {"status": "disarmed"}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    p_arm = commands.add_parser("arm")
    p_arm.add_argument("--serial", required=True)
    p_arm.add_argument("--package", required=True)
    p_arm.add_argument("--run-id", required=True)
    p_arm.add_argument("--role", required=True)
    p_collect = commands.add_parser("collect")
    p_collect.add_argument("--serial", required=True)
    p_collect.add_argument("--package", required=True)
    p_collect.add_argument("--out", required=True)
    p_disarm = commands.add_parser("disarm")
    p_disarm.add_argument("--serial", required=True)
    p_disarm.add_argument("--package", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        validate(args)
        result = {"arm": arm, "collect": collect, "disarm": disarm}[args.command](args)
        print(json.dumps(result, indent=2))
        return 0
    except (TraceError, subprocess.SubprocessError) as error:
        print(f"BLE trace error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
