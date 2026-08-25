#!/usr/bin/env python3
"""ADB-driven mesh test orchestrator for two (or more) live devices.

Drives the debug-only TestHookReceiver in the app
(intent action: com.bitchat.droid.TEST_HOOK) to perform mesh operations:
peer scanning, connect, Noise handshake, DMs, file transfer, broadcast,
announce, and raw packet injection.

Each on-device command writes a JSON result to
cache/testhook/results/<id>.json inside the app sandbox; this module polls
for it via `run-as` and returns the parsed dict.

Typical usage:
    python3 tools/release_gate/mesh_lab.py setup --serial-a X --serial-b Y --apk app/build/outputs/apk/debug/app-debug.apk
    python3 tools/release_gate/mesh_lab.py scenario dm --serial-a X --serial-b Y
    python3 tools/release_gate/mesh_lab.py scenario all --serial-a X --serial-b Y
    python3 tools/release_gate/mesh_lab.py cmd --serial X scan --extra timeout_ms=30000
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import random
import shlex
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from tools.release_gate.android_lab import APPLICATION_ID, find_adb, run_adb

TEST_HOOK_ACTION = "com.bitchat.droid.TEST_HOOK"
TEST_HOOK_COMPONENT = f"{APPLICATION_ID}/com.bitchat.android.testhook.TestHookReceiver"
RESULTS_DIR = "cache/testhook/results"
DEVICE_TMP_DIR = "/data/local/tmp/meshlab"
APP_FIXTURE_DIR = f"/data/data/{APPLICATION_ID}/cache/fixtures"

WATCH_APPLICATION_ID = "com.bitchat.watch"
WATCH_TEST_HOOK_ACTION = "com.bitchat.watch.TEST_HOOK"
WATCH_TEST_HOOK_COMPONENT = f"{WATCH_APPLICATION_ID}/com.bitchat.watch.testhook.WearTestHookReceiver"

PERMISSIONS = [
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.NEARBY_WIFI_DEVICES",
    "android.permission.RECORD_AUDIO",
]

WATCH_PERMISSIONS = [
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.RECORD_AUDIO",
]


class MeshLabError(Exception):
    pass


def _shell(serial: str, command: str) -> str:
    return run_adb(serial, ["shell", command])


class Device:
    """One ADB-connected device running a debug build with the test hook."""

    def __init__(
        self,
        serial: str,
        alias: str,
        package: str = APPLICATION_ID,
        hook_action: str = TEST_HOOK_ACTION,
        hook_component: str = TEST_HOOK_COMPONENT,
        permissions: list[str] = PERMISSIONS,
        activity_component: str = f"{APPLICATION_ID}/com.bitchat.android.MainActivity",
    ):
        self.serial = serial
        self.alias = alias
        self.package = package
        self.hook_action = hook_action
        self.hook_component = hook_component
        self.permissions = permissions
        self.activity_component = activity_component

    # -- app lifecycle ------------------------------------------------------

    def install(self, apk: Path) -> None:
        result = subprocess.run(
            [find_adb(), "-s", self.serial, "install", "-r", "-g", str(apk)],
            check=False, capture_output=True, text=True, timeout=300,
        )
        if result.returncode != 0 or "Success" not in result.stdout:
            raise MeshLabError(f"[{self.alias}] install failed: {result.stdout} {result.stderr}")

    def grant_permissions(self) -> None:
        for perm in self.permissions:
            subprocess.run(
                [find_adb(), "-s", self.serial, "shell", "pm", "grant", self.package, perm],
                check=False, capture_output=True, text=True, timeout=30,
            )

    def clear_app_data(self) -> None:
        _shell(self.serial, f"am force-stop {self.package}")
        output = _shell(self.serial, f"pm clear {self.package}")
        if "Success" not in output:
            raise MeshLabError(f"[{self.alias}] pm clear failed: {output}")

    def force_stop(self) -> None:
        _shell(self.serial, f"am force-stop {self.package}")

    def launch(self) -> None:
        """Launch the app and verify it is actually top-resumed.

        A background/cached process can be frozen by the system (observed on Wear OS),
        which silently hangs test-hook commands; the foreground activity (and the FGS it
        starts) keeps the process unfrozen.
        """
        for _attempt in range(3):
            _shell(self.serial, f"monkey -p {self.package} -c android.intent.category.LAUNCHER 1")
            time.sleep(3)
            try:
                top = _shell(
                    self.serial,
                    "dumpsys activity activities | grep topResumedActivity",
                )
                if self.package in top:
                    return
            except Exception:
                pass
            _shell(self.serial, f"am start -n {self.activity_component}")
            time.sleep(3)

    def wake(self) -> None:
        """Keep the screen on and the app foregrounded (full-power BLE duty cycle).

        A backgrounded app drops to POWER_SAVER (1 s scan per 60 s), which makes
        mesh reformation after restarts take minutes and scenarios flaky.
        `svc power stayon` only applies while charging, so also stretch the
        screen timeout as a fallback.
        """
        _shell(self.serial, "svc power stayon true")
        _shell(self.serial, "settings put system screen_off_timeout 600000")
        subprocess.run(
            [find_adb(), "-s", self.serial, "shell", "locksettings", "set-disabled", "true"],
            check=False, capture_output=True, text=True, timeout=30,
        )
        _shell(self.serial, "input keyevent KEYCODE_WAKEUP")
        _shell(self.serial, "wm dismiss-keyguard")
        _shell(self.serial, "input keyevent 82")  # dismiss non-secure keyguard
        _shell(self.serial, "input swipe 500 1500 500 400")  # swipe-up dismiss

    def reset_bluetooth(self) -> None:
        """Cycle the BT adapter; clears zombie GATT connections from peer restarts."""
        _shell(self.serial, "svc bluetooth disable")
        time.sleep(2)
        _shell(self.serial, "svc bluetooth enable")
        time.sleep(3)

    def enable_bluetooth(self) -> None:
        subprocess.run(
            [find_adb(), "-s", self.serial, "shell", "svc", "bluetooth", "enable"],
            check=False, capture_output=True, text=True, timeout=30,
        )

    # -- fixtures -----------------------------------------------------------

    def push_fixture(self, local: Path, name: str | None = None) -> str:
        """Stage a fixture inside the app sandbox and return its app-readable path.

        adb push lands files as shell:ext_data_rw, which the app cannot read
        through the FUSE Android/data mount, so the bytes are piped through
        the shell into the app's own cache directory via run-as.
        """
        fname = name or local.name
        tmp = f"{DEVICE_TMP_DIR}/{fname}"
        _shell(self.serial, f"mkdir -p {DEVICE_TMP_DIR}")
        result = subprocess.run(
            [find_adb(), "-s", self.serial, "push", str(local), tmp],
            check=False, capture_output=True, text=True, timeout=120,
        )
        if result.returncode != 0:
            raise MeshLabError(f"[{self.alias}] push failed: {result.stderr}")
        fixture_dir = f"/data/data/{self.package}/cache/fixtures"
        target = f"{fixture_dir}/{fname}"
        _shell(
            self.serial,
            f"run-as {self.package} mkdir -p {fixture_dir} && "
            f"cat {tmp} | run-as {self.package} sh -c 'cat > {target}' && rm -f {tmp}",
        )
        return target

    def clear_incoming(self) -> None:
        _shell(
            self.serial,
            f"run-as {self.package} rm -rf cache/files/incoming cache/images/incoming",
        )

    # -- test hook commands -------------------------------------------------

    def cmd(self, cmd: str, timeout_ms: int = 60_000, **extras: object) -> dict:
        """Send a test-hook command and poll for its JSON result."""
        cmd_id = uuid.uuid4().hex[:12]
        _shell(self.serial, f"run-as {self.package} rm -f {RESULTS_DIR}/{cmd_id}.json")

        args = [
            "am", "broadcast", "-a", self.hook_action,
            "-n", self.hook_component,
            "--es", "cmd", cmd,
            "--es", "id", cmd_id,
            "--el", "timeout_ms", str(timeout_ms),
            "--el", "overall_timeout_ms", str(timeout_ms + 30_000),
        ]
        for key, value in extras.items():
            if value is None:
                continue
            if isinstance(value, bool):
                args += ["--ez", key, "true" if value else "false"]
            elif isinstance(value, int):
                # `am` stores --el as Long and --ei as Integer; on-device
                # readers use getIntExtra, so int extras must go via --ei.
                args += ["--ei", key, str(value)]
            else:
                args += ["--es", key, str(value)]
        try:
            _shell(self.serial, " ".join(shlex.quote(a) for a in args))
        except Exception as error:
            # The shell occasionally hangs even though the broadcast was delivered;
            # fall through to result polling, which is the authoritative channel.
            print(f"[{self.alias}] warning: broadcast send for '{cmd}' raised: {error}", file=sys.stderr)

        deadline = time.monotonic() + (timeout_ms + 60_000) / 1000
        while time.monotonic() < deadline:
            try:
                raw = _shell(self.serial, f"run-as {self.package} cat {RESULTS_DIR}/{cmd_id}.json")
                if raw.strip().startswith("{"):
                    return json.loads(raw)
            except Exception:
                pass
            time.sleep(1.0)
        raise MeshLabError(f"[{self.alias}] timed out waiting for result of '{cmd}' ({cmd_id})")

    def cmd_ok(self, cmd: str, timeout_ms: int = 60_000, **extras: object) -> dict:
        result = self.cmd(cmd, timeout_ms=timeout_ms, **extras)
        if result.get("status") != "ok":
            raise MeshLabError(f"[{self.alias}] '{cmd}' failed: {result}")
        return result

    def logcat_dump(self, lines: int = 200) -> str:
        return _shell(self.serial, f"logcat -d -t {lines}")


class WatchDevice(Device):
    """Pixel Watch running the com.bitchat.watch debug build.

    Same test-hook protocol as the phone; different package/hook, a smaller permission
    set (Bluetooth + notifications only), and wake tweaks that skip phone-only keyguard
    commands. File-transfer scenarios are not supported on the watch yet (M5 deferred).
    """

    def __init__(self, serial: str, alias: str = "watch"):
        super().__init__(
            serial,
            alias,
            package=WATCH_APPLICATION_ID,
            hook_action=WATCH_TEST_HOOK_ACTION,
            hook_component=WATCH_TEST_HOOK_COMPONENT,
            permissions=WATCH_PERMISSIONS,
            activity_component=f"{WATCH_APPLICATION_ID}/.MainActivity",
        )

    def wake(self) -> None:
        # Keep the screen on while on the charging puck; otherwise Wear shows the
        # charging activity on top, our app loses foreground, and the OS freezes the
        # process (cached-app freezer), silently hanging test-hook commands.
        _shell(self.serial, "settings put global stay_on_while_plugged_in 3")
        _shell(self.serial, "svc power stayon true")
        _shell(self.serial, "settings put system screen_off_timeout 600000")
        _shell(self.serial, "input keyevent KEYCODE_WAKEUP")


# MARK: - fixtures

FIXTURE_SIZES = {
    "small_1k.bin": 1_024,
    "medium_512k.bin": 512 * 1_024,
    "large_2m.bin": 2 * 1_024 * 1_024,
}


def make_fixtures(directory: Path, seed: int = 1337, names: list[str] | None = None) -> dict[str, dict]:
    directory.mkdir(parents=True, exist_ok=True)
    fixtures = {}
    rng = random.Random(seed)
    for name, size in FIXTURE_SIZES.items():
        if names is not None and name not in names:
            rng.randbytes(size)  # keep the stream deterministic across subsets
            continue
        path = directory / name
        data = rng.randbytes(size)
        path.write_bytes(data)
        fixtures[name] = {"path": path, "sha256": hashlib.sha256(data).hexdigest(), "bytes": size}
    return fixtures


def make_private_media_fixtures(directory: Path, seed: int = 7331) -> dict[str, dict]:
    """Small attachment fixtures covering every private-media UI type."""
    directory.mkdir(parents=True, exist_ok=True)
    fixtures = {}
    rng = random.Random(seed)
    for name, mime in (
        ("voice_note.m4a", "audio/mp4"),
        ("image_note.jpg", "image/jpeg"),
        ("document_note.txt", "text/plain"),
    ):
        path = directory / name
        data = rng.randbytes(1_024)
        path.write_bytes(data)
        fixtures[name] = {
            "path": path,
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "mime": mime,
        }
    return fixtures


# MARK: - setup

def setup_pair(
    a: Device,
    b: Device,
    apk_a: Path | None,
    nickname_a: str,
    nickname_b: str,
    apk_b: Path | None = None,
) -> None:
    if apk_b is None:
        apk_b = apk_a
    for device, nickname, apk in ((a, nickname_a, apk_a), (b, nickname_b, apk_b)):
        device.reset_bluetooth()
        device.enable_bluetooth()
        if apk is not None:
            device.install(apk)
        device.clear_app_data()
        device.grant_permissions()
        device.wake()
        device.launch()
        device.cmd_ok("start")
        device.cmd_ok("set_nickname", name=nickname)
    wait_for_mutual_discovery(a, b)


def whoami(device: Device) -> dict:
    return device.cmd_ok("whoami")


def wait_for_peer(device: Device, peer_id: str, timeout_s: int = 90) -> dict:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        result = device.cmd_ok("peers")
        for peer in result.get("peers", []):
            if peer.get("id") == peer_id:
                return peer
        device.cmd_ok("announce")
        time.sleep(3)
    raise MeshLabError(f"[{device.alias}] peer {peer_id} not discovered within {timeout_s}s")


def wait_for_mutual_discovery(a: Device, b: Device) -> None:
    id_a = whoami(a)["peer_id"]
    id_b = whoami(b)["peer_id"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        fa = pool.submit(wait_for_peer, a, id_b)
        fb = pool.submit(wait_for_peer, b, id_a)
        fa.result()
        fb.result()


# MARK: - scenarios

def scenario_dm(a: Device, b: Device) -> dict:
    """Handshake, then exchange DMs in both directions with content assertions."""
    id_a = whoami(a)["peer_id"]
    id_b = whoami(b)["peer_id"]

    hs = a.cmd_ok("handshake", timeout_ms=60_000, peer=id_b)
    hs_back = b.cmd_ok("handshake", timeout_ms=60_000, peer=id_a)

    token_ab = f"dm-{uuid.uuid4().hex[:8]}"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        recv = pool.submit(b.cmd_ok, "dm_recv", 60_000, peer=id_a, contains=token_ab)
        time.sleep(2)
        send = pool.submit(a.cmd_ok, "dm_send", 30_000, peer=id_b, content=f"hello b {token_ab}")
        recv_result, send_result = recv.result(), send.result()
    assert token_ab in recv_result["content"], recv_result

    token_ba = f"dm-{uuid.uuid4().hex[:8]}"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        recv = pool.submit(a.cmd_ok, "dm_recv", 60_000, peer=id_b, contains=token_ba)
        time.sleep(2)
        send = pool.submit(b.cmd_ok, "dm_send", 30_000, peer=id_a, content=f"hello a {token_ba}")
        recv_result2, send_result2 = recv.result(), send.result()
    assert token_ba in recv_result2["content"], recv_result2

    return {
        "handshake_a_to_b": hs, "handshake_b_to_a": hs_back,
        "a_to_b": {"send": send_result, "recv": recv_result},
        "b_to_a": {"send": send_result2, "recv": recv_result2},
    }


def scenario_favorite_verification(a: Device, b: Device) -> dict:
    """Assert the three-state favorite exchange and local cryptographic verification."""
    id_a = whoami(a)["peer_id"]
    id_b = whoami(b)["peer_id"]
    identity_a = whoami(a)["identity_fingerprint"]

    a.cmd_ok("handshake", timeout_ms=60_000, peer=id_b)
    b.cmd_ok("handshake", timeout_ms=60_000, peer=id_a)

    def wait_for_status(
        device: Device,
        command: str,
        peer_id: str,
        predicate,
        timeout_s: int = 30,
    ) -> dict:
        deadline = time.monotonic() + timeout_s
        last: dict = {}
        while time.monotonic() < deadline:
            last = device.cmd_ok(command, peer=peer_id)
            if predicate(last):
                return last
            time.sleep(1)
        raise MeshLabError(
            f"[{device.alias}] {command} did not reach the expected state: {last}"
        )

    # Start from a known non-favorite relationship without clearing either app's data.
    a.cmd_ok("favorite_set", peer=id_b, enabled=False)
    b.cmd_ok("favorite_set", peer=id_a, enabled=False)
    neutral_a = wait_for_status(
        a,
        "favorite_status",
        id_b,
        lambda state: not state["is_favorite"] and not state["they_favorited_us"],
    )
    neutral_b = wait_for_status(
        b,
        "favorite_status",
        id_a,
        lambda state: not state["is_favorite"] and not state["they_favorited_us"],
    )

    # A favorites B. B must show the orange outline while its own favorite remains false.
    a.cmd_ok("favorite_set", peer=id_b, enabled=True)
    received_only = wait_for_status(
        b,
        "favorite_status",
        id_a,
        lambda state: (
            not state["is_favorite"]
            and state["they_favorited_us"]
            and state["star_state"] == "outlined_orange"
        ),
    )

    # B favorites back. Both relationships become mutual and B's star becomes filled.
    b.cmd_ok("favorite_set", peer=id_a, enabled=True)
    mutual_b = wait_for_status(
        b,
        "favorite_status",
        id_a,
        lambda state: state["is_mutual"] and state["star_state"] == "filled",
    )
    mutual_a = wait_for_status(
        a,
        "favorite_status",
        id_b,
        lambda state: state["is_mutual"] and state["star_state"] == "filled",
    )

    # The code B displays for A must be A's SHA-256 Noise identity fingerprint.
    verification_before = b.cmd_ok("verification_status", peer=id_a)
    if verification_before.get("fingerprint") != identity_a:
        raise MeshLabError("displayed verification fingerprint does not match peer identity")
    b.cmd_ok("verification_set", peer=id_a, enabled=True)
    verification_after = wait_for_status(
        b,
        "verification_status",
        id_a,
        lambda state: state["verified"],
    )

    return {
        "neutral": {"a": neutral_a, "b": neutral_b},
        "received_favorite": received_only,
        "mutual": {"a": mutual_a, "b": mutual_b},
        "verification": {
            "fingerprint_matches_peer_identity": True,
            "before": verification_before,
            "after": verification_after,
        },
    }


def scenario_broadcast(a: Device, b: Device) -> dict:
    """Public broadcast from A received by B."""
    id_a = whoami(a)["peer_id"]
    token = f"bc-{uuid.uuid4().hex[:8]}"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        recv = pool.submit(b.cmd_ok, "msg_recv", 60_000, contains=token)
        time.sleep(2)
        send = pool.submit(a.cmd_ok, "broadcast_msg", 30_000, content=f"broadcast {token}")
        recv_result, send_result = recv.result(), send.result()
    assert recv_result["from"] == id_a, recv_result
    return {"send": send_result, "recv": recv_result}


def _ptt_one_way(
    sender: Device,
    receiver: Device,
    sender_id: str,
    receiver_id: str,
    scope: str,
) -> dict:
    # Long enough to expose sustained GATT/codec backpressure while remaining a quick gate.
    send_args: dict[str, object] = {"duration_ms": 3_000}
    if scope == "dm":
        send_args["peer"] = receiver_id
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        recv = pool.submit(
            receiver.cmd_ok,
            "ptt_recv",
            240_000,
            peer=sender_id,
            scope="public" if scope == "public" else "dm",
        )
        time.sleep(2)
        send = pool.submit(sender.cmd_ok, "ptt_send", 240_000, **send_args)
        recv_result, send_result = recv.result(), send.result()
    if not recv_result.get("live_observed") or recv_result.get("frames", 0) <= 0:
        raise MeshLabError(f"live {scope} burst was not assembled before fallback: {recv_result}")
    if recv_result.get("burst_id") != send_result.get("burst_id"):
        raise MeshLabError(
            f"live {scope} burst/final-note identity mismatch: send={send_result} recv={recv_result}"
        )
    encoded = int(send_result.get("encoded_frames", 0))
    queued = int(send_result.get("queued_pcm_frames", 0))
    sent_packets = int(send_result.get("data_packets", 0))
    received_packets = int(recv_result.get("data_packets", 0))
    expected_packets = int(recv_result.get("expected_packets", 0))
    missing_packets = int(recv_result.get("missing_packets", -1))
    received_frames = int(recv_result.get("frames", 0))
    if int(send_result.get("dropped_oversize_frames", -1)) != 0:
        raise MeshLabError(f"live {scope} encoder produced an unsent AAC frame: {send_result}")
    # AAC encoders may emit up to two priming access units in addition to one unit per PCM block.
    if queued <= 0 or encoded < queued or encoded > queued + 2 or sent_packets != encoded:
        raise MeshLabError(f"live {scope} encoder/packetizer continuity failed: {send_result}")
    outbound_packets = int(send_result.get("outbound_packets", 0))
    delivered_packets = int(send_result.get("delivered_packets", -1))
    if outbound_packets != delivered_packets or outbound_packets != sent_packets + 2:
        raise MeshLabError(f"live {scope} network dispatch did not drain in order: {send_result}")
    minimum_realtime_pcm_frames = int(3_000 / 64) - 2
    if queued < minimum_realtime_pcm_frames:
        raise MeshLabError(f"live {scope} capture fell behind real time: {send_result}")
    if missing_packets != 0:
        raise MeshLabError(f"live {scope} burst contained sequence gaps: {recv_result}")
    if not (received_packets == expected_packets == sent_packets and received_frames == encoded):
        raise MeshLabError(
            f"live {scope} frame counts differ across the physical link: "
            f"send={send_result} recv={recv_result}"
        )
    decoded_samples = int(recv_result.get("decoded_samples", 0))
    if decoded_samples < max(1, received_frames - 2) * 1_024:
        raise MeshLabError(f"live {scope} decoded PCM is truncated: {recv_result}")
    rms = float(recv_result.get("rms", 0.0))
    silent_fraction = float(recv_result.get("silent_block_fraction", 1.0))
    longest_silent_run = int(recv_result.get("longest_silent_block_run", 999))
    crossings_per_second = float(recv_result.get("zero_crossings_per_second", 0.0))
    if rms < 0.05 or silent_fraction > 0.10 or longest_silent_run > 2:
        raise MeshLabError(f"live {scope} decoded tone is silent or broken up: {recv_result}")
    if not 650.0 <= crossings_per_second <= 1_150.0:
        raise MeshLabError(f"live {scope} decoded tone continuity is distorted: {recv_result}")
    return {"send": send_result, "recv": recv_result}


def scenario_ptt_dm(a: Device, b: Device) -> dict:
    """Gap-free Noise-encrypted PTT tone plus finalized note in both directions."""
    id_a = whoami(a)["peer_id"]
    id_b = whoami(b)["peer_id"]
    a.cmd_ok("handshake", timeout_ms=60_000, peer=id_b)
    b.cmd_ok("handshake", timeout_ms=60_000, peer=id_a)
    return {
        "a_to_b": _ptt_one_way(a, b, id_a, id_b, "dm"),
        "b_to_a": _ptt_one_way(b, a, id_b, id_a, "dm"),
    }


def scenario_ptt_broadcast(a: Device, b: Device) -> dict:
    """Gap-free signed public PTT tone plus finalized note in both directions."""
    id_a = whoami(a)["peer_id"]
    id_b = whoami(b)["peer_id"]
    return {
        "a_to_b": _ptt_one_way(a, b, id_a, id_b, "public"),
        "b_to_a": _ptt_one_way(b, a, id_b, id_a, "public"),
    }


def scenario_file(
    a: Device,
    b: Device,
    fixtures: dict[str, dict],
    private: bool = False,
    recipient: str | None = None,
) -> dict:
    """File transfer A -> B with sha256 integrity verification."""
    id_b = whoami(b)["peer_id"]
    b.clear_incoming()  # avoid name-uniquified collisions across runs
    results = {}
    for name, fixture in fixtures.items():
        remote = a.push_fixture(fixture["path"])
        send_kwargs: dict[str, object] = {"path": remote}
        if private:
            send_kwargs["peer"] = recipient or id_b
        if fixture.get("mime"):
            send_kwargs["mime"] = fixture["mime"]
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            recv = pool.submit(b.cmd_ok, "file_recv", 240_000, name_contains=name)
            time.sleep(2)
            send = pool.submit(a.cmd_ok, "file_send", 240_000, **send_kwargs)
            recv_result, send_result = recv.result(), send.result()
        digest_ok = recv_result["sha256"] == fixture["sha256"]
        results[name] = {
            "send": send_result, "recv": recv_result,
            "expected_sha256": fixture["sha256"], "digest_match": digest_ok,
        }
        if not digest_ok:
            raise MeshLabError(
                f"file '{name}' digest mismatch: {recv_result['sha256']} != {fixture['sha256']}"
            )
    return results


def scenario_private_media(a: Device, b: Device) -> dict:
    """Voice, image, and generic file sends through the private-chat contact ID."""
    identity = whoami(b)
    noise_public_key = bytes.fromhex(identity["noise_public_key"])
    conversation_id = f"contact_{hashlib.sha256(noise_public_key).hexdigest()}"
    fixtures = make_private_media_fixtures(
        Path(tempfile.mkdtemp(prefix="meshlab-private-media-"))
    )
    return scenario_file(
        a,
        b,
        fixtures,
        private=True,
        recipient=conversation_id,
    )


def scenario_raw(a: Device, b: Device) -> dict:
    """Raw packet injection (unsigned announce-type packet) reaches the mesh."""
    payload = b"meshlab-raw-" + uuid.uuid4().hex[:8].encode()
    result = a.cmd_ok("raw_send", 30_000, type="05", payload_hex=payload.hex())
    return {"send": result}


# MARK: - session / identity churn scenarios

def _dm_roundtrip(a: Device, b: Device, id_a: str, id_b: str) -> dict:
    """Exchange DMs in both directions with content assertions."""
    token_ab = f"dm-{uuid.uuid4().hex[:8]}"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        recv = pool.submit(b.cmd_ok, "dm_recv", 60_000, peer=id_a, contains=token_ab)
        time.sleep(2)
        send = pool.submit(a.cmd_ok, "dm_send", 30_000, peer=id_b, content=f"hello b {token_ab}")
        recv_ab, send_ab = recv.result(), send.result()
    assert token_ab in recv_ab["content"], recv_ab

    token_ba = f"dm-{uuid.uuid4().hex[:8]}"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        recv = pool.submit(a.cmd_ok, "dm_recv", 60_000, peer=id_b, contains=token_ba)
        time.sleep(2)
        send = pool.submit(b.cmd_ok, "dm_send", 30_000, peer=id_a, content=f"hello a {token_ba}")
        recv_ba, send_ba = recv.result(), send.result()
    assert token_ba in recv_ba["content"], recv_ba
    return {"a_to_b": recv_ab, "b_to_a": recv_ba}


def wait_session_established(device: Device, peer_id: str, timeout_s: int = 90) -> dict:
    deadline = time.monotonic() + timeout_s
    last: dict = {}
    while time.monotonic() < deadline:
        last = device.cmd_ok("session", peer=peer_id)
        if last.get("established"):
            return last
        time.sleep(2)
    raise MeshLabError(
        f"[{device.alias}] session with {peer_id} not established within {timeout_s}s (last: {last})"
    )


def ensure_direct_link(a: Device, b: Device, id_a: str, id_b: str) -> None:
    """Wait for rediscovery, then force a direct GATT connection both ways.

    Backgrounded devices drop to POWER_SAVER duty cycles (1 s scan per 60 s), so
    passively waiting for the mesh to reform takes minutes. The explicit connect
    makes restart scenarios deterministic. The address↔peer mapping is learned from
    direct-link announces and can lag peer-list discovery after a restart, so the
    connect attempt is retried while the peer announces.
    """
    wait_for_peer(a, id_b, timeout_s=120)
    wait_for_peer(b, id_a, timeout_s=120)
    for device, peer, announcer in ((a, id_b, b), (b, id_a, a)):
        connected = False
        last: dict = {}
        for _attempt in range(4):
            last = device.cmd("connect", timeout_ms=45_000, peer=peer)
            if last.get("status") == "ok" and last.get("direct"):
                connected = True
                break
            # Already acceptable if the mesh formed a direct link on its own.
            peers = device.cmd_ok("peers").get("peers", [])
            match = next((p for p in peers if p.get("id") == peer), None)
            if match and match.get("direct"):
                connected = True
                break
            try:
                announcer.cmd_ok("announce")
            except MeshLabError:
                pass
            time.sleep(4)
        if not connected:
            raise MeshLabError(f"[{device.alias}] no direct link to {peer}: connect={last}")


def force_handshake(device: Device, peer_id: str, attempts: int = 5, per_attempt_s: int = 20) -> dict:
    """Retry explicit handshakes; inits can be lost while links settle."""
    last: dict = {}
    for _ in range(attempts):
        last = device.cmd("handshake", timeout_ms=per_attempt_s * 1000, peer=peer_id)
        if last.get("status") == "ok":
            return last
        time.sleep(2)
    raise MeshLabError(f"[{device.alias}] handshake with {peer_id} failed after {attempts} attempts (last: {last})")


def scenario_session_recovery(a: Device, b: Device) -> dict:
    """Process death on B: identity must persist, in-memory Noise sessions are lost.

    Expected recovery flow: A's DM sent with its stale session is dropped by B
    (B has no session and no kick path on pure decrypt failure); B's outgoing DM
    auto-triggers a fresh handshake; subsequent DMs must flow both ways.
    """
    id_a = whoami(a)["peer_id"]
    id_b = whoami(b)["peer_id"]
    a.cmd_ok("handshake", 60_000, peer=id_b)
    b.cmd_ok("handshake", 60_000, peer=id_a)
    baseline = _dm_roundtrip(a, b, id_a, id_b)

    b.force_stop()
    b.wake()
    b.launch()
    b.cmd_ok("start")
    b.cmd_ok("set_nickname", name="bob")
    id_b_after = whoami(b)["peer_id"]
    if id_b_after != id_b:
        raise MeshLabError(f"identity changed across process death: {id_b} -> {id_b_after}")

    wait_for_peer(a, id_b, timeout_s=120)
    wait_for_peer(b, id_a, timeout_s=120)
    ensure_direct_link(a, b, id_a, id_b)

    # A -> B with A's stale session: B lost its in-memory session; drop expected.
    a.cmd_ok("dm_send", 30_000, peer=id_b, content=f"stale-{uuid.uuid4().hex[:8]}")
    # B -> A: no session on B, sendPrivateMessage auto-fires the re-handshake.
    # The fire-and-forget handshake has no retry, so repeat the trigger, then
    # fall back to explicit handshake commands if the auto-path stays stuck.
    session_b: dict = {}
    for _attempt in range(3):
        b.cmd_ok("dm_send", 30_000, peer=id_a, content=f"trigger-{uuid.uuid4().hex[:8]}")
        try:
            session_b = wait_session_established(b, id_a, timeout_s=20)
            break
        except MeshLabError:
            continue
    if not session_b:
        force_handshake(b, id_a)
        session_b = wait_session_established(b, id_a, timeout_s=30)

    session_a = wait_session_established(a, id_b)
    recovered = _dm_roundtrip(a, b, id_a, id_b)
    return {
        "identity_preserved": True,
        "baseline": baseline,
        "session_a": session_a,
        "session_b": session_b,
        "recovered": recovered,
    }


def scenario_identity_reset(a: Device, b: Device) -> dict:
    """pm clear on B mid-session: new identity, rediscovery, fresh handshake and DMs."""
    id_a = whoami(a)["peer_id"]
    id_b_old = whoami(b)["peer_id"]
    a.cmd_ok("handshake", 60_000, peer=id_b_old)
    b.cmd_ok("handshake", 60_000, peer=id_a)
    _dm_roundtrip(a, b, id_a, id_b_old)

    b.clear_app_data()
    b.grant_permissions()
    b.wake()
    b.launch()
    b.cmd_ok("start")
    b.cmd_ok("set_nickname", name="bob")
    id_b_new = whoami(b)["peer_id"]
    if id_b_new == id_b_old:
        raise MeshLabError("identity survived pm clear")

    wait_for_peer(a, id_b_new, timeout_s=180)
    ensure_direct_link(a, b, id_a, id_b_new)
    force_handshake(a, id_b_new)
    force_handshake(b, id_a)
    recovered = _dm_roundtrip(a, b, id_a, id_b_new)

    # Inspect how A treats the dead peer's stale session (evidence, not an assertion).
    stale = a.cmd("session", peer=id_b_old)
    return {
        "old_peer_id": id_b_old,
        "new_peer_id": id_b_new,
        "identity_changed": True,
        "recovered": recovered,
        "stale_session_on_a": stale,
    }


def scenario_file_oversize(a: Device, b: Device, fixtures: dict[str, dict]) -> dict:
    """Oversized broadcast file must be rejected sender-side (>256 fragments)."""
    fixture = fixtures["medium_512k.bin"]
    remote = a.push_fixture(fixture["path"])
    send = a.cmd("file_send", timeout_ms=60_000, path=remote)
    rejected = send.get("status") == "error" and "rejected" in send.get("error", "")
    if not rejected:
        raise MeshLabError(f"expected sender-side rejection, got: {send}")
    # Receiver must not see any file appear.
    recv = b.cmd("file_recv", timeout_ms=15_000, name_contains="medium_512k")
    if recv.get("status") == "ok":
        raise MeshLabError(f"receiver unexpectedly saved an oversized file: {recv}")
    return {"send": send, "receiver_saw_file": False}


SCENARIOS = {
    "dm": scenario_dm,
    "favorite_verification": scenario_favorite_verification,
    "broadcast": scenario_broadcast,
    "ptt_dm": scenario_ptt_dm,
    "ptt_broadcast": scenario_ptt_broadcast,
    # Broadcast transfers are receiver-capped at 256 fragments (~120 KB); only
    # the small fixture is end-to-end receivable.
    "file": lambda a, b: scenario_file(
        a, b,
        make_fixtures(Path(tempfile.mkdtemp(prefix="meshlab-fixtures-")), names=["small_1k.bin"]),
    ),
    "file_oversize": lambda a, b: scenario_file_oversize(
        a, b, make_fixtures(Path(tempfile.mkdtemp(prefix="meshlab-fixtures-")))
    ),
    # Private media is hard-capped at 256 fragments (PrivateMediaTransfer), so only
    # the small fixture fits; larger sizes are expected to be rejected by the sender.
    "file_private": lambda a, b: scenario_file(
        a, b,
        make_fixtures(Path(tempfile.mkdtemp(prefix="meshlab-fixtures-")), names=["small_1k.bin"]),
        private=True,
    ),
    "media_private": scenario_private_media,
    "raw": scenario_raw,
    "session_recovery": scenario_session_recovery,
    "identity_reset": scenario_identity_reset,
}

# Scenarios supported when device B is a watch (file scenarios are receive-only: phone sends,
# the watch must receive with matching digests).
WATCH_SCENARIOS = [
    "dm",
    "favorite_verification",
    "broadcast",
    "ptt_dm",
    "ptt_broadcast",
    "raw",
    "file",
    "file_private",
    "session_recovery",
    "identity_reset",
]


def run_scenario(name: str, a: Device, b: Device, out: Path | None) -> dict:
    started = time.time()
    evidence: dict[str, object] = {"scenario": name, "devices": [a.alias, b.alias]}
    try:
        supported = WATCH_SCENARIOS if isinstance(b, WatchDevice) else list(SCENARIOS)
        if name == "all":
            results = {}
            failures = []
            for n in supported:
                sub = run_scenario(n, a, b, out)
                results[n] = sub.get("results", {"error": sub.get("error", "unknown")})
                if sub["status"] != "pass":
                    failures.append(n)
            evidence["results"] = results
            if failures:
                raise MeshLabError(f"sub-scenarios failed: {', '.join(failures)}")
        elif name not in supported:
            raise MeshLabError(f"scenario '{name}' is not supported on device '{b.alias}'")
        else:
            evidence["results"] = SCENARIOS[name](a, b)
        evidence["status"] = "pass"
    except (MeshLabError, AssertionError) as error:
        evidence["status"] = "fail"
        evidence["error"] = str(error)
        evidence["logcat"] = {d.alias: d.logcat_dump() for d in (a, b)}
    evidence["duration_s"] = round(time.time() - started, 1)
    if out is not None:
        out.mkdir(parents=True, exist_ok=True)
        (out / f"{name}-evidence.json").write_text(json.dumps(evidence, indent=2, default=str))
    return evidence


# MARK: - CLI

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    setup = commands.add_parser("setup", help="install, grant, launch, nickname, discover")
    setup.add_argument("--serial-a", required=True)
    setup.add_argument("--serial-b")
    setup.add_argument("--serial-watch", help="watch serial; used as device B (overrides --serial-b)")
    setup.add_argument("--apk", type=Path, default=None)
    setup.add_argument("--watch-apk", type=Path, default=None)
    setup.add_argument("--nickname-a", default="alice")
    setup.add_argument("--nickname-b", default="bob")

    scenario = commands.add_parser("scenario", help="run a test scenario on two devices")
    scenario.add_argument("name", choices=[*SCENARIOS.keys(), "all"])
    scenario.add_argument("--serial-a", required=True)
    scenario.add_argument("--serial-b")
    scenario.add_argument("--serial-watch", help="watch serial; used as device B (overrides --serial-b)")
    scenario.add_argument("--out", type=Path, default=None, help="evidence output directory")

    raw = commands.add_parser("cmd", help="send a raw test-hook command to one device")
    raw.add_argument("--serial", required=True)
    raw.add_argument("cmd")
    raw.add_argument("--extra", action="append", default=[], help="key=value string extra (repeatable)")
    raw.add_argument("--extra-int", action="append", default=[], help="key=value int extra (repeatable)")
    raw.add_argument("--timeout-ms", type=int, default=60_000)
    return parser


def _resolve_devices(args: argparse.Namespace) -> tuple[Device, Device]:
    """Device A is always the phone; device B is a watch when --serial-watch is given."""
    a = Device(args.serial_a, "alpha")
    if getattr(args, "serial_watch", None):
        return a, WatchDevice(args.serial_watch)
    if not getattr(args, "serial_b", None):
        raise MeshLabError("either --serial-b or --serial-watch is required")
    return a, Device(args.serial_b, "beta")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "setup":
            a, b = _resolve_devices(args)
            nickname_b = "watch" if isinstance(b, WatchDevice) and args.nickname_b == "bob" else args.nickname_b
            setup_pair(
                a, b, args.apk, args.nickname_a, nickname_b,
                apk_b=args.watch_apk if isinstance(b, WatchDevice) else None,
            )
            print(json.dumps({"status": "ok", "step": "setup"}))
        elif args.command == "scenario":
            a, b = _resolve_devices(args)
            evidence = run_scenario(args.name, a, b, args.out)
            print(json.dumps(evidence, indent=2, default=str))
            return 0 if evidence["status"] == "pass" else 1
        elif args.command == "cmd":
            extras: dict[str, object] = {}
            extras: dict[str, object] = {}
            for item in args.extra:
                key, _, value = item.partition("=")
                extras[key] = value
            for item in args.extra_int:
                key, _, value = item.partition("=")
                extras[key] = int(value)
            result = Device(args.serial, "device").cmd(args.cmd, timeout_ms=args.timeout_ms, **extras)
            print(json.dumps(result, indent=2, default=str))
            return 0 if result.get("status") == "ok" else 1
        return 0
    except MeshLabError as error:
        print(f"mesh lab error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
