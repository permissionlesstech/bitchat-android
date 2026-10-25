# Client rewrite compatibility contracts

This document defines the behavior a from-scratch BitChat client must preserve.
The JVM contract, protocol, security, mesh and state tests provide executable
checks for parts of this contract.

The remaining implementation work and milestone progress are tracked in
[test-implementation-plan.md](test-implementation-plan.md).

## Required contract layers

The listed tests cover parts of each layer; they do not verify every requirement
in the corresponding row.

| Layer | Required compatibility behavior | Selected tests (partial coverage) |
|---|---|---|
| Outer mesh packet | v1/v2 header widths, big-endian fields, flags, section order, route placement, signature placement, padding, compression, signing bytes | `BinaryProtocolTest`, `ClientRewriteWireContractTest` |
| Chat payload | Flag bits, millisecond timestamp, UTF-8 byte lengths, encrypted-content substitution, optional-field order | `ClientRewriteWireContractTest` |
| Inner payloads | Noise type bytes, private-message TLVs, peer-state TLVs, file-transfer TLVs, live-voice bursts, fragment header, sync request TLVs | `ClientRewriteWireContractTest`, `AuthenticatedPeerStateTest`, `PrivateMediaTransferPreparerTest`, `VoiceBurstPacketTest`, `FragmentManagerTest` |
| Identity/security | Announcement extensions, capability bitfield endianness, Noise static-key binding, handshake identity binding, signatures | `IdentityAnnouncementTest`, `NoiseSessionManagerIdentityBindingTest` |
| Sync/routing | Stable packet IDs, GCS bitstream, replay collapse, TTL handling, relay choice, confirmed graph edges | `GCSFilterTest`, `PacketRelayManagerTest`, `MeshGraphServiceTest`, `TransportBridgeServiceTest`, `AppStateStoreTest` |
| Nostr | Bech32, secp256k1 key derivation, NIP-01 event IDs/signatures, NIP-44 authenticated encryption, NIP-13 PoW, authenticated NIP-17 seals, 22h outbound envelope randomization | `NostrProtocolTest` (authenticated-seal cases and outbound timestamp bounds) |
| Application state | Peer unions, canonical private conversations, chronological history, delivery/read behavior, media migration policy | `AppStateStoreTest`, `PrivateChatManagerTest`, `MediaSendingManagerMigrationTest` |

The private-history clock-skew case in `AppStateStoreTest` expects arrival
order, which can differ from timestamp order. That test does not establish the
chronological-history requirement above.

## Golden-vector policy

Golden vectors compare literal externally visible bytes or hashes. Do not update
them merely because an implementation changed. Update a vector only when the
wire protocol is intentionally versioned and interoperating clients are updated
together.

Round-trip tests remain useful but are not sufficient on their own: an encoder
and decoder can share the same defect. Protocol round-trip tests must be paired with literal golden vectors for critical
externally visible formats. Round-trip success alone does not establish compatibility.

NIP-17 receivers should reserve safety slack beyond the maximum timestamp
randomization used by senders. Android's default seal and gift-wrap timestamp
randomization is up to 22h. iOS's DM subscription lookback is 24h, a two-hour
difference. `NostrClient.subscribeToPrivateMessages` requests a 48-hour lookback.
These intervals do not establish delivery under arbitrary delays or clock skew.

Geohash directory downloads and the weekly bundled-asset update use
`relays/online_relays_gps.csv` from the bitchat repo, the source used by iOS
`GeoRelayDirectory`. Android deduplicates parsed rows by a lowercase host key,
omits port 443, includes other ports in 1 through 65535, and
breaks distance ties by that key. `RelayDirectoryTest` covers endpoint normalization, duplicate removal and distance ties.
`RelayDirectoryAddressTest` covers address rejection and port boundaries;
`RelayDirectoryPortTest` covers integer-conversion overflow. Shared synthetic selection
vectors and the Android/Swift check commands are documented in
[the relay-selection test guide](../tools/relay-selection/README.md).

Matching selections depend on the loaded entries. Bundled and cached copies
can differ between clients; a configured source URL does not establish equal
snapshots or successful delivery. Parsing remains permissive: rows with fewer
than three fields or unparseable coordinates are skipped, and duplicate
endpoints keep the first accepted row. Directory addresses accept bare hostnames or
`wss`/`https` URLs with an empty or root path. Userinfo, queries, fragments,
non-root paths and unsupported schemes are skipped; hostname characters are
limited to ASCII letters, digits, dots and hyphens. iOS also checks hosts and coordinate ranges
and rejects a directory containing conflicting duplicate rows. For a host followed by a digit-only port, an integer-conversion failure skips
that row. `RelayDirectoryPortTest` covers oversized ports between valid rows.

Bech32 byte decoding must reject nonzero discarded bits and a residual whole
five-bit symbol. A valid checksum alone does not make byte padding canonical.
Human-readable prefixes contain 1–83 characters; Nostr's extended data payloads
are not subject to Bitcoin address length limits. `Bech32PaddingTest` pins these
rules with literal checksum-valid positive and negative vectors.

## Rewrite acceptance gate

From a configured Android development environment, run:

```sh
./gradlew clientRewriteContractTest
```

The task runs `:app:testDebugUnitTest`, including the wire contract tests.
It must pass before accepting a rewrite. Passing it does not establish Wear or physical-device compatibility. Tests should be ported
unchanged when package boundaries change; adapter façades are preferable to
weakening assertions.

## Device-only acceptance

Local JVM tests alone do not establish radio operation or lifecycle behavior
on physical Android devices. Before shipping a rewrite, run the following on
at least two physical devices:

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
