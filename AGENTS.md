# Bitchat Android - Agent Guide

This document provides context, architectural insights, and development standards for AI agents working on the Bitchat Android codebase.

## 1. Project Overview
**Bitchat** is a decentralized, off-grid communication application focused on privacy and censorship resistance. It utilizes mesh networking (primarily Bluetooth LE and Tor/Arti) to enable peer-to-peer messaging without centralized servers.

**Key Technologies:**
- **Language:** Kotlin (JVM Target 1.8)
- **UI Framework:** Jetpack Compose (Material 3)
- **Asynchronous:** Kotlin Coroutines & Flow
- **Networking:** Bluetooth Low Energy (BLE), Tor (Arti Rust bridge), OkHttp
- **Architecture:** MVVM with Clean Architecture principles
- **Build System:** Gradle (Kotlin DSL)

## 2. Architecture & Directory Structure
The application follows a clean architecture pattern, heavily modularized by feature within the `app` module.

**Root Package:** `com.bitchat.android`

| Directory | Purpose |
|-----------|---------|
| `ui/` | **Presentation Layer**: Jetpack Compose screens, themes, and ViewModels. |
| `service/` | **Core Service**: Contains `MeshForegroundService`, managing persistent background connectivity. |
| `mesh/` | **Mesh Networking**: Logic for peer discovery, advertising, and message routing. |
| `protocol/` | **Wire Protocol**: Definitions of messages exchanged between peers. |
| `crypto/` | **Security**: Cryptographic primitives and key management. |
| `noise/` | **Encryption**: Implementation of the Noise Protocol Framework for secure channels. |
| `identity/` | **User Identity**: Management of user profiles and public/private keys. |
| `features/` | **App Features**: Sub-modules for `voice`, `file`, and `media` handling. |
| `nostr/` | **Relay Integration**: Logic for Nostr protocol integration and relay management. |
| `geohash/` | **Location**: Utilities for location-based features and geohashing. |
| `net/` | **Networking**: General network utilities and abstractions. |

## 3. Key Components

### UI Layer (Jetpack Compose)
- **Activity**: Single-Activity architecture (`MainActivity.kt`).
- **Navigation**: Jetpack Compose Navigation.
- **State Management**: `ViewModel` exposing `StateFlow` to Composables.
- **Theme**: Custom theme definitions in `ui/theme`.

### Networking & Connectivity
- **MeshForegroundService**: The critical component that keeps the mesh network alive. It manages the lifecycle of BLE scanning/advertising and other transport layers.
- **BLE Stack**: Located in `mesh/` and `net/`, handles the intricacies of Android Bluetooth interactions.
- **Tor/Arti**: Integrated via JNI (`jniLibs`) to provide anonymous internet routing where available.

## 4. Development Standards

### Code Style
- **Kotlin**: Adhere to official Kotlin coding conventions.
- **Compose**: Use functional components. Hoist state to ViewModels where possible.
- **Coroutines**: Use `suspend` functions for all I/O operations. strictly avoid blocking the main thread.
- **Naming**: Clear, descriptive names. Follow standard Android naming patterns (e.g., `*ViewModel`, `*Repository`, `*Screen`).

### Testing
- **Unit Tests**: Located in `app/src/test/`. Use for business logic, protocols, and utility testing.
- **Instrumented Tests**: Located in `app/src/androidTest/`. Use for UI and permission integration testing.
- **Execution**:
  - Unit: `./gradlew test`
  - Instrumented: `./gradlew connectedAndroidTest`

## 5. Critical Constraints & Gotchas
1.  **Permissions**: The app relies heavily on dangerous runtime permissions (Location, Bluetooth Scan/Connect/Advertise, Audio Recording). Always verify permission handling patterns in `MainActivity` or permission wrappers before adding new hardware features.
2.  **Hardware Dependency**: Features like BLE are difficult to emulate. When writing code for these, focus on robust error handling and defensive programming as hardware behavior can be flaky.
3.  **Background Limits**: Android enforces strict background execution limits. Network operations intended to persist must be tied to the `MeshForegroundService`.

## 6. Common Tasks
- **Build Debug APK**: `./gradlew assembleDebug`
- **Lint Check**: `./gradlew lint`
- **Clean Build**: `./gradlew clean`

---
*Note: This file is intended to assist AI agents in navigating and modifying the codebase efficiently. Always verify context by reading the actual files before making changes.*
