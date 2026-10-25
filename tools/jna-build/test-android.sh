#!/usr/bin/env bash
# Runs only on an otherwise idle, disposable CI emulator. No physical devices.
set -euo pipefail
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != --boot-only ) ]]; then
    echo 'usage: test-android.sh [--boot-only]' >&2
    exit 1
fi
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK is required}}"
ADB="$SDK/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then
    echo 'error: Android SDK platform-tools/adb is required' >&2
    exit 1
fi
"$ADB" start-server
AVD_NAME=bitchat-jna-ci
EMULATOR_SERIAL=emulator-5554
IMAGE='system-images;android-35;default;x86_64'
if "$ADB" -s "$EMULATOR_SERIAL" get-state >/dev/null 2>&1; then
    echo 'error: emulator port is already occupied' >&2
    exit 1
fi
# avdmanager and emulator can otherwise resolve different default user roots.
export ANDROID_AVD_HOME="${RUNNER_TEMP:?}/jna-avd"
mkdir -p "$ANDROID_AVD_HOME"
"$SDK/cmdline-tools/latest/bin/sdkmanager" emulator "$IMAGE"
printf 'no\n' | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
    --name "$AVD_NAME" --package "$IMAGE" --path "$ANDROID_AVD_HOME/$AVD_NAME.avd"
if [[ ! -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]]; then
    echo 'error: AVD registration is missing from the shared test directory' >&2
    exit 1
fi
"$SDK/emulator/emulator" -avd "$AVD_NAME" -port 5554 -no-window -no-audio \
    -no-boot-anim -no-snapshot -gpu swiftshader > "${RUNNER_TEMP:?}/jna-emulator.log" 2>&1 &
EMULATOR_PID=$!
cleanup() {
    kill "$EMULATOR_PID" 2>/dev/null || true
    wait "$EMULATOR_PID" 2>/dev/null || true
    "$SDK/cmdline-tools/latest/bin/avdmanager" delete avd --name "$AVD_NAME" >/dev/null
}
trap cleanup EXIT
deadline=$((SECONDS + 180))
until [[ "$("$ADB" -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null || (( SECONDS >= deadline )); then
        echo 'error: disposable emulator did not boot' >&2
        # Keep raw startup/configuration logs private. Emit only bounded errors,
        # removing paths and addresses even on disposable hosted runners.
        python3 - "$RUNNER_TEMP/jna-emulator.log" <<'PY' >&2
import re
import sys
from pathlib import Path

errors = []
for line in Path(sys.argv[1]).read_text(errors="replace").splitlines():
    line = re.sub(r"\x1b\[[0-9;]*m", "", line)
    if not re.search(r"\b(?:ERROR|FATAL|PANIC)\b|error while loading shared libraries", line):
        continue
    line = re.sub(r"(?:[A-Za-z]:)?/[^\s'\"<>]+", "<path>", line)
    line = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", "<address>", line)
    line = re.sub(r"\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b", "<address>", line)
    errors.append(line[:500])
print("\n".join(errors[:8]) or "No startup error was reported; emulator exited or boot timed out.")
PY
        exit 1
    fi
    sleep 1
done
if [[ "${1:-}" == --boot-only ]]; then
    echo 'Disposable Android emulator booted successfully.'
    exit 0
fi
# The instrumentation runner uses plain Application: no transports or location.
ANDROID_SERIAL="$EMULATOR_SERIAL" ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.bitchat.android.nostr.PairwiseFfiInstrumentedTest
