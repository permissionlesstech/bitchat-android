# Repository Guidelines

## Project Structure & Architecture

`app/` is the Kotlin/Compose phone client; its main packages cover UI, services,
BLE/Wi-Fi mesh, protocol, Noise/crypto, identity, Nostr, geohash, and media.
`wear/` is the Wear OS client. Module resources live in `src/main/`
and JVM tests in `src/test/`. Specifications are in `docs/`; tooling is in
`tools/`.

`app/` is the source of truth for shared mesh/protocol code.
`syncSharedAppSources` generates `wear/build/sharedSrc` from the include list
in `wear/build.gradle.kts`. Extend that list; never copy shared
Kotlin into `wear/src/` or edit generated `build/` content.

## Build, Test & Development Commands

Use JDK 21 and the Android SDK versions in `gradle/libs.versions.toml`.

```sh
./gradlew :app:assembleDebug :wear:assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew connectedAndroidTest
./gradlew clientRewriteContractTest
tools/arti-build/verify-checksums.sh
```

CI runs `testDebugUnitTest lintDebug`; instrumented tests require a device.
Follow `docs/reproducible-builds.md` and `docs/maintainer-release-guide.md` for
dependency and release work.

## Coding Style & Naming

Use official Kotlin style with four-space indentation. Classes and Composables
use `PascalCase`; functions and properties use `camelCase`; constants use
`UPPER_SNAKE_CASE`. Hoist Compose state, expose immutable `StateFlow`, use
structured coroutines and suspend I/O, and never block the main thread.

Protocol and security changes must remain fail-closed and cross-client
compatible. Update the relevant specification and golden-vector tests.

## Testing & Physical Mesh Lab

Tests use JUnit 4, Robolectric, Mockito, and coroutine test utilities. Name files
`*Test.kt` by observable behavior. Avoid arbitrary sleeps, public
relays, live user data, and nondeterministic completion. See
`docs/testing-conventions.md`.

Changes affecting discovery, routing, transports, Noise/crypto, identity,
foreground-service power, messaging, transfers, packets, or fragmentation
require Mesh Lab validation on physical devices. Debug-only hooks live in
`app/src/debug/` and `wear/src/debug/`; never move them into release sources.

```sh
python3 tools/release_gate/mesh_lab.py setup \
  --serial-a <device-a> --serial-b <device-b> --apk <debug-apk>
python3 tools/release_gate/mesh_lab.py scenario all \
  --serial-a <device-a> --serial-b <device-b> --out /tmp/mesh-evidence
```

For phone-to-watch interop, replace `--serial-b` with `--serial-watch` and add
`--watch-apk`. Keep devices unlocked and awake. Follow the Mesh Lab appendix in
`docs/release-gate-runbook.md`; raw evidence and logcat must remain local.

## Commits, Pull Requests & Privacy

Use short imperative subjects (`Fix stale peer lifecycle cleanup`) or scoped
Conventional Commit subjects (`fix(wifi-aware): ...`). PRs must explain risk,
link the issue, report tests, and include before/after screenshots for visible
phone or watch changes. Do not publish raw device logs.

Use `gh` for GitHub operations. Never override Git author or committer identity.
Never put usernames, local paths, device identifiers, network addresses, peer
IDs, messages, keys, or other PII in GitHub content or Git history. Never commit
keystores or secrets.
