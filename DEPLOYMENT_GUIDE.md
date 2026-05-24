# Bitchat Nigeria: Deployment & Operations Guide

This document provides a comprehensive guide for deploying and maintaining the Bitchat Nigeria communication system.

## 1. Technical Prerequisites
- **Build Environment**: JDK 17, Android SDK 35, Gradle 8.13+.
- **Hardware**: Android devices running API 26 (Android 8.0) or higher. Physical devices are required for Bluetooth Mesh and GPS functionality.
- **Relays**: While the app works off-grid via mesh, for internet-based features, you should have access to Nostr relays. The app is pre-configured with standard relays, but for Nigeria, setting up local geo-relays is recommended.

## 2. Preparing for Release

### 2.1 Versioning
Update the versioning in `app/build.gradle.kts`:
```kotlin
defaultConfig {
    applicationId = "com.bitchat.droid.nigeria" // Unique ID for Nigeria variant
    versionCode = 34 // Increment for every new release
    versionName = "1.8.0" // Semantic versioning
}
```

### 2.2 Updating Administrative Data
If the Nigerian administrative hierarchy (States, LGAs, Wards, or Constituencies) changes:
1. Edit `app/src/main/assets/ng.json`.
2. The app uses an optimized **SQLite FTS (Full-Text Search)** index. On first launch after an update, the `AdminDataSeeder` will automatically detect changes and re-index the data for instant searching.

## 3. Building and Signing

### 3.1 Generating a Keystore
If you don't have a production signing key, generate one:
```bash
keytool -genkey -v -keystore bitchat-nigeria-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias nigeria-key
```

### 3.2 Build Commands
From the project root, run:

- **Production App Bundle (Google Play)**:
  ```bash
  ./gradlew bundleRelease
  ```
- **Universal APK (Direct Download/Offline Sharing)**:
  ```bash
  ./gradlew assembleRelease
  ```
The artifacts will be located in `app/build/outputs/`.

## 4. Post-Deployment Configuration

### 4.1 Designating User Roles
The system supports four roles: `STANDARD`, `PROFILER`, `GROUP_ADMIN`, and `SUPER_ADMIN`.
- Roles are initially stored in the device's `SecureIdentityStateManager`.
- For large-scale deployments, the Super Admin can "Vouch" for other users via the **Decentralized Vouching System**, elevating their trust status within the mesh.

### 4.2 Database Merging
Group Admins can merge their locally scouted databases into the central Super Admin database:
1. Ensure the Group Admin and Super Admin are connected via the mesh or a common Nostr relay.
2. The Group Admin initiates a "Merge Request" from their dashboard.
3. The data is transmitted as a custom Nostr event (Kind 30005) with version-based conflict resolution.

## 5. Performance Tuning
- **Image Compression**: All images posted to location-scoped channels are automatically resized to 1024px to ensure reliability over low-bandwidth BLE mesh.
- **Spatial Compression**: User "Digital Footprints" use spatial compression (recording only significant movements >10m) to minimize storage and battery impact.
- **Battery Optimization**: Users should be prompted during onboarding to disable battery optimization for the Bitchat app to ensure reliable background mesh connectivity.

## 6. Troubleshooting
- **Location Issues**: Ensure GPS is enabled. The app uses GMS Location for high-accuracy admin-level detection.
- **Mesh Connectivity**: BLE mesh range is typically 10-30 meters. In dense areas, the "Store-and-Forward" mechanism will automatically propagate messages.
- **Sync Failures**: If databases fail to merge, check the "Version" field in the profile metadata; the system follows a "higher version wins" logic.

---
*For development support, refer to the technical `README.md` and `README_NIGERIA.md` files.*
