# Android ndr-ffi provenance

Vendored artifacts:

- `app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt`
- `app/src/main/jniLibs/arm64-v8a/libndr_ffi.so`
- `app/src/main/jniLibs/armeabi-v7a/libndr_ffi.so`
- `app/src/main/jniLibs/x86/libndr_ffi.so`
- `app/src/main/jniLibs/x86_64/libndr_ffi.so`

Source:

- Repository: `https://github.com/mmalmi/nostr-double-ratchet.git`
- Crate: `rust/crates/ndr-ffi`
- Version: `v0.0.100`
- Source revision: `v0.0.100-1-gb8f0cc2`
- Commit: `b8f0cc2ef9dd651d5eb4a6d9e1236532c9b414a4`
- Android build script: `scripts/mobile/build-android.sh`
- Android NDK used for the vendored refresh: `28.2.13676358`
- Release builds strip non-runtime symbol tables with the NDK `llvm-strip --strip-unneeded` tool.

Refresh procedure:

1. From the source repository, check out the recorded commit.
2. Run `ANDROID_NDK_HOME=/path/to/android-ndk NDK_HOME=/path/to/android-ndk scripts/mobile/build-android.sh --release`.
3. Copy `rust/target/android/jniLibs/*/libndr_ffi.so` into this module's `app/src/main/jniLibs/`.
4. Copy the generated Kotlin binding from `rust/target/android/bindings/` into `app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt`.

Recorded on `2026-04-26T12:14:44Z`.
