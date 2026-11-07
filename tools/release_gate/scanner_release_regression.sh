#!/usr/bin/env bash
# Reproduce/verify the real scanner entry point after production R8 shrinking.
# Usage: bash tools/release_gate/scanner_release_regression.sh <disposable-emulator>
# Requires JDK 21, the pinned Android SDK, and adb on PATH. Never use publishable
# signing keys here: -PreleaseUiTests=true uses the debug key for test APKs only.
set -euo pipefail

if [[ $# != 1 || "$1" != emulator-* ]]; then
    echo "Usage: $0 <disposable-emulator>" >&2
    exit 2
fi
scanner_emulator="$1"
if [[ "$(adb -s "$scanner_emulator" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo "Refusing to install: target is not an emulator." >&2
    exit 2
fi
scanner_abi="$(adb -s "$scanner_emulator" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$scanner_abi" in
    arm64-v8a|x86_64|armeabi-v7a|x86) ;;
    *) echo "Unsupported emulator ABI." >&2; exit 2 ;;
esac

cd "$(git rev-parse --show-toplevel)"
# This must be a disposable emulator. Keep the fixture offline and location off;
# no real camera, identities, messages, or transport startup are involved.
adb -s "$scanner_emulator" shell svc wifi disable
adb -s "$scanner_emulator" shell svc data disable
adb -s "$scanner_emulator" shell settings put secure location_mode 0
./gradlew -PreleaseUiTests=true :app:assembleRelease :app:assembleReleaseAndroidTest
adb -s "$scanner_emulator" install -r "app/build/outputs/apk/release/app-${scanner_abi}-release.apk"
adb -s "$scanner_emulator" install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk

scanner_evidence="$(mktemp -d "${TMPDIR:-/tmp}/scanner-release.XXXXXX")"
chmod 700 "$scanner_evidence"
adb -s "$scanner_emulator" shell am instrument -w -r \
    com.bitchat.droid.test/com.bitchat.android.ui.ReleaseScannerTestRunner \
    > "$scanner_evidence/result.txt"
cat "$scanner_evidence/result.txt"
# adb itself exits successfully even when instrumentation tests fail.
grep -q '^INSTRUMENTATION_RESULT: stream=Tests run: 2, failures: 0' "$scanner_evidence/result.txt"
grep -q '^INSTRUMENTATION_CODE: -1' "$scanner_evidence/result.txt"
echo "Release scanner creation, synthetic QR decoding, and frame cleanup passed."
