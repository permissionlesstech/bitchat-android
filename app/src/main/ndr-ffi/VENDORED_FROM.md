# Android NDR FFI provenance

The Android bindings are generated from the pinned
`vendor/nostr-double-ratchet` submodule.

- Source repository: `https://github.com/irislib/nostr-double-ratchet.git`
- Source commit: `26006f4f1487117e486b35da4854987a7fde87f5`
- Upstream base: `master` at `26006f4f1487117e486b35da4854987a7fde87f5`
- Pairwise FFI crate: `0.0.168`; core crate: `0.0.171`; locked `nostr`: `0.44.8`
- Crate: `ndr-pairwise-ffi` (library `ndr_ffi`)
- Runtime: durable single-identity pairwise sessions only; no AppKeys,
  linked-device, sibling-sync, or group runtime
- Rust toolchain: `1.95.0`
- `cargo-ndk`: `4.1.2`
- Android NDK: `27.3.13750724` (shared with the Arti native builder)

Run `app/src/main/ndr-ffi/build-android.sh` after initializing submodules. The
script uses Cargo's checked-in lockfiles, builds all four Android ABIs, and
regenerates `app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt` with UniFFI's
Android cleaner configuration.

The generated `libndr_ffi.so` files are intentionally ignored. Debug APK jobs
build them from source and check the generated binding; JVM test/lint jobs do
not build native libraries. Canonical releases export the committed NDR gitlink
and compile it inside the existing pinned `tools/arti-build/Dockerfile` container
before the Gradle container runs. Ignored host binaries are never imported.
Both native builds use `tools/arti-build/TOOLCHAIN.env`; the script checks exact
Rust, Cargo, cargo-ndk, and NDK versions and validates 16 KiB ELF alignment.

This update includes invite identity binding and the NIP-44 resource
exhaustion fix from the locked `nostr` dependency. Active handshake bindings
also survive replay-window eviction, preventing stalled reception after the
bounded replay cache fills.

## Rollout sequencing

Android and Apple enable the pairwise protocol through capability negotiation.
Capability bit 11 and Noise payload `0x22` are accepted only for an
authenticated Noise peer with an exact current Nostr identity binding and a
mutual favorite advertising the same capability. The independent kind-1402
fallback-envelope migration can land before or after this work.

Privacy-v2 uses an acceptance-specific responder key, excluding public invite
keys from private relay authors and subscriptions. Pairwise runtime `0.0.169`
quarantines pre-upgrade sessions and queued ciphertext. Both peers must upgrade
and complete a fresh authenticated handshake; durable downgrade pins remain.
