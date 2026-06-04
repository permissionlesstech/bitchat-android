# Android ndr-ffi provenance

Vendored artifacts:

- `app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt`
- `app/src/main/jniLibs/arm64-v8a/libndr_ffi.so`
- `app/src/main/jniLibs/armeabi-v7a/libndr_ffi.so`
- `app/src/main/jniLibs/x86/libndr_ffi.so`
- `app/src/main/jniLibs/x86_64/libndr_ffi.so`

Source:

- Repository: `htree://npub1xdhnr9mrv47kkrn95k6cwecearydeh8e895990n3acntwvmgk2dsdeeycm/iris-chat-rs`
- Crate: `protocol-ffi` (`iris-chat-protocol-ffi`, library `ndr_ffi`)
- Version: `0.1.0`
- Source revision: `423d88cf Retry queued protocol sends from FFI inbound events`
- Commit: `423d88cff10f9ebaab3c8e327ebcb916abb3f029`
- Android build source: `protocol-ffi`
- Android NDK used for the vendored refresh: `28.2.13676358`
- Release builds strip non-runtime symbol tables with the NDK `llvm-strip --strip-unneeded` tool.

Refresh procedure:

1. From the source repository, check out the recorded commit.
2. From `protocol-ffi`, run `ANDROID_NDK_HOME=/path/to/android-ndk NDK_HOME=/path/to/android-ndk cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o /tmp/ndr-jniLibs build --manifest-path protocol-ffi/Cargo.toml --lib --release`.
3. Run `cargo run --manifest-path core/uniffi-bindgen/Cargo.toml -- generate --library /tmp/ndr-jniLibs/arm64-v8a/libndr_ffi.so --language kotlin --out-dir /tmp/ndr-bindings`.
4. Copy `/tmp/ndr-jniLibs/*/libndr_ffi.so` into this module's `app/src/main/jniLibs/`.
5. Strip copied libraries with the NDK `llvm-strip --strip-unneeded`.
6. Copy `/tmp/ndr-bindings/uniffi/ndr_ffi/ndr_ffi.kt` into `app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt`.

Recorded on `2026-06-04T00:31:50Z`.
