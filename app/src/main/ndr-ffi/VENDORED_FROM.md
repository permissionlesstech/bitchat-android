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
- Version: `v0.0.104`
- Source revision: `v0.0.100-29-g601d88c`
- Commit: `601d88c1172424ba3847827cc91db012bd2ccfbb`
- Android build script: `scripts/mobile/build-android.sh`
- Android NDK used for the vendored refresh: `28.2.13676358`
- Release builds strip non-runtime symbol tables with the NDK `llvm-strip --strip-unneeded` tool.

Refresh procedure:

1. From the source repository, check out the recorded commit.
2. Run `ANDROID_NDK_HOME=/path/to/android-ndk NDK_HOME=/path/to/android-ndk scripts/mobile/build-android.sh --release`.
3. Copy `rust/target/android/jniLibs/*/libndr_ffi.so` into this module's `app/src/main/jniLibs/`.
4. Copy the generated Kotlin binding from `rust/target/android/bindings/` into `app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt`.

Recorded on `2026-04-28T20:49:33Z`.
