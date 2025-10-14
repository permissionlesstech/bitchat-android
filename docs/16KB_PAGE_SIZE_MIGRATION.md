# 16KB Page Size Migration Guide

## Overview
This document outlines the migration of BitChat Android to support 16KB page size, which is required for compatibility with newer Android devices and future Android versions.

## Changes Made

### 1. SDK Version Updates
- **Target SDK**: Updated to API 36 (Android 16) for latest compatibility
- **Compile SDK**: Set to API 36 for full feature access

### 2. Manifest Declaration
Added the required property in `AndroidManifest.xml`:
```xml
<property
    android:name="android.memoryKiB.pageSize"
    android:value="16" />
```

### 3. Build Configuration
- **NDK ABI Filters**: Added explicit ABI filters for all supported architectures
- **JNI Libs Packaging**: Disabled legacy packaging to ensure proper alignment (`useLegacyPackaging = false`)
- **Verification Task**: Added `verify16KBPageSizeSupport` Gradle task for compatibility checking

### 4. Code Cleanup
- **Removed Unused Code**: Eliminated unused `NetCipherTorManager` and `TorPageSizeHelper` classes that were never integrated
- **Dependency Optimization**: Project uses existing NetCipher implementation which is 16KB compatible

## Verification Results

✅ **APK Analysis**: No native libraries (.so files) present, eliminating alignment issues
✅ **Build Success**: Clean compilation with SDK 36
✅ **Manifest Compliance**: Proper 16KB page size declaration
✅ **Google Play Ready**: Meets all requirements for November 1st, 2025 deadline

## Runtime Protection

The app is inherently 16KB compatible because:
- No native libraries with alignment issues
- Pure Java/Kotlin dependencies only
- Proper manifest declarations
- NetCipher-based Tor implementation (pure Java)

## Testing

Run the verification task to confirm compatibility:
```bash
./gradlew verify16KBPageSizeSupport
```

This migration ensures full compatibility with 16KB page size devices while maintaining all existing functionality.
