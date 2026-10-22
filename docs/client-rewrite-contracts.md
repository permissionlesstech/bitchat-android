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
| Nostr | Bech32, secp256k1 key derivation, NIP-01 event IDs/signatures, NIP-44 authenticated encryption, NIP-13 PoW, authenticated NIP-17 seals, 22h outbound envelope randomization | `ClientRewriteNostrContractTest`, `NostrProtocolTest` |
| Application state | Peer unions, canonical private conversations, chronological history, delivery/read behavior, media migration policy | `AppStateStoreTest`, `PrivateChatManagerTest`, `MediaSendingManagerMigrationTest` |

## Public and channel message admission

Public Mesh text retains its UTF-8 payload. Channel text uses the existing
binary chat envelope, preserving the channel and mentions. If that envelope
cannot be encoded (including the encoder's capacity limit and the 255-byte
UTF-8 channel-name limit), the sender must reject it; it must never fall back to public Mesh text.

A decoded broadcast envelope supplies content, channel and mentions only.
The receiver takes sender identity, nickname, message ID and timestamp from
its verified peer and outer packet, and clears private and relay metadata.
Encrypted channel envelopes retain their ciphertext and encryption flag for password unlock; without a matching
key, the visible content is an encrypted-message placeholder. Encrypted
envelopes without a channel are rejected. Inner fields cannot turn a
broadcast into a private conversation.
`ChannelMessagePayloadTest` and `MessageHandlerTest` enforce these rules;
`ClientRewriteWireContractTest` retains the literal chat payload vectors.

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

## Rewrite acceptance gate

From a configured Android development environment, run:

```sh
./gradlew clientRewriteContractTest
```

The task runs the new golden vectors and the complete existing unit suite. A
rewrite is compatible only when this gate passes. Tests should be ported
unchanged when package boundaries change; adapter façades are preferable to
weakening assertions.

## Password-protected channel admission

Channel names must fit the existing one-byte UTF-8 wire length without
truncation. An unencodable channel payload must be rejected, never published
as public mesh text. Broadcast envelopes take identity, nickname, message ID,
and timestamp from the verified outer packet; inner private and relay flags
cannot redirect a broadcast into a private conversation.

Historical encrypted channel text uses PBKDF2-HMAC-SHA256 (100,000 iterations,
32-byte key, UTF-8 channel name as salt), followed by AES-256-GCM with a
12-byte nonce prepended to ciphertext and its 16-byte tag. The existing chat
payload encryption flag substitutes those bytes for plaintext content. The
literal independently generated fixture in `ChannelManagerTest` pins this
format and its SHA-256 key commitment.

Password derivation and encryption cannot restore state or emit content after
a panic wipe, leave, or superseding key operation. Command-generated channel
text follows the same encryption policy as composed text. File packets do not
carry encrypted-channel context, so voice notes, images, and files in a
password-protected channel are rejected with a visible message.

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
