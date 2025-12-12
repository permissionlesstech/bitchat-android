# Arti Android Build Tools

This directory contains the build scripts and source files for compiling the custom Arti (Tor in Rust) library for Android.

## Overview

bitchat-android uses a custom-built Arti library instead of Guardian Project's outdated `arti-mobile-ex` AAR. This provides:

- **Smaller APK size**: ~11MB total vs ~140MB with Guardian Project AAR (28x reduction)
- **Latest Arti version**: Currently v1.7.0 with pure Rust TLS (rustls)
- **16KB page size support**: Required for Google Play (Nov 2025)
- **Full transparency**: Build from official Arti source + our JNI wrapper

## Quick Start

The pre-built `.so` files are committed to the repo, so you don't need to build unless you want to:

1. **Verify the binaries** match the source
2. **Update to a new Arti version**
3. **Modify the JNI wrapper**

## Directory Structure

```
tools/arti-build/
├── README.md           # This file
├── build-arti.sh       # Main build script (clones Arti, builds .so files)
├── ARTI_VERSION        # Pinned Arti version tag (e.g., arti-v1.7.0)
├── Cargo.toml          # Rust package configuration
├── src/
│   └── lib.rs          # JNI wrapper (Rust -> Kotlin/Java bridge)
└── .arti-source/       # [GITIGNORED] Cloned official Arti repo

app/src/main/jniLibs/   # [COMMITTED] Pre-built native libraries
├── arm64-v8a/
│   └── libarti_android.so  (~5.3MB)
└── x86_64/
    └── libarti_android.so  (~6.2MB)
```

## Rebuilding from Source

### Prerequisites

1. **Rust toolchain** with Android targets:
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   rustup target add aarch64-linux-android x86_64-linux-android
   ```

2. **cargo-ndk** for Android cross-compilation:
   ```bash
   cargo install cargo-ndk
   ```

3. **Android NDK 25+** (for 16KB page size support):
   ```bash
   # Via Android Studio SDK Manager, or:
   $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager "ndk;27.0.12077973"

   # Set environment variable
   export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.0.12077973"
   ```

### Build Commands

```bash
cd tools/arti-build

# Build both architectures (arm64 + x86_64 for emulator)
./build-arti.sh

# Build ARM64 only (smaller, for production releases)
./build-arti.sh --release

# Clean rebuild (re-clone Arti source)
./build-arti.sh --clean
```

The script will:
1. Clone official Arti from https://gitlab.torproject.org/tpo/core/arti
2. Checkout the version specified in `ARTI_VERSION`
3. Copy our JNI wrapper into the cloned repo
4. Build with `cargo ndk`
5. Copy `.so` files to `app/src/main/jniLibs/`

### Verification

After building, verify the libraries:

```bash
# Check file sizes
ls -lh ../../app/src/main/jniLibs/*/libarti_android.so

# Verify JNI symbols are exported
nm -gU ../../app/src/main/jniLibs/arm64-v8a/libarti_android.so | grep Java_org_torproject

# Verify 16KB page alignment
readelf -l ../../app/src/main/jniLibs/arm64-v8a/libarti_android.so | grep LOAD
# Look for: Align 0x4000 (16KB)
```

## Updating Arti Version

1. **Check available versions**:
   ```bash
   git ls-remote --tags https://gitlab.torproject.org/tpo/core/arti.git | grep arti-v
   ```

2. **Update the version file**:
   ```bash
   echo "arti-v1.8.0" > ARTI_VERSION
   ```

3. **Rebuild from scratch**:
   ```bash
   ./build-arti.sh --clean
   ```

4. **Test the build**:
   ```bash
   cd ../..
   ./gradlew clean assembleDebug
   ./gradlew installDebug
   # Enable Tor in app and verify it works
   ```

5. **Commit the new libraries**:
   ```bash
   git add app/src/main/jniLibs/ tools/arti-build/ARTI_VERSION
   git commit -m "chore: update Arti to v1.8.0"
   ```

## JNI Wrapper Architecture

The `src/lib.rs` file implements a JNI bridge between Kotlin and Rust:

```
Kotlin (ArtiNative.kt)
    ↓ JNI
Rust (lib.rs)
    ↓
Arti Client (TorClient)
    ↓
SOCKS5 Proxy (localhost:9060)
```

**Exported JNI Functions**:
- `getVersion()` - Returns Arti version string
- `setLogCallback(callback)` - Registers log listener for bootstrap progress
- `initialize(dataDir)` - Creates Tokio runtime and TorClient
- `startSocksProxy(port)` - Starts SOCKS5 proxy on specified port
- `stop()` - Stops SOCKS proxy (TorClient is reused)

**Key Design Decisions**:
- Global `TorClient` persists across stop/start cycles (fixes Nov 2024 toggle bug)
- Tokio runtime created once and never destroyed
- Log messages bridged to Java via `GlobalRef` callback

## Feature Configuration

Edit `Cargo.toml` to customize Arti features:

```toml
[dependencies]
arti-client = {
    path = "../crates/arti-client",
    default-features = false,
    features = [
        "tokio",                  # Required: async runtime
        "rustls",                 # Required: pure Rust TLS (no OpenSSL)
        "compression",            # Optional: directory compression
        "bridge-client",          # Optional: Tor bridge support
        "onion-service-client",   # Optional: .onion site support
        "static-sqlite"           # Required: bundled SQLite
    ]
}
```

## Size Comparison

| Configuration | arm64-v8a | x86_64 | Total | APK Size |
|---------------|-----------|--------|-------|----------|
| Guardian Project AAR | - | - | ~140 MB | ~150 MB |
| Custom (both arch) | 5.3 MB | 6.2 MB | 11.5 MB | ~15 MB |
| Custom (ARM-only) | 5.3 MB | - | 5.3 MB | ~10 MB |

**28x size reduction** vs Guardian Project implementation.

## Troubleshooting

### "cargo-ndk not found"
```bash
cargo install cargo-ndk
```

### "Android NDK not found"
```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.0.12077973"
```

### "Rust target not installed"
```bash
rustup target add aarch64-linux-android x86_64-linux-android
```

### "Version not found"
Check available versions:
```bash
git ls-remote --tags https://gitlab.torproject.org/tpo/core/arti.git | grep arti-v | tail -10
```

### Library too large
1. Ensure building with `--release` flag
2. Verify `strip = true` in `Cargo.toml` `[profile.release]`
3. Consider removing optional features

## References

- [Arti Documentation](https://gitlab.torproject.org/tpo/core/arti/-/blob/main/doc/README.md)
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk)
- [Android NDK Guide](https://developer.android.com/ndk/guides)
- [Google Play 16KB Page Size](https://developer.android.com/guide/practices/page-sizes)
