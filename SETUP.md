# BitChat Android - Setup Guide

## Project Overview

**BitChat for Android** is a secure, decentralized, peer-to-peer messaging app that operates over Bluetooth mesh networks. It provides encrypted communication without requiring internet connectivity for local mesh chats.

- **Package Name:** `com.bitchat.droid`
- **Application ID:** `com.bitchat.droid`
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 35 (Android 15)
- **Compile SDK:** 35 (Android 15)
- **Current Version:** 1.7.2 (versionCode 33)
- **Language:** Kotlin 2.2.0
- **UI Framework:** Jetpack Compose with Material Design 3

## Key Features

- ✅ **Cross-Platform Compatible:** 100% protocol compatible with iOS BitChat
- ✅ **Bluetooth Mesh Networking:** Automatic peer discovery and multi-hop message relay
- ✅ **End-to-End Encryption:** Noise Protocol (X25519) + Ed25519 signatures
- ✅ **Channel-Based Messaging:** Topic-based group chats with password protection
- ✅ **Store & Forward:** Messages cached for offline peers
- ✅ **Nostr Integration:** Geohash-based location channels over internet
- ✅ **Tor Support:** Built-in Tor integration via Arti (Rust implementation)
- ✅ **File Sharing:** Images, audio notes, and file transfers
- ✅ **Privacy First:** No accounts, no phone numbers, ephemeral by default

## Architecture Overview

### Core Components

1. **BluetoothMeshService** - Central mesh networking coordinator
   - Manages BLE connections (central + peripheral roles)
   - Handles packet routing and relay logic
   - Coordinates peer discovery and connection management

2. **Security Architecture**
   - **Noise Protocol** (NK pattern) for secure key exchange
   - **Ed25519** for digital signatures
   - **X25519** for ECDH key agreement
   - **AES-256-GCM** for message encryption

3. **Binary Protocol**
   - Version 1: Legacy 2-byte payload length
   - Version 2: 4-byte payload length + Source-Based Routing
   - Packet types: Announce, Message, Leave, Noise Handshake, Noise Encrypted, Fragment, Request Sync, File Transfer

4. **Nostr Protocol** (NIP-01, NIP-17, NIP-42)
   - Geohash-scoped text notes (kind 1, 20000)
   - Private direct messages (kind 14, 13, 1059)
   - Presence events (kind 20001)
   - Proof of Work mining support

5. **Mesh Graph Service**
   - Gossip-based topology discovery
   - Two-way edge verification
   - Source-based route planning
   - Bloom filter deduplication

### Module Structure

```
app/src/main/java/com/bitchat/android/
├── BitchatApplication.kt          # Application initialization
├── MainActivity.kt                # Main activity & permission handling
├── MainViewModel.kt               # Global app state
├── mesh/                          # Bluetooth mesh networking
│   ├── BluetoothMeshService.kt    # Core mesh service
│   ├── BluetoothConnectionManager.kt
│   ├── BluetoothGattClientManager.kt
│   ├── BluetoothGattServerManager.kt
│   ├── PeerManager.kt
│   ├── SecurityManager.kt
│   ├── StoreForwardManager.kt
│   └── MessageHandler.kt
├── protocol/                      # Binary protocol implementation
│   ├── BinaryProtocol.kt          # Packet encoding/decoding
│   └── CompressionUtil.kt         # LZ4 compression
├── noise/                         # Noise Protocol implementation
│   ├── NoiseSession.kt
│   ├── NoiseSessionManager.kt
│   └── NoiseChannelEncryption.kt
├── nostr/                         # Nostr protocol client
│   ├── NostrProtocol.kt
│   ├── NostrEvent.kt
│   ├── NostrIdentity.kt
│   ├── NostrRelayManager.kt
│   └── GeohashMessageHandler.kt
├── crypto/                        # Cryptographic operations
│   └── EncryptionService.kt
├── model/                         # Data models
│   ├── BitchatMessage.kt
│   ├── BitchatFilePacket.kt
│   └── RoutedPacket.kt
├── ui/                            # Jetpack Compose UI
│   ├── ChatScreen.kt
│   ├── ChatViewModel.kt
│   └── theme/
├── geohash/                       # Location-based features
│   ├── Geohash.kt
│   ├── LocationChannelManager.kt
│   └── FusedLocationProvider.kt
├── net/                           # Network layer
│   ├── ArtiTorManager.kt          # Tor integration
│   ├── TorMode.kt
│   └── OkHttpProvider.kt
├── sync/                          # Sync protocol
│   ├── GossipSyncManager.kt
│   ├── GCSFilter.kt
│   └── PacketIdUtil.kt
└── services/                      # Background services
    ├── MeshForegroundService.kt   # Foreground service
    └── MeshGraphService.kt        # Topology discovery
```

## Dependencies

### Core Android
- `androidx.core.ktx:1.16.0`
- `androidx.activity.compose:1.10.1`
- `androidx.appcompat:1.7.1`
- `androidx.lifecycle:*:2.9.1`
- `androidx.navigation.compose:2.9.1`

### UI Framework
- Jetpack Compose BOM `2025.06.01`
- Material Design 3
- `com.google.accompanist:accompanist-permissions:0.37.3`

### Cryptography
- `org.bouncycastle:bcprov-jdk15on:1.70`
- `com.google.crypto.tink:tink-android:1.10.0`

### Networking
- `no.nordicsemi.android:ble:2.6.1` - Bluetooth Low Energy
- `com.squareup.okhttp3:okhttp:4.12.0` - HTTP client
- `org.torproject:tor-android-binary:0.4.4.6` - Tor (legacy)

### Location & Maps
- `com.google.android.gms:play-services-location:21.3.0`

### Media
- `androidx.camera:*:1.5.2` - CameraX for QR scanning
- `com.google.mlkit:barcode-scanning:17.3.0` - ML Kit
- `com.google.zxing:core:3.5.4` - QR code generation

### Storage & Security
- `androidx.security:security-crypto:1.1.0-beta01` - EncryptedSharedPreferences
- `com.google.code.gson:gson:2.13.1` - JSON parsing

### Coroutines
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`

## Build Requirements

### System Requirements

- **Java:** OpenJDK 17 or higher
- **Android SDK:** API 26-35
- **Gradle:** 8.13 (via wrapper)
- **Android Gradle Plugin:** 8.10.1
- **Kotlin:** 2.2.0

### Installation Steps

#### 1. Install Java 17+

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-17-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
```

#### 2. Install Android SDK

Option A: Using Android Studio
```bash
# Install Android Studio
sudo snap install android-studio --classic

# Open Android Studio and install SDKs via SDK Manager:
# - Android SDK Platform-Tools
# - Android SDK Build-Tools 35.0.0
# - Android 15 (API 35)
# - Android 14 (API 34)
# - Android 8.0 (API 26) - Minimum
```

Option B: Command Line Only
```bash
# Download command line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mkdir -p ~/Android/sdk/cmdline-tools/latest
mv cmdline-tools/* ~/Android/sdk/cmdline-tools/latest/

# Set environment variables
export ANDROID_HOME=$HOME/Android/sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
echo 'export ANDROID_HOME=$HOME/Android/sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc

# Accept licenses
yes | sdkmanager --licenses

# Install required SDKs
sdkmanager "platform-tools"
sdkmanager "platforms;android-35"
sdkmanager "platforms;android-34"
sdkmanager "platforms;android-26"
sdkmanager "build-tools;35.0.0"
```

#### 3. Clone Repository

```bash
git clone git@github.com:Satoshi-NaAkokwa/ikorochat-android.git
cd ikorochat-android
```

#### 4. Build Project

```bash
# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Build app bundle for Play Store
./gradlew bundleRelease

# Run tests
./gradlew test
./gradlew connectedAndroidTest
```

#### 5. Install on Device

```bash
# Install debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Install release APK
adb install -r app/build/outputs/apk/release/app-release.apk

# Or use gradle task
./gradlew installDebug
```

## Build Outputs

### Debug Builds

Location: `app/build/outputs/apk/debug/`

- `app-debug.apk` - Universal debug APK (all architectures)
- `app-armeabi-v7a-debug.apk` - ARM 32-bit
- `app-arm64-v8a-debug.apk` - ARM 64-bit (recommended for most devices)
- `app-x86-debug.apk` - x86 emulator
- `app-x86_64-debug.apk` - x86_64 emulator

### Release Builds

Location: `app/build/outputs/apk/release/`

Same APK splits as debug, but with:
- Code minification (ProGuard/R8)
- Resource shrinking
- Optimized bytecode
- Smaller APK size

### App Bundle

Location: `app/build/outputs/bundle/release/`

- `app-release.aab` - Android App Bundle for Google Play Store
- Handles architecture distribution automatically

## Configuration

### Build Variants

The project supports automatic APK splitting based on architecture:

```kotlin
// Splits enabled for assemble tasks, disabled for bundle tasks
val enableSplits = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("assemble", ignoreCase = true) &&
    !taskName.contains("bundle", ignoreCase = true)
}
```

### Signing Configuration

Release builds require a signing configuration. Set up in `app/build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("path/to/keystore.jks")
        storePassword = "your_store_password"
        keyAlias = "your_key_alias"
        keyPassword = "your_key_password"
    }
}
```

**IMPORTANT:** Never commit keystore files or passwords. Use:
- Environment variables
- `keystore.properties` file (git-ignored)
- CI/CD secret management

### Gradle Properties

Key properties in `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.enableJetifier=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

## Permissions

### Required Permissions

The app requires the following permissions:

```xml
<!-- Internet for Nostr relays -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Bluetooth -->
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- Location (required for BLE scanning) -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Notifications -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Foreground services -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Media -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />

<!-- Other -->
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

### Runtime Permissions

The app handles runtime permissions for:
- Bluetooth (API 31+)
- Location
- Camera (QR scanning)
- Microphone (voice notes)
- Notifications (API 33+)

## Testing

### Unit Tests

```bash
./gradlew test
./gradlew testDebugUnitTest
```

### Instrumented Tests

```bash
./gradlew connectedAndroidTest
./gradlew connectedDebugAndroidTest
```

### Lint Checks

```bash
./gradlew lint
./gradlew lintDebug
```

## Troubleshooting

### Build Issues

**Issue:** "JAVA_HOME is not set"
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

**Issue:** "Android SDK not found"
```bash
export ANDROID_HOME=$HOME/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

**Issue:** Gradle daemon out of memory
```bash
./gradlew --stop
./gradlew -Dorg.gradle.jvmargs="-Xmx4g" build
```

### Dependency Issues

**Issue:** "Could not resolve nordic-ble"
- Check Maven Central repository in `settings.gradle.kts`

**Issue:** "Tor native library not found"
- Native libraries are in `app/src/main/jniLibs/` (from arti-custom.aar)

### BLE Issues

**Issue:** BLE not scanning
- Verify location permissions granted
- Check GPS is enabled
- Android 12+ requires `ACCESS_FINE_LOCATION` for BLE

**Issue:** Connections failing
- Ensure both devices have BLE hardware
- Check Bluetooth permissions granted
- Verify devices are discoverable

## Development Workflow

### Recommended Commands

```bash
# Clean build
./gradlew clean

# Build and install debug
./gradlew assembleDebug installDebug

# Run tests
./gradlew test connectedAndroidTest lint

# Generate release build
./gradlew assembleRelease
```

### Android Studio Setup

1. Open project in Android Studio
2. Wait for Gradle sync to complete
3. Run configuration: `app` module
4. Select device or emulator
5. Click Run ▶️

### Debugging

- Enable USB debugging on device
- Use Android Studio Logcat
- Filter by tag: `BluetoothMeshService`, `NostrClient`, `TorManager`

## Security Notes

### Secrets Management

**DO NOT commit:**
- Keystore files (`.jks`, `.keystore`)
- API keys
- Passwords
- Signing certificates

**DO use:**
- Environment variables
- `local.properties` (git-ignored)
- CI/CD secret storage
- EncryptedSharedPreferences for runtime secrets

### Cryptographic Implementation

The app uses:
- **BouncyCastle** for cryptographic primitives
- **Custom Noise Protocol** implementation (NK pattern)
- **Ed25519** for signatures
- **X25519** for key exchange
- **AES-256-GCM** for encryption

### Security Best Practices

1. Never log sensitive data (keys, passwords, plaintext messages)
2. Use `EncryptedSharedPreferences` for storing sensitive preferences
3. Validate all incoming packets before processing
4. Implement proper key rotation
5. Use TLS/SSL for Nostr relay connections (via Tor)

## Google Play Store Submission

### Pre-Submission Checklist

- [ ] Target API 35 (Android 15)
- [ ] Comply with Play Store policies
- [ ] Provide privacy policy URL
- [ ] Complete content rating questionnaire
- [ ] Test on multiple devices
- [ ] Verify all permissions are justified
- [ ] Test in-app purchases (if any)
- [ ] Check for crashes and ANRs

### Upload Steps

```bash
# Generate app bundle
./gradlew bundleRelease

# Upload to Google Play Console
# Location: app/build/outputs/bundle/release/app-release.aab
```

### Store Listing Requirements

- **Full Description:** Use README.md content
- **Short Description:** "Secure P2P messaging over Bluetooth mesh"
- **Screenshots:** At least 2 phone screenshots
- **Icon:** 512x512 PNG
- **Feature Graphic:** 1024x500 PNG
- **Privacy Policy:** Required for apps with location permissions

## CI/CD

### GitHub Actions (Recommended)

```yaml
name: Build
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build with Gradle
        run: ./gradlew build
```

## Additional Resources

- **BitChat Protocol Docs:** `/docs/`
- **iOS Version:** https://github.com/jackjackbits/bitchat
- **Android Developer Docs:** https://developer.android.com
- **Jetpack Compose:** https://developer.android.com/jetpack/compose
- **Nostr Protocol:** https://github.com/nostr-protocol/nips
- **Noise Protocol:** https://noiseprotocol.org/

## Support & Issues

- **Bug Reports:** Create GitHub issue
- **Feature Requests:** GitHub Discussions
- **Security Issues:** Private disclosure
- **Questions:** GitHub Discussions

---

Last Updated: 2025-04-11
Version: 1.7.2 (versionCode 33)