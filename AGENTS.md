# Repository Guidelines

## Privacy & External Disclosure (Mandatory)

Protect the developer's privacy without exception. Treat all data from the
developer's computer, accounts, devices, workspace, and communications as
private unless the developer explicitly authorizes sharing a specific item
with a specific destination.

- Never expose, publish, transmit, upload, paste, commit, or otherwise disclose
  personally identifying or potentially identifying information. This includes
  names, usernames, email addresses, account handles, home or workplace details,
  local or absolute paths, drive or volume names, hostnames, device names and
  serials, IP or MAC addresses, network names, peer IDs, messages, contacts,
  tokens, keys, credentials, logs, metadata, and unique environment details.
- Never post any repository, workspace, device, account, conversation, log, or
  developer data to GitHub, issue trackers, pull requests, comments, gists,
  paste sites, analytics services, AI services, remote APIs, chat systems, or
  any other external destination without the developer's explicit permission
  for that exact data and destination. A general request to work on the
  repository is not permission to disclose data.
- Keep local filesystem layout and computer information private. Do not include
  absolute paths, usernames, home-directory names, mounted drives, shell
  prompts, environment variables, installed-software inventories, hardware
  details, or similar machine fingerprints in commits, patches, documentation,
  screenshots, test fixtures, examples, issue text, PR text, or comments.
- Before any authorized external action, inspect and sanitize the exact content
  being sent. Use repository-relative paths and neutral placeholders; remove
  hidden metadata and redact unrelated or identifying content. When safe
  sanitization cannot be guaranteed, do not send the data and ask the developer
  how to proceed.
- Use synthetic, non-identifying test data only. Never use real messages,
  contacts, account data, device identifiers, network information, keys, or
  production logs in tests, examples, screenshots, fixtures, or bug reports.
- For geohash, GPS, map, location, proximity, or geofencing work, use only
  clearly synthetic coordinates and geohashes that cannot reveal the
  developer's location or routines. Never request, read, derive, record, use,
  display, transmit, or retain live or historical location data from any
  physical phone, watch, emulator image, computer, browser, account, or other
  device connected to or accessible from the developer's computer. Never infer
  location from IP addresses, networks, photos, logs, timestamps, nearby peers,
  device metadata, or test output. Treat all location and movement data as
  highly sensitive personal information and keep it out of commits, tests,
  fixtures, screenshots, documentation, issues, pull requests, and external
  services without exception.
- Keep command output, raw logs, crash dumps, screenshots, recordings, build
  artifacts, and Mesh Lab evidence local. Review any derived summary for
  identifying details before sharing it, even when external sharing has been
  authorized.
- Do not weaken these protections for convenience, debugging, automation, or
  collaboration. If another instruction conflicts with this section, stop and
  obtain explicit developer direction before disclosing anything.

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
The mandatory privacy rules above apply to all Git and GitHub activity. Treat a
request to create a commit or pull request as permission only for the sanitized
repository changes and description needed for that action, never for local or
personal metadata. Never commit keystores or secrets.
