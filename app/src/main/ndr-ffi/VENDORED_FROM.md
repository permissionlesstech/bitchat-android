# Android NDR FFI provenance

The Android bindings are generated from the pinned
`vendor/nostr-double-ratchet` submodule.

- Source repository: `https://github.com/irislib/nostr-double-ratchet.git`
- Source commit: `0fe8caf2d4e24e2030ffae195597a2764613a659`
- Upstream base: `master` at `c93f76a2b947f4288d2c7bcbecabe70ce197da5f`
- Crate: `ndr-pairwise-ffi` (library `ndr_ffi`)
- Runtime: durable single-identity pairwise sessions only; no AppKeys,
  linked-device, sibling-sync, or group runtime
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

Rollout remains disabled until iOS and Android enable the pairwise protocol
together. Capability bit 11 and Noise payload `0x22` are accepted only for an
authenticated Noise peer with an exact current Nostr identity binding and a
mutual favorite advertising the same capability. The independent kind-1402
fallback-envelope migration can land before or after this work.
