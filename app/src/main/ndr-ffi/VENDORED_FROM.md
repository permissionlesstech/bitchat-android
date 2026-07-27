# Android NDR FFI provenance

The Android bindings are generated from the pinned `vendor/iris-chat-rs`
submodule rather than from checked-in native libraries.

- Source repository: `https://github.com/irislib/iris-chat-rs.git`
- Source ref: `codex/bitchat-ffi-hardening`
- Source commit: `095e70489345df4d92dded686902f3dccb54cc45`
- Upstream base: `33f7732bbd300ed62fdf5bcf9da0a176efa7ff8c`
- Crate: `protocol-ffi` (`iris-chat-protocol-ffi`, library `ndr_ffi`)
- Protocol FFI version: `0.1.0`
- `nostr-double-ratchet`: `0.0.164` (locked by `protocol-ffi/Cargo.lock`)
- `nostr-double-ratchet-pairwise-codec`: `0.0.164` (locked by
  `protocol-ffi/Cargo.lock`)
- Rust toolchain: `1.95.0`
- `cargo-ndk`: `4.1.2`
- Android NDK: `28.2.13676358`

Run `app/src/main/ndr-ffi/build-android.sh` after initializing submodules. The
script uses Cargo's checked-in lockfiles, builds all four Android ABIs, and
regenerates `app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt` with UniFFI's
Android cleaner configuration.

The generated `libndr_ffi.so` files are intentionally ignored. CI and release
jobs build them from the pinned source before Gradle runs.

## Rollout sequencing

This source refresh does not implement or claim completion of the separate
private-envelope kind-1402 migration. Double-ratchet rollout remains on hold
until that protocol change has its own linked, reviewed Android implementation.

`BuildConfig.NDR_ROLLOUT_ENABLED` is therefore hard-coded to `false`.
Production builds do not advertise capability bit 11, configure or bootstrap
the FFI runtime, accept inbound NDR traffic, or send NDR relay/OOB traffic.
Account messages continue to use the existing Nostr gift-wrap path. Unit tests
use a debug-only override to exercise the dark implementation. Enabling the
gate requires the kind-1402 work to be linked and reviewed first; once enabled,
OOB payload type `0x22` is additionally restricted to an authenticated Noise
session with a mutual favorite that proves capability bit 11.
