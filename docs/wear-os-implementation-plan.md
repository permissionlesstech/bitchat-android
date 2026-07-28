# Bitchat for Pixel Watch — Implementation Plan

> **Status tracker**: each milestone carries a status (`pending` / `in-progress` / `done`) and a
> checklist. A milestone may only be started when the previous milestone's success criteria pass.
> Update statuses in this file as work progresses.

| Milestone | Title | Status |
|-----------|-------|--------|
| M0 | Scaffolding & plan document | done |
| M1 | Shared core compiles on Wear | done |
| M2 | BLE transport & background service on watch | pending |
| M3 | Global chat | pending |
| M4 | Noise DMs & people screen | pending |
| M5 | File/image receive & display — **DEFERRED** (post-M7, later day) | deferred |
| M6 | ADB test hook & mesh_lab interop | pending |
| M7 | Polish & final design pass | pending |

---

## 1. Context for a fresh coding agent

- **Reference app**: this repository (`bitchat-android`) is a fully working, decentralized BLE mesh
  chat client. Single Gradle module `:app`, root package `com.bitchat.android`, applicationId
  `com.bitchat.droid`. See `AGENTS.md` for the full architecture overview.
- **Goal**: a new `:wear` Gradle module (applicationId `com.bitchat.watch`) — a standalone Wear OS
  app for the Pixel Watch. **Bluetooth mesh only**: global chat, Noise-encrypted direct messages,
  and receiving/displaying files & images. It must be a fully interoperable bitchat client: scan,
  advertise, connect, relay, handshake, and exchange messages with the Android (and iOS) apps.
- **Explicitly out of scope**: no internet features (no Nostr, no Tor/Arti, no relays), no GPS /
  geohash / location channels, no Wi-Fi Aware, no hotspot/APK sharing, no voice notes recording.
  The watch manifest must not even declare `INTERNET` or location permissions.
- **Hard constraint**: **zero modifications to `:app` production code.** The only allowed changes
  to shared repo files are: `settings.gradle.kts` (add `include(":wear")`), entries in
  `gradle/libs.versions.toml` (new wear dependencies only), the new `wear/` directory, and docs.
  All shared Kotlin code is consumed by the `:wear` module *in place* via Gradle source sets —
  files are never moved, copied, or edited.

## 2. Code reuse strategy (shared source sets)

Wear OS is Android. `android.util.Log`, `android.bluetooth.*`, `EncryptedSharedPreferences`
(androidx.security), BouncyCastle, and coroutines all work on the watch, so the vast majority of
the bitchat protocol stack compiles unmodified.

In `wear/build.gradle.kts`:

```kotlin
sourceSets["main"].java.srcDir("../app/src/main/java") // with include filters (see below)
```

**Include** (iteratively refined by fixing compile errors — the include list lives in
`wear/build.gradle.kts` with comments):

- `protocol/**` — wire format, `BinaryProtocol`, `CompressionUtil`, `MessagePadding`
- `model/**` — `BitchatMessage`, `BitchatFilePacket`, `FragmentPayload`, `NoiseEncrypted`,
  `IdentityAnnouncement`, `RoutedPacket`
- `noise/**` — `NoiseSession`, `NoiseSessionManager`, `NoiseEncryptionService`,
  `NoiseChannelEncryption`, vendored pure-Java `noise/southernstorm/**`
- `crypto/**` — `EncryptionService`
- `identity/**` — `SecureIdentityStateManager`
- `mesh/**` — BLE stack (`BluetoothConnectionManager`, GATT server/client managers, broadcaster,
  tracker, permission manager), `FragmentManager`, `SecurityManager`, `PacketProcessor`,
  `MessageHandler`, `PeerManager`, `StoreForwardManager`, `MeshTransport`, `MeshService`,
  `TransferProgressManager`, `PrivateMediaTransfer`, `PowerManager`
- `services/AppStateStore.kt` — process-wide state store
- `util/AppConstants.kt` — shared constants (GATT UUIDs, fragmentation sizes)
- Small transitive deps the compiler reveals (known: `ui/debug/DebugSettingsManager.kt` is
  referenced from the mesh layer — include the file, not the package)

**Exclude**: `ui/**` (except forced single-file includes), `onboarding/**`, `nostr/**`, `net/**`,
`geohash/**`, `wifi-aware/**`, `hotspot/**`, `features/voice/**`, `service/MeshForegroundService.kt`
(wear gets its own service), `BitchatApplication.kt`, `MainActivity.kt`.

**Tests**: the app's own unit tests for shared packages (`protocol`, `noise`, `crypto`, `mesh`)
are wired into the `:wear` test source set the same way (srcDir + includes), so shared behavior is
continuously verified on both modules.

**Resources**: font files cannot be selectively shared via srcDir cleanly — copy the 4 Geist Mono
font files (`app/src/main/res/font/geist_mono_*`) into `wear/src/main/res/font/`. Theme/palette/
peer-color logic is re-created as wear-owned files mirroring `ui/theme/` values exactly.

## 3. Watch UX design

Wear Compose Material3 (round-screen safe by default):

- **Screens**: Chat (global timeline) → People (connected peers w/ RSSI, unread badges) →
  DM conversation. Edge-swipe back, `TimeText` scaffold, rotary crown scrolling.
- **Visual identity** (mirrors `ui/theme/` exactly): black background `#000000`, green primary
  `#32D74B`, error `#FF453A`, orange accent for self/mentions, djb2-hash stable peer colors
  (`PeerColors.kt` algorithm), Geist Mono typography, `BitchatMotion` timing tokens
  (120/180/240 ms) for all animations.
- **Input**: text field using the Pixel Watch Gboard IME, plus voice dictation via
  `RecognizerIntent`. Haptic feedback on incoming messages.
- **Background**: wear-owned foreground service (type `connectedDevice`) keeps scan/advertise
  alive; shared `PowerManager` provides duty-cycling.

## 4. Hardware & test environment

- Pixel Watch connected via ADB (target device for all milestones; screencaps via
  `adb exec-out screencap`).
- Two phones running the Android bitchat app, also on ADB, for interop testing (used heavily from
  M6; manual interop checks from M2 onward).
- Design verification: at every UI milestone, take ADB screencaps of every screen and review them
  for round-screen clipping, element visibility, contrast, and touch-target size. A milestone does
  not pass until its screencap set is approved.

## 5. Risks & notes

- `BluetoothMeshService` (legacy monolith) vs `MeshCore` — prefer wiring the shared components
  directly (MeshCore-style composition) in the wear service.
- `mesh/` references `ui/debug/DebugSettingsManager` — include that single file; do not pull in
  the debug UI sheet.
- Wear BLE MTUs are small; the shared fragmentation layer (469-byte fragments) already handles
  this.
- `EncryptedSharedPreferences` (androidx.security-crypto) works on Wear OS — identity persistence
  is reused as-is.
- Watch has no camera/gallery: file transfer is **receive + display only** (confirmed decision).

---

## Milestones

### M0 — Scaffolding & plan document

- [x] Write this plan to `docs/wear-os-implementation-plan.md`
- [x] Create `:wear` module: `wear/build.gradle.kts`, manifest
  (`<uses-feature android:name="android.hardware.type.watch"/>`, standalone, BT permissions,
  **no INTERNET/location**), `MainActivity` with hello-world screen using the ported theme
- [x] Add Wear Compose dependencies to `gradle/libs.versions.toml`; `include(":wear")` in
  `settings.gradle.kts`
- [x] Build, install, and launch on the physical Pixel Watch via ADB; take first screencap

**Success criteria**: `./gradlew :wear:assembleDebug` green; app launches on the watch;
`git diff --name-only` shows no changes under `app/src/`.
**Result**: PASSED — installed on Pixel Watch 3 (serial 4C201JEAYW0020), launch screencap shows
"bitchat" wordmark (green `#32D74B`, Geist Mono, black background) correctly centered on the
round display. No `app/src/` changes.

---

### M1 — Shared core compiles on Wear

- [x] Configure shared-source wiring in `wear/build.gradle.kts`; resolve transitive dependencies by
  extending includes (never by copying Kotlin sources)
- [x] Copy Geist Mono fonts; create wear theme/palette/peer-color files mirroring `ui/theme/`
- [x] Wire shared unit tests (`protocol`, `noise`, `crypto`, `mesh`) into `:wear` test source set
- [x] `./gradlew :app:test :wear:test` green

**Implementation notes** (deviation from original plan): AGP 9 source directory sets no longer
support include/exclude filters, so a Gradle `Sync` task (`syncSharedAppSources`) materializes a
filtered mirror of `app/src/main/java` into `wear/build/sharedSrc` which is added as a source
root. App sources remain the single source of truth; nothing is hand-copied. Excluded:
`BluetoothMeshService`/`UnifiedMeshService` (phone monolith / Wi-Fi Aware multiplexer — the watch
composes its own service in M2). Two tiny wear-owned shims satisfy the only unresolvable
references from shared code: `com.bitchat.android.service.MeshServiceHolder` (BLE-toggle
interface, null) and `com.bitchat.android.wifiaware.WifiAwareController` (no-op).

**Success criteria**: the entire shared stack (protocol, noise, crypto, identity, mesh, model,
AppStateStore) compiles into `:wear`; both modules' unit tests pass; `app/src/` untouched.
**Result**: PASSED — `:wear` compiles the full shared stack; 172 shared unit tests pass on
`:wear` (0 failures), `:app` suite green; `app/src/` unchanged.

---

### M2 — BLE transport & background service on watch

- [ ] Wear onboarding flow: Bluetooth-enable check + runtime permission requests
  (`BLUETOOTH_SCAN/CONNECT/ADVERTISE`), watch-styled screens
- [ ] `WearMeshService` foreground service (type `connectedDevice`); wire shared
  `BluetoothConnectionManager` + mesh components; start scanning + advertising
- [ ] Internal debug screen: discovered peers with RSSI (temporary, replaced by real UI in M3/M4)
- [ ] Manual interop check: watch and one phone mutually discover

**Success criteria**: the phone's bitchat app lists the watch as a connected peer and vice versa
(logcat + screencap evidence); mesh survives the screen turning off (ambient mode) for 5 minutes.

---

### M3 — Global chat

- [ ] Nickname onboarding; identity announcement over the mesh
- [ ] Send/receive/relay public `BitchatMessage`s (relay/TTL comes free from shared mesh code)
- [ ] Chat timeline UI (`ScalingLazyColumn`, message bubbles per bitchat style, peer colors,
  timestamps) + composer (IME + `RecognizerIntent` dictation) + incoming-message haptics
- [ ] Design check: ADB screencaps of onboarding, chat (empty/populated), composer; review for
  round-screen clipping/visibility/contrast

**Success criteria**: two-way public chat between watch and phone; messages the watch relays reach
a second phone that is only connected through the first (relay proof); screencap set approved.

---

### M4 — Noise DMs & people screen

- [ ] People screen: connected peers, nicknames, RSSI, unread-DM badges
- [ ] Tap peer → Noise XX handshake (shared `EncryptionService`/`NoiseSessionManager`) → DM thread
- [ ] DM conversation UI; unread counters; delivery/read receipts if supported by shared code
- [ ] Identity persistence (`EncryptedSharedPreferences`); stale-session detection & automatic
  re-handshake after watch app restart
- [ ] Design check: screencaps of people screen, handshake state, DM thread

**Success criteria**: encrypted DM round trip with the phone; DMs survive a watch app restart
(session recovery); screencap set approved.

---

### M5 — File/image receive & display — **DEFERRED**

> Deferred to a later day (after M7). Milestones M6 and M7 do not depend on M5 and proceed
> without it. The mesh_lab `file` scenario for the watch is skipped until M5 is un-deferred.

- [ ] Receive broadcast files (`MessageType.FILE_TRANSFER`, `BitchatFilePacket` TLV decode,
  fragment reassembly — all shared)
- [ ] Receive Noise-encrypted private files (`NoisePayloadType.FILE_TRANSFER`)
- [ ] Inline image rendering in chat timelines; full-screen image viewer (pinch/crown zoom);
  non-image files saved with a way to open/share them
- [ ] Transfer progress indicator; respect shared fragment/size caps
- [ ] Design check: screencaps of inline image, full-screen viewer, transfer progress

**Success criteria**: phone→watch image renders inline in both global chat and DM; SHA-256 of
received file matches sender; screencap set approved. (Sending files from the watch is out of
scope.)

---

### M6 — ADB test hook & mesh_lab interop

- [ ] Wear debug-only `TestHookReceiver` (`wear/src/debug/`) mirroring the phone's command set:
  `ping`, `start`, `stop`, `whoami`, `set_nickname`, `scan`, `peers`, `connect`, `handshake`,
  `session`, `announce`, `broadcast_msg`, `dm_send`, `dm_recv`, `msg_recv`, `file_recv`, `state`,
  `clear_results` — broadcast action `com.bitchat.watch.TEST_HOOK`, same JSON-result-file protocol
- [ ] Extend `tools/release_gate/mesh_lab.py`: `--serial-watch` argument and phone↔watch scenarios
  (`dm`, `broadcast`, `session_recovery`, `all`; `file` excluded while M5 is deferred), reusing
  the existing `Device`/`cmd` machinery
- [ ] Run full scenario suite phone↔watch; store evidence JSON

**Success criteria**:
`python3 tools/release_gate/mesh_lab.py scenario all --serial-a <phone> --serial-watch <watch>`
exits 0 with evidence files; no manual intervention.

---

### M7 — Polish & final design pass

- [ ] Animations/transitions per `BitchatMotion` tokens; message-appear animations; screen
  transitions; rotary scroll feel; splash screen & app icon (bitchat wordmark style)
- [ ] Power/battery: verify shared `PowerManager` duty-cycling behaves on Wear; ambient-mode
  behavior; memory audit (fragment/image caps)
- [ ] Full screencap design review of every screen and state; fix all findings
- [ ] Update this document: all milestones `done`; add a short "how to build/run/test" section

**Success criteria**: all milestones marked `done`; interop suite green; final screencap set
approved; a fresh agent can build, install, and test the watch app from this document alone.
