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

`core/mesh/` holds the mesh transport stack both clients run: protocol,
Noise/crypto, identity, gossip sync, private conversation storage and voice
frames.

`core/nostr/` holds Nostr relays, gift-wrapped private messages, geohash
channels and location. It never touches the chat session: private messages land
in a `PrivateMessageInbox` the session implements, and HTTP clients come from a
`NostrHttpClients` the app installs, which fails closed so relay traffic cannot
bypass Tor.

`core/chat/` holds the chat session behind the chat screens: `ChatState`, the
managers, private conversations, notifications and QR verification, in the
Activity-retained scope. It takes what is the app's own through ports the app
binds (`NotificationTargets`, `SessionMeshSource`, `WifiAwarePeers`). Screens and
their ViewModels live in the `feature/` modules.

`core/domain/` holds the domain models that Android library modules
need (Gradle forbids a library module depending on an application module).
`:app` and `:wear` depend on both. Where the clients differ, `:core:mesh`
takes the policy from its caller (Bluetooth permissions) or a hook the app
registers (`TransportToggles`), never a same-name class per client. Shared
test doubles, including the JVM `android.util` shims, live in
`core/mesh/src/testFixtures/`.

`core/navigation/` holds the Navigator, the NavDisplay host and the Hilt
module that binds them. Routes live in the `feature/*/api` modules. The `core/`
modules keep the package names they had inside `app/`, so moving a file between
them needs no import changes.

`core/designsystem/` is the base both clients share: palette, motion, peer colors
and the font, on Compose foundation/ui only, with no Material3 and no other
project module. `core/designsystem/phone/` adds the phone's Material3 theme,
generic components and the resources shared between `:app` and the features.

`core/readiness/` reads what the device and the stored onboarding choices say
about readiness (Bluetooth, location services, battery optimization, runtime
permissions, first launch, the skip preferences) through `DeviceReadiness`, and
decides the next onboarding step (`nextStep`). It depends only on `:core:mesh`
and `:core:domain`, so both the app's startup gate and the onboarding feature
build on it.

`core/tor/` holds Arti and the Tor HTTP stack. It depends on no other project
module. It signals route changes through `ArtiTorManager.routeChanges`, which the
app wires into the `NostrHttpClients` port, so relays reset on a route change.

`feature/share`, `feature/settings`, `feature/location`, `feature/chat` and
`feature/onboarding` hold the phone screens, each with an `:api` module for its
routes (and, for chat, `openPrivateChat`; for onboarding, the `OnboardingHost`
port the app binds to its startup gate). A feature registers its Nav3 destinations as Hilt
`@IntoSet EntryProviderInstaller`s. A feature depends only on another feature's
`:api`, never on the feature. An `:api` module depends only on
`:core:navigation` and `:core:domain` among project modules; external Compose
runtime/ui libraries are allowed. `FeatureDependencyRulesTest` enforces this,
that `:core:tor` has no project dependencies and that `:core:readiness` depends
on no feature and not on `:app`. `:app` keeps `MainActivity`, the startup gate
(`AppStartupGate`), the application class, the mesh, Wi-Fi Aware and foreground
service, the DI modules that bind ports, the debug hooks (`ReadinessOverride`
among them) and all instrumented tests. `MainActivity` registers no destinations
inline.

`build-logic/` holds the Gradle convention plugins (`bitchat.android.*`)
applied by library modules. It is an included build; it does not appear in
`settings.gradle.kts` as a project. A convention plugin must pin every build
setting `app/build.gradle.kts` pins, `buildToolsVersion` included.

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
