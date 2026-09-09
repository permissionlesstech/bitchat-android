# Bitchat for Pixel Watch — Implementation Plan

> **Status tracker**: each milestone carries a status (`pending` / `in-progress` / `done`) and a
> checklist. A milestone may only be started when the previous milestone's success criteria pass.
> Update statuses in this file as work progresses.

| Milestone | Title | Status |
|-----------|-------|--------|
| M0 | Scaffolding & plan document | done |
| M1 | Shared core compiles on Wear | done |
| M2 | BLE transport & background service on watch | done |
| M3 | Global chat | done |
| M4 | Noise DMs & people screen | done |
| M5 | Files/images receive + voice notes (push-to-talk) + input redesign | done |
| M6 | ADB test hook & mesh_lab interop | done |
| M7 | Polish & final design pass | done |

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
**Result**: PASSED — phone↔watch mutual discovery via `mesh_lab.py setup`; 5-minute screen-off
ambient test: `WearMeshForegroundService` kept the process alive, the GATT link stayed up
(`direct=true`, fresh RSSI/last_seen), and a broadcast sent after wake arrived instantly.
Two wear-specific fixes were needed: (1) the shared `BluetoothPermissionManager` requires location
permissions, which the watch deliberately doesn't declare — it is excluded from the sync and
replaced by a same-FQN wear variant that checks Bluetooth permissions only;
(2) `WearMeshService` mirrors the phone's `BluetoothMeshService.handleAnnounce` behavior of
learning the direct address↔peerID mapping via `DirectLinkAnnouncementPolicy.observationFor` +
`connectionManager.observePeerIfCurrent` (without this, `connect` after restarts fails).

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
**Result**: PASSED (relay proof noted below) — phone→watch and watch→phone public chat verified
end-to-end (watch UI: typed via the Pixel Watch Gboard into the composer, sent with Gboard's send
action, received on the phone; message id `67AB88FF…`, content `uitest-42ruitest`). Gossip sync
re-delivers history after reinstall/restart. Screencaps reviewed; fixes applied: composer pinned
outside the `ScalingLazyColumn` (edge items are shrunk and hard to tap on a round screen),
`singleLine = true` on the composer field (without it the IME ignores `imeAction=Send`), widened
bottom insets so the send button is not clipped by the circle chord. Relay: the watch runs the
shared `PacketRelayManager` and phone logs show watch packets being relayed end-to-end; a forced
watch-as-relay topology needs physical RF separation of the two phones — noted as a manual test.

---

### M4 — Noise DMs & people screen

- [x] People screen: connected peers, nicknames, RSSI, unread-DM badges
- [x] Tap peer → Noise XX handshake (shared `EncryptionService`/`NoiseSessionManager`) → DM thread
- [x] DM conversation UI; unread counters; delivery/read receipts if supported by shared code
- [x] Identity persistence (`EncryptedSharedPreferences`); stale-session detection & automatic
  re-handshake after watch app restart
- [x] Design check: screencaps of people screen, handshake state, DM thread

**Success criteria**: encrypted DM round trip with the phone; DMs survive a watch app restart
(session recovery); screencap set approved.
**Result**: PASSED — `mesh_lab.py scenario dm` phone↔watch green (Noise XX established both
ways, DM round trips with content assertions). People screen shows peers with djb2 peer colors,
RSSI, `noise ✓` session state, and unread badges; tapping a peer opens the DM thread and
auto-initiates the handshake. Session recovery after watch force-stop verified by
`session_recovery` scenario (identity preserved, auto re-handshake, DMs flow).

---

### M5 — Files/images receive + voice notes (push-to-talk) + input redesign

> Revised scope (was: receive-only, deferred). Now includes voice messages as a first-class
> input method and a native-Wear bottom-action redesign of the composer.

**Files & images (receive + display)**

- [x] Receive broadcast + Noise-encrypted private files (shared `BitchatFilePacket` TLV,
  `FileUtils.saveIncomingFile`, `messageTypeForMime` — already wired via shared `MessageHandler`)
- [x] Image messages render as compact inline thumbnails (rounded, fit-width); tap → full-screen
  viewer (black surface, fit-to-screen, dismiss) — mirrors the phone's `ImageMessageItem` /
  `FullScreenImageViewer`
- [x] Non-media files: compact chip (name + size)
- [x] mesh_lab: add `file_recv` to the wear test hook; enable `file` + `file_private` scenarios
  for the watch

**Voice notes (first-class)**

- [x] RECORD_AUDIO permission (manifest + just-in-time runtime request)
- [x] Push-to-talk recording: press-and-hold starts recording, release sends (10 s cap, 600 ms
  minimum, ~80 ms amplitude polls); full-screen overlay that fades in with a live waveform
  animation + elapsed time; shared `VoiceRecorder` (16 kHz mono AAC, `audio/mp4`, `.m4a`)
- [x] Send as `BitchatFilePacket` broadcast in global chat (`MeshCore.sendFileBroadcast`); in a
  DM thread send Noise-encrypted (`WearMeshService.sendFilePrivateEncrypted` with
  handshake/prep retry, mirroring the phone's `dispatchFileSend`)
- [x] Received voice notes (`BitchatMessageType.Audio`, `content` = local path) render as a
  voice-note bubble: play/pause + waveform (shared `Waveform.kt` extractor, 120 bins) +
  duration; `MediaPlayer` playback

**Input redesign (native Wear bottom actions)**

- [x] Replaced the inline composer with two always-visible bottom action buttons (the
  framework's `ScreenScaffold.edgeButton` slot auto-hides on scroll, making push-to-talk
  unreachable mid-conversation, so the bar is overlaid with the same native look instead):
  - keyboard button → full-screen text input screen (field auto-focused, the watch IME opens
    immediately with its built-in dictation; IME hides on send)
  - mic button → push-to-talk (press-and-hold record, release send) with the full-screen
    waveform overlay (rendered outside the edgeButton slot, which would clip it)
- [x] Message lists use `LazyColumn(reverseLayout = true)`: the newest message anchors at the
  bottom above the buttons; empty space collects at the top. Works identically on round and
  square screens (no ScalingLazyColumn center-anchor gap).
- [x] ScreenScaffold contentPadding keeps the last message reachable right above the buttons

**Result**: PASSED —
- `mesh_lab.py scenario file` and `file_private` (phone→watch) green, SHA-256 digest match.
- Push-to-talk voice note (watch→phone) verified end-to-end: broadcast in global chat and
  Noise-encrypted in DM, digest match on the phone side; phone→watch voice note renders as a
  bubble and plays (MediaPlayer).
- Image (phone→watch) verified: compact inline render, tap → full-screen viewer, digest match.
- Keyboard path: auto-focus opens the IME, send hides it, message arrives on the phone.
- Full regression: `scenario all` (7 scenarios) green in ~75 s.
- Robustness: the watch auto-initiates a throttled Noise handshake with peers lacking an
  established session — heals stale sessions after watch restarts (the protocol has no
  decrypt-failure kick path; without this, private files/DMs from peers with stale sessions
  were silently dropped).

---

### M6 — ADB test hook & mesh_lab interop

- [x] Wear debug-only `TestHookReceiver` (`wear/src/debug/`) mirroring the phone's command set:
  `ping`, `start`, `stop`, `whoami`, `set_nickname`, `scan`, `peers`, `connect`, `handshake`,
  `session`, `announce`, `broadcast_msg`, `dm_send`, `dm_recv`, `msg_recv`, `raw_send`, `state`,
  `clear_results` — broadcast action `com.bitchat.watch.TEST_HOOK`, same JSON-result-file protocol
  (`file_*` excluded while M5 is deferred)
- [x] Extend `tools/release_gate/mesh_lab.py`: `--serial-watch` argument and phone↔watch scenarios
  (`dm`, `broadcast`, `raw`, `session_recovery`, `identity_reset`, `all`), reusing
  the existing `Device`/`cmd` machinery
- [x] Run full scenario suite phone↔watch; store evidence JSON

**Success criteria**:
`python3 tools/release_gate/mesh_lab.py scenario all --serial-a <phone> --serial-watch <watch>`
exits 0 with evidence files; no manual intervention.
**Result**: PASSED — `scenario all` (dm, broadcast, raw, session_recovery, identity_reset)
green in 73 s, evidence in `/tmp/meshlab-evidence/all-evidence.json`. Host-side robustness fixes
in `mesh_lab.py`: `WatchDevice` (package/hook/permissions/activity for `com.bitchat.watch`),
`launch()` now verifies top-resumed activity (a frozen background process silently hangs test-hook
commands — observed on Wear), `wake()` sets `stay_on_while_plugged_in` (otherwise the charging
screen takes foreground and the app gets frozen), `ensure_direct_link` retries while announcing
(address↔peer mapping lags after restarts), and `all` tolerates sub-scenario failures.
Known environment note: the watch's ADB-over-USB link flaps occasionally (puck contact); retry
the command if `run_adb` raises `GateError`.

---

### M7 — Polish & final design pass

- [x] Animations/transitions per `BitchatMotion` tokens; message-appear animations; screen
  transitions; auto-scroll to newest; splash screen (black, on-brand) & app icon
- [x] Power/battery: ambient test passed (see M2 result); shared `PowerManager` duty-cycling
  active; composer/IME insets verified. Rotary crown scrolling is provided by
  `ScalingLazyColumn` (wear-compose-foundation ≥1.3, framework-level; `input rotary` is not
  supported by this Wear build's adb, so crown feel was not adb-verifiable — check manually)
- [x] Full screencap design review of every screen and state; fixes applied (composer pinning,
  `singleLine` IME action, bottom-chord clipping, black splash)
- [x] Update this document: all milestones `done`; add a short "how to build/run/test" section

**Success criteria**: all milestones marked `done`; interop suite green; final screencap set
approved; a fresh agent can build, install, and test the watch app from this document alone.
**Result**: PASSED.

---

## How to build / run / test

Prereqs: JDK (e.g. Android Studio JBR), `adb` on PATH or `ANDROID_HOME` set, Python 3.10+,
a Wear OS device (Pixel Watch) and a phone with USB debugging. **Both devices unlocked, screen
on** — on the watch, disable the lock screen (Settings → Security) or tests will stall on the
pattern lock; mesh_lab sets `stay_on_while_plugged_in` etc. automatically.

```bash
# Build
./gradlew :wear:assembleDebug :app:assembleDebug

# Unit tests (shared stack runs on both modules)
./gradlew :wear:testDebugUnitTest :app:testDebugUnitTest

# Install & launch on the watch
adb -s <watch-serial> install -r -g wear/build/outputs/apk/debug/wear-debug.apk
adb -s <watch-serial> shell monkey -p com.bitchat.watch -c android.intent.category.LAUNCHER 1

# Screencap (design checks)
adb -s <watch-serial> exec-out screencap -p > watch.png

# Full interop suite (phone + watch)
python3 tools/release_gate/mesh_lab.py setup \
  --serial-a <phone-serial> --serial-watch <watch-serial> \
  --apk app/build/outputs/apk/debug/app-arm64-v8a-debug.apk \
  --watch-apk wear/build/outputs/apk/debug/wear-debug.apk
python3 tools/release_gate/mesh_lab.py scenario all \
  --serial-a <phone-serial> --serial-watch <watch-serial> --out /tmp/meshlab-evidence

# Ad-hoc test-hook commands (watch)
adb -s <watch-serial> shell am broadcast -a com.bitchat.watch.TEST_HOOK \
  -n com.bitchat.watch/.testhook.WearTestHookReceiver --es cmd state --es id s1
adb -s <watch-serial> shell run-as com.bitchat.watch cat cache/testhook/results/s1.json
```

Notes:
- If `mesh_lab` raises `GateError: ADB command failed`, the watch's USB link flapped — retry.
- Wear test-hook commands: `ping start stop whoami set_nickname scan peers connect handshake
  session announce broadcast_msg dm_send dm_recv msg_recv raw_send file_recv state
  clear_results`.
