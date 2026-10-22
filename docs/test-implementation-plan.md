# Test implementation plan

## Objective

Build enough deterministic, adversarial, integration, and device-level coverage
that the Android client can be rewritten from scratch without silently changing
its wire behavior, security properties, delivery semantics, lifecycle behavior,
or user-visible workflows.

The canonical compatibility requirements are documented in
[client-rewrite-contracts.md](client-rewrite-contracts.md). This plan describes
how to turn those requirements into a complete, continuously enforced test
program.

## Status legend

- **Complete**: acceptance criteria are met and the tests run in the rewrite gate.
- **In progress**: implementation has started but acceptance criteria are not met.
- **Not started**: no implementation work has been completed.
- **Blocked**: progress requires an external dependency, device, or decision.

## Current progress

| Milestone | Status | Progress | Depends on |
|---|---|---:|---|
| 0. Compatibility baseline | Complete | 100% | — |
| 1. Coverage and deterministic test infrastructure | Not started | 0% | 0 |
| 2. Adversarial protocol and parser testing | Not started | 0% | 1 |
| 3. Noise, cryptography, and identity testing | Not started | 0% | 1 |
| 4. BLE, Wi-Fi Aware, and transport lifecycle testing | Not started | 0% | 1, 3 |
| 5. Sync, routing, and store-and-forward testing | Not started | 0% | 1, 4 |
| 6. Nostr and Tor integration testing | Not started | 0% | 1, 3 |
| 7. Android lifecycle and permission testing | Not started | 0% | 1, 4 |
| 8. Persistence, migration, and recovery testing | Not started | 0% | 1, 3 |
| 9. UI, media, and accessibility testing | Not started | 0% | 1, 7, 8 |
| 10. Physical-device and cross-client release gate | Not started | 0% | 2–9 |

Milestone completion is currently **1 of 11 milestones (9%)**. This is
milestone-based progress, not line or branch coverage. Milestone 1 will establish
measured coverage baselines and trends.

## Test levels and execution policy

| Level | Purpose | Expected execution |
|---|---|---|
| Pure JVM unit tests | Protocols, state machines, crypto vectors, parsing, routing, and deterministic utilities | Every pull request |
| Property and fuzz tests | Malformed inputs, boundary exploration, invariants, and crash resistance | Bounded set on every pull request; extended corpus nightly |
| Robolectric tests | Android services, lifecycle, broadcasts, permissions, persistence, and process recreation | Every pull request where stable |
| Instrumented emulator tests | Compose semantics, navigation, database/filesystem integration, and permission flows | Main branch and release candidates |
| Physical-device tests | BLE, Wi-Fi Aware, radios, background execution, and manufacturer-specific behavior | Nightly where devices are available; mandatory release gate |
| Cross-client interoperability | Android/iOS and old/new client wire compatibility | Mandatory release gate |

## Global rules

- [x] Keep literal golden vectors for externally visible bytes and hashes.
- [x] Run all compatibility and regression tests through
  `./gradlew clientRewriteContractTest`.
- [ ] Prefer public behavior and stable adapter interfaces over implementation
  details.
- [ ] Require deterministic clocks, randomness, dispatchers, storage, and
  transports in tests.
- [ ] Never use production relay or internet availability as a test dependency.
- [ ] Every fixed protocol or security defect must receive a regression test.
- [ ] Every decoder must have positive, boundary, malformed, and fuzz coverage.
- [ ] Every asynchronous test must have bounded completion and must not use
  arbitrary sleeps.
- [ ] Test failures must preserve seeds, inputs, and traces needed to reproduce
  the failure.
- [ ] Golden vectors may change only with an intentional protocol version change
  and coordinated interoperability review.

---

## Milestone 0: Compatibility baseline

**Status:** Complete  
**Progress:** 100%

### Scope

Establish executable rewrite contracts for the most important deterministic
wire formats and reuse the existing regression suite as a single acceptance
gate.

### Completed checklist

- [x] Create an isolated workspace and
  `codex/client-rewrite-contract-tests` branch.
- [x] Add literal v1 and v2 outer packet vectors.
- [x] Add public, private, optional-field, and encrypted message vectors.
- [x] Add private-message, Noise envelope, fragment, sync, identity, and file
  transfer vectors.
- [x] Add padding, binary encoding, geohash, gossip, packet ID, GCS, and Noise
  peer-ID contracts.
- [x] Add Bech32, secp256k1, NIP-01, NIP-44, and NIP-13 contracts.
- [x] Add required-prefix and representative truncated-input rejection tests.
- [x] Add explicit validation for malformed fragment IDs.
- [x] Add `clientRewriteContractTest` as the complete rewrite acceptance task.
- [x] Verify 32 new tests pass without skips.
- [x] Verify the full gate discovers 254 tests with zero failures or errors.
- [x] Document the remaining device-only acceptance requirements.

### Acceptance criteria

- [x] The original `main` workspace remains unchanged.
- [x] All new golden-vector tests pass.
- [x] The complete unit suite passes through one documented command.

---

## Milestone 1: Coverage and deterministic test infrastructure

**Status:** Not started  
**Progress:** 0%

### Goal

Make coverage measurable and provide reusable deterministic seams so later
milestones test behavior without real time, radios, network access, or flaky
scheduling.

### TODO checklist

#### Coverage reporting

- [ ] Add JaCoCo or Kover for JVM unit-test line and branch coverage.
- [ ] Generate XML and HTML reports from the rewrite acceptance task.
- [ ] Record the initial project-wide line and branch coverage baseline.
- [ ] Record package-level baselines for `mesh`, `noise`, `nostr`, `service`,
  `services`, `sync`, `model`, `protocol`, `identity`, and `ui`.
- [ ] Publish coverage artifacts in CI.
- [ ] Add a changed-lines coverage check for new production code.
- [ ] Add non-regression thresholds without forcing low-value tests for trivial
  generated or platform glue.
- [ ] Exclude generated code, Compose compiler output, Android resource classes,
  and vendored cryptographic code from first-party coverage metrics.

#### Deterministic seams

- [ ] Introduce an injectable monotonic clock and wall clock.
- [ ] Introduce injectable secure and non-secure random-byte sources where
  deterministic vectors are required.
- [ ] Introduce injectable coroutine dispatchers and test scopes.
- [ ] Introduce an in-memory key/value storage adapter for preferences.
- [ ] Introduce an in-memory file store with controllable I/O failures.
- [ ] Define a fake mesh transport that can connect, disconnect, delay, drop,
  duplicate, corrupt, reorder, and fragment packets.
- [ ] Define fake BLE scanner, advertiser, GATT client, and GATT server adapters.
- [ ] Define a fake Wi-Fi Aware session/socket adapter.
- [ ] Define a fake Nostr relay transport or MockWebServer fixture.
- [ ] Provide reusable packet, identity, peer, graph, and message fixture
  builders.
- [ ] Provide seed capture and reproduction helpers for randomized tests.
- [ ] Add test naming and directory conventions for unit, property, Robolectric,
  instrumented, and interoperability suites.

### Acceptance criteria

- [ ] One command generates a repeatable coverage report.
- [ ] Two consecutive clean runs produce identical deterministic test results.
- [ ] Fake time and transport behavior require no wall-clock sleeps.
- [ ] CI publishes coverage and test-result artifacts.
- [ ] The plan's progress table is updated with measured baseline numbers.

---

## Milestone 2: Adversarial protocol and parser testing

**Status:** Not started  
**Progress:** 0%

### Goal

Prove that all wire decoders preserve canonical behavior, reject unsafe input,
and never crash or allocate unreasonable memory for attacker-controlled data.

### TODO checklist

#### Outer packet protocol

- [ ] Test every valid flag combination for v1 and v2.
- [ ] Test exact minimum and maximum payload sizes.
- [ ] Test sender and recipient IDs at 0, 1, 7, 8, 9, and oversized lengths.
- [ ] Test signatures at 0, 1, 63, 64, 65, and oversized lengths.
- [ ] Test route counts at 0, 1, 254, 255, and truncated route entries.
- [ ] Test unknown message type values remain safely representable or are
  rejected according to the protocol contract.
- [ ] Test invalid versions, reserved flags, integer overflow, and unsigned
  length conversion.
- [ ] Test trailing bytes and concatenated frames explicitly.
- [ ] Test padding boundaries around 256, 512, 1024, and 2048 bytes.
- [ ] Test malformed PKCS#7 tails and ambiguous unpadded frames.
- [ ] Test raw DEFLATE and zlib-header compatibility vectors.
- [ ] Test forged original-size fields, compression bombs, and truncated
  compressed streams.
- [ ] Add encode/decode property tests for all valid packet shapes.
- [ ] Add a mutation corpus derived from every golden packet.

#### Inner payloads and TLVs

- [ ] Fuzz `BitchatMessage.fromBinaryPayload`.
- [ ] Fuzz `IdentityAnnouncement.decode`.
- [ ] Fuzz `AuthenticatedPeerState.decode`.
- [ ] Fuzz `PrivateMessagePacket.decode`.
- [ ] Fuzz `NoisePayload.decode`.
- [ ] Fuzz `BitchatFilePacket.decode`.
- [ ] Fuzz `FragmentPayload.decode`.
- [ ] Fuzz `RequestSyncPacket.decode`.
- [ ] Test missing, duplicated, reordered, unknown, and zero-length TLVs.
- [ ] Test truncated headers and values at every byte offset.
- [ ] Test UTF-8 ASCII, multi-byte, combining-mark, emoji, invalid-byte, and
  maximum-byte-length cases.
- [ ] Test 255-byte one-byte-length boundaries.
- [ ] Test 65,535-byte two-byte-length boundaries.
- [ ] Test four-byte file content lengths and impossible content declarations.
- [ ] Test fragmented file content using one and multiple content TLVs.
- [ ] Define and test whether non-canonical but tolerated inputs re-encode
  canonically.

#### Fuzzing operations

- [ ] Select a JVM-compatible property/fuzz framework.
- [ ] Add bounded pull-request fuzz runs with fixed seeds.
- [ ] Add extended randomized nightly runs.
- [ ] Store minimized failing inputs as regression fixtures.
- [ ] Assert no decoder throws for arbitrary byte arrays.
- [ ] Assert decoder runtime and allocations stay within configured bounds.

### Acceptance criteria

- [ ] Every externally reachable decoder has boundary and malformed-input tests.
- [ ] Every decoder has a bounded arbitrary-byte no-crash property.
- [ ] All discovered crashes or ambiguous contracts have regression fixtures.
- [ ] Extended fuzzing completes nightly and preserves reproduction seeds.

---

## Milestone 3: Noise, cryptography, and identity testing

**Status:** Not started  
**Progress:** 0%

### Goal

Prove confidentiality, authenticity, identity binding, replay behavior, session
replacement, rekeying, and recovery across the complete secure-channel
lifecycle.

### TODO checklist

#### Known vectors and primitives

- [ ] Add known Curve25519 key agreement vectors.
- [ ] Add known Ed25519 signing and verification vectors.
- [ ] Add known BIP-340 verification vectors.
- [ ] Add known HKDF and channel-key derivation vectors.
- [ ] Add external Noise XX handshake transcript vectors where compatible.
- [ ] Add deterministic channel encryption vectors with injected nonces.
- [ ] Test constant-time verification APIs where the underlying library exposes
  an appropriate contract.

#### Noise session lifecycle

- [ ] Test initiator and responder handshakes without manager wrappers.
- [ ] Test all valid handshake state transitions.
- [ ] Test every invalid message for every handshake state.
- [ ] Test tampered handshake messages and remote static-key substitution.
- [ ] Test post-handshake encryption in both directions.
- [ ] Test empty, small, maximum, and fragmented plaintext.
- [ ] Test tampered ciphertext, nonce, tag, and associated data.
- [ ] Test replayed ciphertext.
- [ ] Test skipped, duplicated, and out-of-order transport messages.
- [ ] Test send and receive nonce progression.
- [ ] Test nonce exhaustion and counter-overflow behavior.
- [ ] Test rekey thresholds, successful rekey, failed rekey, and simultaneous
  rekey.
- [ ] Test session reset and destruction zeroize or discard sensitive state as
  designed.
- [ ] Test handshake timeouts and stale generation leases with fake time.
- [ ] Test simultaneous initiator tie-breaking across a larger peer matrix.
- [ ] Test process restart with and without persisted identity.

#### Identity and downgrade protection

- [ ] Test peer-ID derivation for valid and malformed static keys.
- [ ] Test signing-key rotation with authorized and unauthorized announcements.
- [ ] Test private-media capability pinning across restart.
- [ ] Test downgrade attempts after a capability has been pinned.
- [ ] Test corrupted, missing, partially written, and legacy identity storage.
- [ ] Test atomic clearing of identity, capability, and peer mappings.
- [ ] Test verification fingerprints remain stable for unchanged identities.
- [ ] Test identity replacement does not expose an established session before
  authentication completes.

### Acceptance criteria

- [ ] Known vectors pass independently of Android storage and services.
- [ ] Replay, tampering, downgrade, and identity-substitution tests all fail
  closed.
- [ ] All timeouts and rekey tests use fake time.
- [ ] No sensitive test fixtures contain production keys or user data.

---

## Milestone 4: BLE, Wi-Fi Aware, and transport lifecycle testing

**Status:** Not started  
**Progress:** 0%

### Goal

Verify connection state machines and packet delivery across unreliable Android
transports without requiring real radios for the majority of cases.

### TODO checklist

#### BLE discovery and connection

- [ ] Test scan start, stop, restart, and failure callbacks.
- [ ] Test advertising start, stop, restart, and failure callbacks.
- [ ] Test Bluetooth-off and Bluetooth-on recovery.
- [ ] Test duplicate scan results and rapidly changing peer addresses.
- [ ] Test connection success, rejection, timeout, and cancellation.
- [ ] Test simultaneous inbound and outbound connection races.
- [ ] Test canonical connection selection and duplicate-link teardown.
- [ ] Test service discovery failure and missing characteristics.
- [ ] Test GATT disconnect during discovery, negotiation, read, and write.
- [ ] Test reconnect backoff with fake time.
- [ ] Test maximum-connection enforcement and eviction policy.
- [ ] Test RSSI thresholds and power-mode transitions.

#### Packet transfer

- [ ] Test MTU negotiation at minimum, normal, and maximum values.
- [ ] Test partial writes and write callbacks delivered out of order.
- [ ] Test notification subscription and notification failure.
- [ ] Test queue backpressure and bounded memory use.
- [ ] Test fragmentation and reassembly across disconnect/reconnect.
- [ ] Test duplicate, missing, reordered, and corrupted fragments.
- [ ] Test cancellation cleans pending queues and transfer state.
- [ ] Test large file/media transfers under constrained MTU.
- [ ] Test broadcast and directed packet delivery.
- [ ] Test packet relay while one link disconnects.

#### Wi-Fi Aware

- [ ] Test feature unavailable and permission-denied behavior.
- [ ] Test publish/subscribe session creation and teardown.
- [ ] Test provisional link authentication and canonical promotion.
- [ ] Test socket replacement and stale-socket rejection.
- [ ] Test partial reads, writes, EOF, exceptions, and cancellation.
- [ ] Test reconnect and rediscovery.
- [ ] Test coexistence with BLE for the same peer.

#### Unified transport behavior

- [ ] Test peer-list union and removal across transports.
- [ ] Test preferred-transport selection.
- [ ] Test transparent failover between BLE and Wi-Fi Aware.
- [ ] Test duplicate packet suppression across transports.
- [ ] Test transport bridge TTL decrement and loop prevention.
- [ ] Test shutdown cancels all jobs, scans, advertisements, sockets, and queues.

### Acceptance criteria

- [ ] Transport state-machine tests run deterministically on the JVM or
  Robolectric.
- [ ] Disconnect and cancellation tests leave no queued work or active jobs.
- [ ] Cross-transport duplicate delivery and loops are prevented.
- [ ] A smaller physical-device suite confirms the fake adapters match Android
  behavior.

---

## Milestone 5: Sync, routing, and store-and-forward testing

**Status:** Not started  
**Progress:** 0%

### Goal

Verify eventual delivery, bounded resource usage, correct routing, and duplicate
suppression during partitions, topology changes, and reconnects.

### TODO checklist

#### Packet identity and sync filters

- [ ] Add more packet-ID vectors for every message type.
- [ ] Prove TTL, route, recipient, and signature mutations do not change sync
  identity.
- [ ] Prove payload, sender, timestamp, and type mutations do change identity.
- [ ] Property-test GCS encode/decode membership.
- [ ] Test empty, singleton, maximum-capacity, duplicate, and collision-heavy
  filters.
- [ ] Test false-positive behavior statistically against configured tolerances.
- [ ] Test maximum accepted filter bytes and malicious bitstreams.
- [ ] Test sync requests with unknown TLVs and future capability extensions.

#### Store and forward

- [ ] Test caching decisions for public, private, favorite, and offline peers.
- [ ] Test cache capacity and deterministic eviction.
- [ ] Test cache expiry with fake time.
- [ ] Test delivery acknowledgement removal.
- [ ] Test retransmission after reconnect.
- [ ] Test duplicate acknowledgements and late acknowledgements.
- [ ] Test process restart persistence policy.
- [ ] Test shutdown and cleanup under active delivery.
- [ ] Test memory bounds under repeated undeliverable messages.

#### Routing and topology

- [ ] Test shortest paths for disconnected, cyclic, diamond, and changing graphs.
- [ ] Test deterministic tie-breaking for equal-length routes.
- [ ] Test only confirmed edges are used.
- [ ] Test edge expiry and peer disappearance with fake time.
- [ ] Test a route invalidated between planning and send.
- [ ] Test relay TTL exhaustion at every hop.
- [ ] Test source-route loop rejection.
- [ ] Test broadcast storm suppression.
- [ ] Test delivery across mixed BLE and Wi-Fi Aware paths.
- [ ] Test graph updates while sync and relay operations run concurrently.

### Acceptance criteria

- [ ] Partition/reconnect scenarios eventually deliver exactly once at the
  application layer.
- [ ] Cache, graph, and filter resource bounds are enforced.
- [ ] No topology or bridge scenario produces an infinite relay loop.
- [ ] All expiry behavior uses fake time.

---

## Milestone 6: Nostr and Tor integration testing

**Status:** Not started  
**Progress:** 0%

### Goal

Verify relay communication, subscriptions, event validation, NIP-17 delivery,
and Tor-mode behavior under realistic network failures.

### TODO checklist

#### Relay protocol

- [ ] Add a scripted local WebSocket relay fixture.
- [ ] Test initial connection and clean disconnect.
- [ ] Test DNS, TCP, TLS, WebSocket, and protocol failures.
- [ ] Test reconnect backoff and cancellation with fake time.
- [ ] Test relay notices, acknowledgements, end-of-stored-events, and malformed
  messages.
- [ ] Test subscription creation, replacement, unsubscribe, and reconnect
  restoration.
- [ ] Test duplicate, delayed, reordered, and conflicting events.
- [ ] Test multi-relay publish success, partial success, and total failure.
- [ ] Test event deduplication across relays.
- [ ] Test relay-list selection and invalid relay URLs.

#### Event security and messaging

- [ ] Add external NIP-01, NIP-13, NIP-17, and NIP-44 vectors.
- [ ] Test invalid event IDs and signatures are rejected before dispatch.
- [ ] Test future timestamps, stale timestamps, and integer boundaries.
- [ ] Test NIP-17 gift-wrap signer/rumor identity mismatches.
- [ ] Test malformed seals, wrong recipients, and tampered ciphertext.
- [ ] Test private-message and acknowledgement embedding/extraction.
- [ ] Test geohash note, presence, and ephemeral-event filters.
- [ ] Test nickname and teleport tags.
- [ ] Test proof-of-work policy at exact difficulty boundaries.
- [ ] Test cancellation and bounded mining iterations.

#### Tor behavior

- [ ] Add a fake Tor-state provider and proxy-selection tests.
- [ ] Test direct, Tor-only, and fallback modes.
- [ ] Test bootstrap delay, bootstrap failure, proxy failure, and shutdown.
- [ ] Verify Tor-only mode never silently uses a direct connection.
- [ ] Verify mode changes rebuild clients and close old connections.

### Acceptance criteria

- [ ] Nostr integration tests require no public relay or internet connection.
- [ ] Invalid or unauthenticated events never reach application state.
- [ ] Reconnect restores intended subscriptions without duplicate delivery.
- [ ] Tor-only policy fails closed.

---

## Milestone 7: Android lifecycle and permission testing

**Status:** Not started  
**Progress:** 0%

### Goal

Verify the app behaves correctly under Android process, service, permission,
Bluetooth, battery, and background-execution rules.

### TODO checklist

#### Foreground service

- [ ] Add Robolectric tests for service create, start, bind, unbind, and destroy.
- [ ] Test repeated start commands are idempotent.
- [ ] Test foreground notification creation and channel configuration.
- [ ] Test explicit shutdown clears transport and application state correctly.
- [ ] Test unexpected process/service recreation restores required state.
- [ ] Test task removal behavior.
- [ ] Test boot-completed handling.
- [ ] Test service start restrictions and failure reporting.
- [ ] Test all coroutines and resources are cancelled on destroy.

#### Permissions and system state

- [ ] Test first-run permission explanations.
- [ ] Test denial, permanent denial, and later grant.
- [ ] Test partial Bluetooth permission grants by Android version.
- [ ] Test location-disabled and Bluetooth-disabled states.
- [ ] Test notification permission denial.
- [ ] Test microphone permission denial during voice recording.
- [ ] Test background-location preferences where applicable.
- [ ] Test battery-optimization accepted, declined, and unavailable paths.
- [ ] Test configuration changes during onboarding.
- [ ] Test onboarding restoration after process recreation.

#### Android-version matrix

- [ ] Define minimum, target, and newest-supported API test matrix.
- [ ] Add emulator coverage for behavior changes in permissions and foreground
  services.
- [ ] Add at least one low-memory/process-death scenario.
- [ ] Add manufacturer-device coverage for known BLE/background differences.

### Acceptance criteria

- [ ] Critical service and permission flows have Robolectric or instrumented
  coverage.
- [ ] No permission denial crashes or leaves onboarding irrecoverable.
- [ ] Service recreation does not duplicate transports or lose required state.
- [ ] Required API-level matrix passes before release.

---

## Milestone 8: Persistence, migration, and recovery testing

**Status:** Not started  
**Progress:** 0%

### Goal

Ensure identities, settings, aliases, favorites, bookmarks, messages, and
capability pins survive upgrades and fail safely when storage is incomplete or
corrupt.

### TODO checklist

- [ ] Inventory every persisted key, file, schema, and version marker.
- [ ] Create legacy fixtures for every supported application version.
- [ ] Test clean first launch with no persisted state.
- [ ] Test upgrade from each retained legacy fixture.
- [ ] Test unknown future fields are preserved or ignored safely.
- [ ] Test truncated, malformed, empty, and type-mismatched preference values.
- [ ] Test partial multi-key identity writes.
- [ ] Test storage write failure and rollback.
- [ ] Test concurrent readers and writers.
- [ ] Test alias merging and canonical conversation migration.
- [ ] Test chronological ordering after migration.
- [ ] Test favorite and bookmark preservation.
- [ ] Test message-retention expiry with fake time.
- [ ] Test secure identity clearing removes all linked mappings and pins.
- [ ] Test signing-key and capability rotation is atomic.
- [ ] Test backup/restore policy does not duplicate or expose sensitive identity
  material.
- [ ] Test migration idempotence by running each migration twice.
- [ ] Test downgrade behavior when a newer schema has already been written.

### Acceptance criteria

- [ ] Every supported legacy fixture migrates deterministically.
- [ ] Failed migrations leave either the old valid state or the new valid state,
  never a partial mixture.
- [ ] Security-sensitive corruption fails closed with a recoverable user path.
- [ ] Migration and retention behavior uses deterministic storage and time.

---

## Milestone 9: UI, media, and accessibility testing

**Status:** Not started  
**Progress:** 0%

### Goal

Protect user-visible behavior and media workflows while keeping most assertions
at ViewModel/state boundaries and reserving Compose instrumentation for genuine
interaction and rendering contracts.

### TODO checklist

#### ViewModels and application state

- [ ] Restore or replace the currently skipped command-processor tests.
- [ ] Restore or replace the currently skipped notification tests.
- [ ] Test public/private/channel conversation switching.
- [ ] Test optimistic send, success, failure, retry, and cancellation.
- [ ] Test delivery and read-receipt transitions.
- [ ] Test peer arrival, departure, alias change, and identity rotation.
- [ ] Test state restoration after configuration change and process recreation.
- [ ] Test concurrent inbound messages while changing conversations.
- [ ] Test error messages for permission, transport, storage, and crypto failures.

#### Compose UI

- [ ] Add semantics tests for critical chat actions.
- [ ] Test onboarding navigation and recoverability.
- [ ] Test empty, loading, connected, disconnected, and error states.
- [ ] Test long nicknames, messages, channels, and localized text.
- [ ] Test dynamic font sizes and display scaling.
- [ ] Test light, dark, and supported theme variants.
- [ ] Add screenshot tests only for stable high-value layouts.
- [ ] Test keyboard, focus, back navigation, and bottom-sheet behavior.
- [ ] Test screen-reader labels, traversal order, and minimum touch targets.
- [ ] Test reduced-motion behavior where animations are nonessential.

#### Files, images, and voice

- [ ] Test zero-byte, small, maximum-size, and oversized files.
- [ ] Test unsupported and misleading MIME types.
- [ ] Test missing filenames and Unicode filenames.
- [ ] Test file read/write failures and insufficient storage.
- [ ] Test image decode failures, orientation metadata, and large-image memory
  limits.
- [ ] Test voice-recording start, pause/stop, cancellation, and microphone loss.
- [ ] Test corrupt and unsupported audio playback.
- [ ] Test waveform generation boundaries.
- [ ] Test interrupted private-media preparation and commit rollback.
- [ ] Test cleanup of temporary files after success, failure, and cancellation.

### Acceptance criteria

- [ ] Critical user journeys pass through state-level tests.
- [ ] A focused Compose suite protects navigation, semantics, and accessibility.
- [ ] Media failures are visible, recoverable, and leak no temporary resources.
- [ ] Previously skipped UI-related tests are either active or replaced with
  equivalent coverage.

---

## Milestone 10: Physical-device and cross-client release gate

**Status:** Not started  
**Progress:** 0%

### Goal

Validate the behavior that JVM, Robolectric, and emulator tests cannot prove:
real radios, background limits, device interoperability, and compatibility with
released clients.

### TODO checklist

#### Device matrix and harness

- [ ] Define a minimum physical-device matrix covering at least two Android API
  levels and two manufacturers.
- [ ] Include devices supporting BLE only and BLE plus Wi-Fi Aware where
  available.
- [ ] Build a test control channel that does not interfere with mesh transport.
- [ ] Capture structured traces, packet IDs, connection transitions, and failure
  reasons.
- [ ] Make test accounts, identities, and files disposable and non-personal.
- [ ] Provide deterministic scenario setup and cleanup.

#### Android-to-Android scenarios

- [ ] Discover, connect, exchange announcements, disconnect, and reconnect.
- [ ] Send public and private messages in both directions.
- [ ] Verify delivery and read receipts.
- [ ] Transfer image, audio, and generic files at multiple sizes.
- [ ] Relay across at least three devices.
- [ ] Partition the mesh and verify store-and-forward delivery after reconnect.
- [ ] Disable and re-enable Bluetooth during active transfers.
- [ ] Lock screens and background both apps during active mesh operation.
- [ ] Kill and recreate one process.
- [ ] Exercise simultaneous connections and duplicate-link resolution.
- [ ] Exercise Wi-Fi Aware failover where supported.

#### Cross-client and backward compatibility

- [ ] Test current Android against the current iOS client.
- [ ] Test the rewrite against the last supported Android release.
- [ ] Test legacy announcements without capabilities.
- [ ] Test current capability announcements with an older client.
- [ ] Test canonical private-media type and decode-only prerelease alias.
- [ ] Compare packet, message, identity, fragment, sync, and file golden vectors
  across implementations.
- [ ] Verify malformed and unauthenticated inputs are rejected consistently.
- [ ] Verify Nostr fallback messages and receipts across clients.

#### Background and endurance

- [ ] Run a multi-hour discovery/connect/disconnect soak test.
- [ ] Run repeated large-transfer and cancellation cycles.
- [ ] Monitor memory, threads, file descriptors, wake locks, and battery impact.
- [ ] Test foreground-service survival with screens off.
- [ ] Test network and Tor availability changes during Nostr operation.
- [ ] Confirm shutdown releases radios, sockets, jobs, and wake locks.

### Acceptance criteria

- [ ] All mandatory scenarios pass on the defined device matrix.
- [ ] Android/iOS and old/new clients exchange every supported critical payload.
- [ ] No endurance run shows unbounded growth or leaked resources.
- [ ] Failures produce sufficient traces for deterministic reproduction where
  possible.
- [ ] Release approval records the client versions, device matrix, and results.

---

## CI rollout

### Pull-request gate

- [ ] Run formatting and static analysis.
- [ ] Run deterministic JVM unit tests.
- [ ] Run bounded property/fuzz tests.
- [ ] Run stable Robolectric tests.
- [ ] Run `clientRewriteContractTest`.
- [ ] Upload JUnit and coverage reports.
- [ ] Reject new failures, errors, or unexpected skips.
- [ ] Reject golden-vector changes without the protocol-change review label.

### Main and nightly gate

- [ ] Run the extended fuzz corpus.
- [ ] Run emulator instrumented tests.
- [ ] Run local relay/Tor integration tests.
- [ ] Run physical-device smoke tests when the lab is available.
- [ ] Track runtime, flakes, coverage, and quarantined tests.

### Release-candidate gate

- [ ] Run the complete device matrix.
- [ ] Run Android/iOS and old/new interoperability.
- [ ] Run endurance and background scenarios.
- [ ] Review all skipped or quarantined tests.
- [ ] Archive coverage, test, trace, and version metadata with the release.

## Progress update procedure

When work lands:

1. Check completed TODOs in the relevant milestone.
2. Update the milestone percentage based on completed checklist items.
3. Change status to **In progress** when its first TODO is complete.
4. Change status to **Complete** only when all acceptance criteria are met.
5. Update the top-level progress table and milestone completion count.
6. Link the implementing pull request or commit next to material completed work
   without including personal information.
7. Record intentionally deferred items and their justification; do not mark them
   complete.

## Final definition of done

The test program is complete when:

- [ ] Milestones 0–10 meet every acceptance criterion.
- [ ] Project and package-level line/branch coverage no longer regress.
- [ ] All critical parsers have adversarial and fuzz coverage.
- [ ] All security-sensitive state transitions fail closed under tampering,
  replay, downgrade, and corruption.
- [ ] Transport, sync, and lifecycle tests cover disconnection, cancellation,
  timeout, and restart.
- [ ] Physical-device and cross-client scenarios pass for every release.
- [ ] The full rewrite can replace the existing implementation while preserving
  the unchanged compatibility and acceptance tests.
