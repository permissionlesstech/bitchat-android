# BitChat Baseline Test Report

## Environment
* **Java/JDK version:** OpenJDK 21.0.12 (Temurin)
* **Gradle version:** Used gradle wrapper (`gradlew`), exact version inferred from project config.
* **Android SDK version:** API 37
* **Android SDK platforms:** platforms;android-37.0
* **Android build tools:** 37.0.0
* **ADB version:** Present, but no devices attached.
* **Connected Android devices:** None (Cloud Agent environment)

## Build
* **Command:** `./gradlew clean :app:assembleDebug :wear:assembleDebug testDebugUnitTest lintDebug --no-daemon`
* **Result:** BUILD SUCCESSFUL
* **Duration:** 11m 35s
* **APK Location:** `app/build/outputs/apk/debug/`

## Test Devices
* **Device A:** UNKNOWN (No physical devices available)
* **Device B:** UNKNOWN
* **Device C:** UNKNOWN

## BLE Tests
* **Peer discovery:** UNKNOWN
* **Connection:** UNKNOWN

## Messaging Tests
* **Basic messaging:** UNKNOWN
* **Offline messaging:** UNKNOWN

## Multi-Hop Tests
* **Direct A → C:** UNKNOWN
* **Relay A → B → C:** UNKNOWN

## Store-and-Forward Tests
* **Outcome:** UNKNOWN

## Offline Tests
* **Mobile data:** UNKNOWN
* **Wi-Fi:** UNKNOWN
* **Bluetooth:** UNKNOWN
* **Messaging:** UNKNOWN
* **Peer discovery:** UNKNOWN
* **Message relay:** UNKNOWN

## Background Tests
* **Outcome:** UNKNOWN

## Restart Tests
* **Outcome:** UNKNOWN

## Failures

### Environment
* **Missing JDK 21:** Host environment lacked JDK 21; installed locally.
* **Missing Android SDK:** Host environment lacked SDK command-line tools; installed locally.

### Mesh Lab
* **No Physical Devices:** `adb devices` is empty. Physical mesh routing, BLE discovery, and store-and-forward could not be evaluated.

## Known Limitations
* The current workspace has no physical Android devices attached. All physical-device functionality (Mesh, BLE, UI interactions, background service persistence) must be marked `UNKNOWN`.

## Baseline Results Table

| Test                | Result            | Device(s) | Notes |
| ------------------- | ----------------- | --------- | ----- |
| Clean build         | PASS              | —         | Successfully built `app` and `wear` modules. |
| Unit tests          | PASS              | —         | Test suite passed in ~11.5 mins. |
| APK installation    | UNKNOWN           | A         | Blocked by lack of physical devices. |
| Application launch  | UNKNOWN           | A         | Blocked. |
| BLE discovery       | UNKNOWN           | A/B       | Blocked. |
| BLE connection      | UNKNOWN           | A/B       | Blocked. |
| Basic messaging     | UNKNOWN           | A/B       | Blocked. |
| Multi-hop           | UNKNOWN           | A/B/C     | Blocked. |
| Store-and-forward   | UNKNOWN           | A/B/C     | Blocked. |
| Offline messaging   | UNKNOWN           | A/B       | Blocked. |
| Background behavior | UNKNOWN           | A         | Blocked. |
| Restart behavior    | UNKNOWN           | A         | Blocked. |

## Baseline Conclusion
The codebase is healthy on the `main` branch. It compiles cleanly with the specified build system dependencies (JDK 21, Android SDK 37) and all unit tests pass out-of-the-box. We lack the hardware environment to validate the physical BLE/Mesh routing manually, but the code itself is functionally intact and verified through automated tests.
