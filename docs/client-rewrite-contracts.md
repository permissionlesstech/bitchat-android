# Client rewrite compatibility contracts

This document defines the behavior a from-scratch BitChat client must preserve.
The executable source of truth is the JVM test suite under
`app/src/test/**/contracts`, together with the pre-existing protocol, security,
mesh, and state tests.

The remaining implementation work and milestone progress are tracked in
[test-implementation-plan.md](test-implementation-plan.md).

## Required contract layers

| Layer | Compatibility promise | Primary tests |
|---|---|---|
| Outer mesh packet | v1/v2 header widths, big-endian fields, flags, section order, route placement, signature placement, padding, compression, signing bytes | `BinaryProtocolTest`, `ClientRewriteWireContractTest` |
| Chat payload | Flag bits, millisecond timestamp, UTF-8 byte lengths, encrypted-content substitution, optional-field order | `ClientRewriteWireContractTest` |
| Inner payloads | Noise type bytes, private-message TLVs, peer-state TLVs, file-transfer TLVs, live-voice bursts, fragment header, sync request TLVs | `ClientRewriteWireContractTest`, `AuthenticatedPeerStateTest`, `PrivateMediaTransferPreparerTest`, `VoiceBurstPacketTest`, `FragmentManagerTest` |
| Identity/security | Announcement extensions, capability bitfield endianness, Noise static-key binding, handshake identity binding, signatures | `IdentityAnnouncementTest`, `NoiseSessionManagerIdentityBindingTest`, `ClientRewritePrimitiveContractTest` |
| Sync/routing | Stable packet IDs, GCS bitstream, replay collapse, TTL handling, relay choice, confirmed graph edges | `ClientRewritePrimitiveContractTest`, `GCSFilterTest`, `PacketRelayManagerTest`, `MeshGraphServiceTest`, `TransportBridgeServiceTest` |
| Nostr | Bech32, secp256k1 key derivation, NIP-01 event IDs/signatures, NIP-42 relay authentication, NIP-44 authenticated encryption, NIP-13 PoW, authenticated NIP-17 seals, 22h outbound envelope randomization | `ClientRewriteNostrContractTest`, `NostrProtocolTest`, `NostrRelayAuthenticationTest`, `NostrRelayAuthenticationSessionTest`, `NostrRelayAuthenticationRobolectricTest` |
| Application state | Peer unions, canonical private conversations, chronological history, delivery/read behavior, media migration policy | `AppStateStoreTest`, `PrivateChatManagerTest`, `MediaSendingManagerMigrationTest` |

## Golden-vector policy

Golden vectors compare literal externally visible bytes or hashes. Do not update
them merely because an implementation changed. Update a vector only when the
wire protocol is intentionally versioned and interoperating clients are updated
together.

Round-trip tests remain useful but are not sufficient on their own: an encoder
and decoder can share the same defect. Each critical wire format therefore has
at least one literal vector.

NIP-17 receivers should reserve safety slack beyond the maximum timestamp
randomization used by senders. Android caps outbound seal and gift-wrap
randomization at 22h, leaving 2 hours of slack inside iOS's 24-hour
subscription window, while retaining its 48-hour receive lookback.

## Relay authentication (NIP-42)

The client stores each relay's `AUTH` challenge on its current WebSocket. A
challenge alone does not disclose identity. When that relay rejects an active
subscription (`CLOSED`) or a queued publication (`OK`, false) with the
`auth-required:` prefix, the client signs a kind `22242` proof using the identity
of that operation. Geohash chat, presence, notes, channel DMs, and their delivery
and read receipts use the originating channel's derived identity. The separate
account/favorites Nostr path uses its persistent account identity. A channel
operation must never fall back to that account identity when its signer is
unavailable. The proof has empty content, the current Unix
timestamp, and `relay` and `challenge` tags bound to that connection. It is sent
as `["AUTH", <event>]` only to the requesting relay, never published as `EVENT`.

Only an `OK` with a JSON boolean `true` and the exact proof ID authorizes retries.
The client retries only the rejected subscriptions and publications, once per
challenge, with their original filters, signed event contents, relay targets,
and live-location privacy provenance. A revoked or unsubscribed operation must
not be replayed. Publications remain bounded and pending until a relay returns
an acknowledgement; terminal rejections other than `auth-required:` are dropped.

Repeated challenges, rejected proofs, missing identities, and restricted
membership must not produce retry loops. Each socket allows at most 32 identities
and three proof attempts per identity, with challenges bounded to 4096 characters.
Disconnect, replacement, and panic discard socket authentication state; reconnect
requires a fresh proof.
Ordinary relays continue to work without authentication.

Authorization is tracked independently for each public key on a relay connection.
Accepting one key's proof must not replay a different key's requests. Gift-wrap
events retain an explicit originating signer because their ephemeral outer key
cannot identify the account or channel key. Queueing, reconnect, and retries
must preserve this context. Multiple keys use the existing shared WebSocket,
as permitted by NIP-42; this does not isolate channels at the transport level.
Authentication does not grant paid membership or bypass relay access policies.

The opt-in live probe compiles separately from the offline unit suite. It uses
the production relay manager with a derived identity for a clearly synthetic
channel in isolated test storage, requires the
chosen secure relay to appear in the bundled CSV, and verifies `auth-required`,
a matching accepted proof, and `EOSE` on the retried subscription. It publishes
no messages and reads no user storage or location data. Run it only when
live access to the selected relay has been authorized:

```sh
./gradlew --init-script tools/nostr/live-relay-auth.init.gradle \
  :app:testDebugUnitTest --tests 'com.bitchat.android.nostr.NostrRelayLiveAuthProbe' \
  -PnostrLiveRelay='wss://<relay-from-csv>'
```

To exercise the same contract on Android, use a newly created disposable emulator
with location disabled. The Android runner refuses an existing identity store.
It verifies that the synthetic channel key can be rederived from encrypted
storage before authentication. `PerformanceTestRunner` replaces the application
with a plain `Application`, keeping the probe isolated from normal startup.

```sh
./gradlew --init-script tools/nostr/live-relay-auth-android.init.gradle \
  :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$TEST_EMULATOR" install '<matching-debug-apk>'
adb -s "$TEST_EMULATOR" install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$TEST_EMULATOR" shell am instrument -w \
  -e class com.bitchat.android.nostr.NostrRelayLiveAuthInstrumentedProbe \
  -e nostrLiveRelay 'wss://<relay-from-csv>' -e disposableStorage true \
  com.bitchat.droid.test/com.bitchat.android.ui.PerformanceTestRunner
```

`TEST_EMULATOR` must select only that disposable emulator. Keep raw output local.
The live probe sources are compiled only by the explicit init scripts, so normal
unit and instrumented suites remain offline.

## Rewrite acceptance gate

From a configured Android development environment, run:

```sh
./gradlew clientRewriteContractTest
```

The task runs the new golden vectors and the complete existing unit suite. A
rewrite is compatible only when this gate passes. Tests should be ported
unchanged when package boundaries change; adapter façades are preferable to
weakening assertions.

## Device-only acceptance

Local JVM tests cannot prove Android radio and lifecycle behavior. Before
shipping a rewrite, run the following on at least two physical devices:

1. BLE discovery, connection, disconnect, reconnect, and multi-hop relay.
2. Runtime permission denial/retry for Bluetooth, location, notifications, and
   microphone.
3. Foreground-service survival with the screen off and after process recreation.
4. Cross-client Android/iOS exchange for announce, public/private text, delivery
   and read receipts, image/audio/file transfer, sync replay, and Nostr fallback.
5. Corrupt, duplicated, reordered, delayed, and partially delivered fragments.
6. Identity rotation, verification continuity, downgrade rejection, and recovery
   after stale Noise sessions.

Those scenarios belong in instrumented tests or a two-device interoperability
harness; they must not be represented as passing JVM mocks.
