# Bitchat Nigeria - Running and Deployment Guide

This guide provides instructions for developers and administrators on how to run and deploy the Bitchat Android app with the new Nigerian Location-Based features.

## 1. Overview of New Features
- **Mandatory Nigeria Location Selection**: Onboarding flow requires State -> Region -> LGA -> Ward -> Constituency selection.
- **Location-Scoped Interactions**: Posts and Reposts can be scoped to specific administrative levels (e.g., Ward-only).
- **Profiling & Scouting**: Authorized users ("Scouts") can create profiles for individuals with psychological, mental, and physical traits.
- **Decentralized Alerts**: Location-tagged alerts that trigger system notifications for users in the affected area.
- **Digital Footprint**: Automatic background recording and compression of movement history.
- **Database Merging**: Group Admins can merge local scouted databases into a central one controlled by a Super Admin.

## 2. Prerequisites
- **Android Studio Koala** or newer.
- **JDK 17** (required for Gradle 8.13+).
- **Android SDK 35** (Target SDK).
- **Physical Android Device** (API 26+) with Bluetooth and GPS capability (BLE mesh and Location features require physical hardware).

## 3. How to Run (Development)
1. **Clone the Repository**:
   ```bash
   git clone <repository-url>
   cd bitchat-android
   ```
2. **Open in Android Studio**:
   Open the root `build.gradle.kts` file.
3. **Sync Gradle**:
   Let Android Studio download dependencies (Room, GMS Location, Jetpack Compose, etc.).
4. **Select Build Variant**:
   By default, `debug` split for all ABIs is available.
5. **Run on Device**:
   Click the "Run" button and select your physical Android device.

## 4. How to Deploy (Production)
1. **Update Versioning**:
   In `app/build.gradle.kts`, increment `versionCode` and update `versionName`.
2. **Configure Signing**:
   Ensure you have a valid upload key and configure it in your `local.properties` or environment variables for the release build.
3. **Generate Release Build**:
   ```bash
   ./gradlew assembleRelease
   ```
   This will generate multiple APKs for different architectures (arm64-v8a, x86_64, etc.) in `app/build/outputs/apk/release/`.
4. **Generate App Bundle (AAB)**:
   ```bash
   ./gradlew bundleRelease
   ```
   This is recommended for Play Store distribution.

## 5. Administrative Configuration
- **Super Admin Role**: To designate a Super Admin, the role must be set in the `SecureIdentityStateManager` (this is currently local-first, but can be synchronized via Nostr events with Admin kind).
- **Nigeria Data**: The administrative hierarchy is stored in `app/src/main/assets/ng.json`. You can update this file to reflect the latest administrative changes in Nigeria.

## 6. Testing
To run the automated tests for the new location and profiling features:
```bash
./gradlew testDebugUnitTest --tests "com.bitchat.android.location.*" --tests "com.bitchat.android.profiling.*"
```

---
*Bitchat Nigeria: Off-grid, Secure, and Locally-Scoped Communication.*
