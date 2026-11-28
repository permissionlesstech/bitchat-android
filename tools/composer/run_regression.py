#!/usr/bin/env python3
"""Run composer regressions on an owned disposable, network-blocked emulator."""
import argparse
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time

PACKAGE = "com.bitchat.droid.composerTest"
CLASSES = ",".join("com.bitchat.android.ui." + name for name in (
    "ComposerPanicInstrumentedTest", "ComposerEdgeCasesInstrumentedTest", "ComposerOutcomeInstrumentedTest"))


TEST_CLASSES = {name.rsplit(".", 1)[-1] for name in CLASSES.split(",")}


def failure_summary(output):
    """Expose test identifiers and exception types, never raw assertion values or paths."""
    details = []
    current_class = current_test = ""
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: class="):
            candidate = line.split("=", 1)[1].rsplit(".", 1)[-1]
            current_class = candidate if candidate in TEST_CLASSES else ""
        elif line.startswith("INSTRUMENTATION_STATUS: test="):
            candidate = line.split("=", 1)[1]
            current_test = candidate if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,160}", candidate) else ""
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: -") and current_class and current_test:
            details.append(current_class + "." + current_test + " (failed or skipped)")
        exception = re.match(r"(?:INSTRUMENTATION_STATUS: stack=)?((?:java|android|androidx|kotlin|org\.junit)\.[A-Za-z0-9_.$]*(?:Exception|Error|ComparisonFailure))(?::|$)", line.strip())
        if exception: details.append(exception.group(1))
        location = re.search(r"\((Composer[A-Za-z]+Test\.kt:\d+)\)", line)
        if location and location.group(1).split(":", 1)[0][:-3] in TEST_CLASSES:
            details.append(location.group(1))
    if not details and current_class and current_test:
        details.append("last test: " + current_class + "." + current_test)
    return "; ".join(dict.fromkeys(details)) or "no completed test details; inspect the local instrumentation log"


def require_success(output):
    if not re.search(r"^OK \(9 tests\)$", output, re.MULTILINE) or any(
        marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "INSTRUMENTATION_ABORTED", "INSTRUMENTATION_STATUS_CODE: -3", "INSTRUMENTATION_STATUS_CODE: -4")
    ):
        raise RuntimeError("Composer instrumentation failed: " + failure_summary(output))


def run_instrumentation(command, log_path):
    try:
        with log_path.open("w") as test_log:
            subprocess.run(command, stdout=test_log, stderr=subprocess.STDOUT, check=True, timeout=600)
    except subprocess.TimeoutExpired:
        raise RuntimeError("Composer instrumentation timed out after 600s: " + failure_summary(log_path.read_text())) from None
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f"Composer instrumentation exited {error.returncode}: " + failure_summary(log_path.read_text())) from None
    output = log_path.read_text()
    require_success(output)
    return output


def require_first_output_reject(rules):
    """Check rule order at this instant; this is not continuous traffic monitoring."""
    output_rules = [line.strip() for line in rules.splitlines() if line.startswith("-A OUTPUT ")]
    expected = "-A OUTPUT ! -o lo -j REJECT"
    accepted = {expected, expected + " --reject-with icmp-port-unreachable",
                expected + " --reject-with icmp6-port-unreachable"}
    if not output_rules or output_rules[0] not in accepted:
        raise RuntimeError("External egress rejection is not the first OUTPUT rule")


def finish_cleanup(actions, primary_failure):
    """Attempt each teardown step without replacing an active test/setup failure."""
    failures = []
    for action in actions:
        try:
            action()
        except Exception as error:
            failures.append(type(error).__name__)
    if failures:
        message = "Composer cleanup failed: " + ", ".join(failures)
        if primary_failure is not None:
            print(message, file=sys.stderr)
        else:
            raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--image", required=True, help="Installed system image package, without Play Store")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, default=5558)
    parser.add_argument("--adb-port", type=int, default=5088)
    args = parser.parse_args()
    if args.port % 2 or not 5554 <= args.port <= 65534:
        parser.error("Use an even emulator port between 5554 and 65534")
    for apk in (args.apk, args.test_apk):
        if not apk.is_file(): parser.error("Build both matching APKs before starting the emulator")
    for port in (args.port, args.port + 1, args.adb_port):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    args.output.mkdir(parents=True, exist_ok=False)
    adb = [str(args.sdk / "platform-tools/adb"), "-P", str(args.adb_port), "-s", f"emulator-{args.port}"]
    def call(*items, timeout=60):
        return subprocess.check_output(adb + list(items), stderr=subprocess.STDOUT, text=True, timeout=timeout).strip()
    with tempfile.TemporaryDirectory(prefix="composer-regression-") as scratch:
        env = dict(os.environ, ANDROID_AVD_HOME=scratch, ANDROID_ADB_SERVER_PORT=str(args.adb_port))
        manager = args.sdk / "cmdline-tools/latest/bin/avdmanager"
        subprocess.run([str(manager), "create", "avd", "-n", "composer_regression", "-k", args.image,
                        "-p", str(Path(scratch) / "device.avd")], input="no\n", text=True,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True, env=env, timeout=90)
        with (args.output / "emulator.log").open("w") as log:
            process = subprocess.Popen([str(args.sdk / "emulator/emulator"), "-avd", "composer_regression",
                "-port", str(args.port), "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim",
                "-gpu", "swiftshader_indirect", "-memory", "2048", "-no-metrics", "-crash-report-mode", "disabled"], env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 180
                while True:
                    if process.poll() is not None: raise RuntimeError("Owned emulator exited during startup")
                    try:
                        if call("shell", "getprop", "sys.boot_completed", timeout=5) == "1": break
                    except (subprocess.SubprocessError, OSError): pass
                    if time.monotonic() >= deadline: raise TimeoutError("Owned emulator did not boot")
                    time.sleep(1)
                if call("shell", "getprop", "ro.kernel.qemu") != "1": raise RuntimeError("Refusing a physical device")
                call("root"); call("wait-for-device")
                if call("shell", "id", "-u") != "0": raise RuntimeError("A rootable disposable image is required")
                def verify_firewalls(stage):
                    for firewall in ("iptables", "ip6tables"):
                        rules = call("shell", firewall, "-S", "OUTPUT")
                        (args.output / f"{firewall}-{stage}.log").write_text(rules)
                        require_first_output_reject(rules)

                # Block external egress before installing or starting either package.
                for firewall in ("iptables", "ip6tables"):
                    call("shell", firewall, "-I", "OUTPUT", "1", "!", "-o", "lo", "-j", "REJECT")
                verify_firewalls("before")
                call("shell", "svc", "wifi", "disable")
                call("shell", "svc", "data", "disable")
                call("shell", "cmd", "bluetooth_manager", "disable")
                call("shell", "cmd", "location", "set-location-enabled", "true")
                for apk in (args.apk, args.test_apk): call("install", "-r", str(apk.resolve()), timeout=90)
                for permission in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"):
                    call("shell", "pm", "grant", PACKAGE, "android.permission." + permission)
                call("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
                call("shell", "input", "keyevent", "KEYCODE_WAKEUP")
                call("shell", "wm", "dismiss-keyguard")
                call("shell", "am", "force-stop", PACKAGE)
                output = run_instrumentation(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", CLASSES,
                    PACKAGE + ".test/com.bitchat.android.ui.ComposerPanicTestRunner"], args.output / "instrumentation.log")
                verify_firewalls("after")
                print("Composer regression: 9 tests passed; outbound reject rule first before and after tests")
            finally:
                primary_failure = sys.exc_info()[1]
                def stop_emulator():
                    try: call("emu", "kill", timeout=10)
                    except (subprocess.SubprocessError, OSError): pass
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.terminate()
                        try: process.wait(timeout=10)
                        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
                def stop_adb():
                    subprocess.run([str(args.sdk / "platform-tools/adb"), "-P", str(args.adb_port), "kill-server"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10, check=True)
                finish_cleanup((stop_emulator, stop_adb), primary_failure)



if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
    except (OSError, subprocess.SubprocessError) as error:
        # Keep local paths, subprocess arguments and raw device output out of CI output.
        print("Composer setup or cleanup failed: " + type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
