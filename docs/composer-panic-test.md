# Public composer panic regression

These nine app-screen tests exercise the real application, Activity and panic-wipe confirmation. They check that confirming a wipe clears the old draft and suggestions, and that stale mention selections cannot restore text.

## 1. Prepare a disposable emulator environment

These tests trigger destructive cleanup. Use a fresh disposable emulator with no user data and block outbound network access. Do not use a personal device. The supplied runner creates its own emulator and refuses physical devices.

Before starting, have:

- An Android SDK with a rootable emulator system image already installed.
- `ANDROID_HOME` set to the local Android SDK directory.
- JDK 21 available for `avdmanager`.
- Matching app and test APKs, built as described below.
- An unused results directory and available emulator/ADB ports.

The example command uses the API 35 Google APIs ARM image. The runner does not build APKs or download dependencies.

## 2. Build the app and test APKs

Run from the repository root:

```sh
./gradlew -PcomposerUiTests=true :app:assembleDebug :app:assembleDebugAndroidTest
```

This property includes the composer test sources and selects the dedicated test installation, `com.bitchat.droid.composerTest`. Normal debug builds keep their usual application ID and runner. Do not combine `composerUiTests` with `releaseUiTests`.

## 3. Run all nine app-screen tests

After the build, run this command from the repository root. The results directory must not already exist.

```sh
python3 tools/composer/run_regression.py \
  --sdk "$ANDROID_HOME" \
  --image 'system-images;android-35;google_apis;arm64-v8a' \
  --apk app/build/outputs/apk/debug/app-arm64-v8a-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --output composer-regression-results
```

For another emulator ABI, select a matching installed system image and app APK. Use the test APK built with that app.

The default emulator port is `5558`, and the private ADB server port is `5088`. If either is occupied, supply unused values with `--port` and `--adb-port`. The emulator port must be even, and the following port must also be available. The runner checks port availability before starting.

### What the runner does

- Creates a temporary Android Virtual Device (AVD) and deletes it during cleanup.
- Installs IPv4 and IPv6 rules that reject external network traffic before installing the app. It checks that each rejection rule is first in its OUTPUT chain before installation and after the tests. These endpoint checks do not continuously monitor rule changes or traffic.
- Turns off device Wi-Fi, mobile data and Bluetooth.
- Enables the disposable emulator's device-wide location setting for onboarding and grants the test app Bluetooth and location permissions. The test app's own location feature remains disabled.
- Keeps the screen awake, dismisses the keyguard and stops the dedicated test app before starting instrumentation.
- Runs all nine tests and requires a successful nine-test result.
- Retains emulator and instrumentation logs locally. Failure summaries include a test identifier, recognized framework exception type and test-source location when available; they omit raw assertion values and device logs.

The test configuration also disables the app's BLE and Wi-Fi Aware features. Those app settings alone do not isolate network traffic. Use the supplied runner's setup instead of running instrumentation alone on an unprepared device. The dedicated package keeps normal app installations separate.

## What the app-screen tests cover

- An empty initial input; Cancel preserving a synthetic draft; confirmed wipe completing and clearing that draft; and typing afterward.
- Back dismissing confirmation without changing selected multiline Unicode text.
- Empty and repeated wipes.
- Clearing command and mention suggestions, and using fresh mentions afterward.
- Clicking mention rows during their closing animation.
- Invoking a captured mention callback immediately after confirmation, before redraw. This checks a controlled callback invocation, not the timing of an ordinary screen tap.
- Clearing the confirmed draft when conversation storage cleanup fails.
- Confirming a second wipe while the first waits for storage, then checking completion after releasing the queue.
- Using the actual Send button before and after wipe, with messages captured by a local test transport.

The mention fixture inserts an unverified synthetic nickname into the local peer map; no real peer is involved.

## Check that the tests detect missing protections

Remove one protection at a time, run the affected tests, then restore it and rerun:

1. Remove the input reset from the real panic-confirmation callback. The main regression test should fail its empty-input assertion after wipe completion.
2. Remove the suggestion-clear call. The command and mention popup checks should fail.
3. In `ChatScreen`, remove the current-state check of `viewModel.showMentionSuggestions.value` and membership in `viewModel.mentionSuggestions.value` from `onMentionSuggestionClick`, leaving its mention-selection body in place. In `exitingMentionRowsCannotRefillInput`, the empty-input assertion after invoking the captured callback before redraw should fail. Keep the separate `mentionPickerVisible` guard unchanged for this check.

Restore every protection before running the full suite.

## 4. Run the test-script checks

From the repository root, run the eleven Python checks:

```sh
python3 tools/composer/test_run_regression.py
```

These check result parsing, failure reporting, cleanup error handling and firewall-rule order. They do not run the nine app-screen tests and do not require an emulator.

## Testing limits

- This suite is opt-in. Default JVM tests and CI commands do not run it.
- The send test checks local acceptance at a capturing transport. It does not establish radio transmission or delivery to another device.
- The tests do not establish storage erasure or cancellation of outgoing transfers.
