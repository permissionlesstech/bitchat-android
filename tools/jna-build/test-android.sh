#!/usr/bin/env bash
# Runs only on an otherwise idle, disposable CI emulator. No physical devices.
set -euo pipefail
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK is required}}"
AVD_NAME=bitchat-jna-ci
EMULATOR_SERIAL=emulator-5554
IMAGE='system-images;android-35;default;x86_64'
if adb -s "$EMULATOR_SERIAL" get-state >/dev/null 2>&1; then
    echo 'error: emulator port is already occupied' >&2
    exit 1
fi
"$SDK/cmdline-tools/latest/bin/sdkmanager" emulator "$IMAGE"
printf 'no\n' | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd --name "$AVD_NAME" --package "$IMAGE"
"$SDK/emulator/emulator" -avd "$AVD_NAME" -port 5554 -no-window -no-audio \
    -no-boot-anim -no-snapshot -gpu swiftshader_indirect > "${RUNNER_TEMP:?}/jna-emulator.log" 2>&1 &
EMULATOR_PID=$!
trap 'kill "$EMULATOR_PID" 2>/dev/null || true; wait "$EMULATOR_PID" 2>/dev/null || true' EXIT
deadline=$((SECONDS + 180))
until [[ "$(adb -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null || (( SECONDS >= deadline )); then
        echo 'error: disposable emulator did not boot' >&2
        exit 1
    fi
    sleep 1
done
# The instrumentation runner uses plain Application: no transports or location.
ANDROID_SERIAL="$EMULATOR_SERIAL" ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.bitchat.android.nostr.PairwiseFfiInstrumentedTest
