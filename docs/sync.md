# GCS Filter Sync (REQUEST_SYNC)

This documents Android's core REQUEST_SYNC profile. Nodes exchange ANNOUNCE and broadcast MESSAGE packets using Golomb-Coded Set (GCS) filters of recently seen packets. iOS also supports additional sync types and optional TLVs.

## Overview

- Each node maintains a rolling set of public BitChat packets it has seen recently:
  - Broadcast messages (MessageType.MESSAGE where recipient is broadcast)
  - Identity announcements (MessageType.ANNOUNCE)
  - Android defaults to 500 entries per archive. This debug setting also caps combined request-filter candidates; it does not cap response packets.
- Nodes do not maintain a rolling Bloom filter. Instead, they compute a GCS filter on demand when sending a REQUEST_SYNC.
- The receiver checks which packets are not in the sender’s filter and sends those packets back. For announcements, only the latest announcement per peerID is sent; for broadcast messages, all missing ones are sent.

Android emits sync requests and responses with TTL=0. Receive-side locality relies on TTL; REQUEST_SYNC and RSR are not independently excluded from relaying.

## Packet ID

To compare packets across peers, a deterministic packet ID is used:

- ID = first 16 bytes of SHA-256 over: [type:uint8 | senderID:8 bytes | timestamp:uint64 big-endian milliseconds | payload bytes]
- This yields a 128-bit ID used in the filter.

Implementation: `com.bitchat.android.sync.PacketIdUtil`.

## GCS Filter (On-demand)

Implementation: `com.bitchat.android.sync.GCSFilter`.

- Parameters (configurable):
  - size: 128–1024 bytes (default 400)
  - target false positive rate (FPR): default 1% (range 0.1%–5%)
- Derivations:
  - P = ceil(log2(1/FPR))
  - Maximum number of elements that fit into the filter is estimated as: N_max ≈ floor((8 * sizeBytes) / (P + 2))
    - This estimate is used to cap the set; the actual encoder will trim further if needed to stay within the configured size.
- What goes into the set:
  - Combine the following and sort by packet timestamp (descending):
    - Broadcast messages (MessageType.MESSAGE, 0x02)
    - The most recent ANNOUNCE per peer
  - Take at most `min(N_max, maxPacketsPerSync)` items from this ordered list.
  - Compute the 16-byte Packet ID (see below), then for hashing use the first 8 bytes of SHA‑256 over the 16‑byte ID.
  - Apply the hashing scheme below with M = max(1, N * 2^P), where N is the selected input count before hash deduplication. Empty filters use M=1 and empty data.
  - Deduplicate mapped values and sort ascending. Starting from previous=0, encode each delta x using q=(x-1)>>P one bits, a zero bit, then the P low bits of x-1. Pack MSB-first and zero-pad the final byte.

Hashing scheme (fixed for cross‑impl compatibility):
- Packet ID: first 16 bytes of SHA‑256 over [type | senderID | timestamp | payload].
- Android GCS hash: read the first 8 bytes of SHA-256 over the 16-byte Packet ID as a big-endian integer and clear the high bit. Compute h64 % M, replacing a zero result with one. Membership tests use the same mapping.

## REQUEST_SYNC Packet

MessageType: `REQUEST_SYNC (0x21)`

- Header: normal BitChat header with TTL indicating “local-only” semantics. Implementations SHOULD set TTL=0 to prevent any relay; neighbors still receive the packet over the direct link-layer.
- Payload: TLV with 16‑bit big‑endian length fields (type, length16, value)
  - 0x01: P (uint8) — Golomb‑Rice parameter
  - 0x02: M (uint32, big-endian) — hash range max(1, N * 2^P)
  - 0x03: data (opaque) — GCS bitstream (MSB‑first bit packing)

Notes:
- The GCS bitstream uses MSB‑first packing (bit 7 is the first bit in each byte).
- Receivers MUST reject filters with data length exceeding the local maximum (default 1024 bytes) to avoid DoS.

Encode/Decode implementation: `com.bitchat.android.model.RequestSyncPacket`.

## Behavior

Android sender behavior:
- Periodic: every 30 seconds, send REQUEST_SYNC with a freshly computed GCS snapshot to each directly connected peer (unicast; TTL=0 recommended; do not relay). Broadcast to immediate neighbors only while no direct peer is known.
- Initial per-peer: the Android Bluetooth path schedules a REQUEST_SYNC to the peer about one second after accepting a direct-link ANNOUNCE (unicast; TTL=0 recommended; do not relay).

Receiver behavior:
- Decode the REQUEST_SYNC payload and reconstruct the sorted set of mapped values using the provided P, M, and bitstream.
- For each locally stored public packet ID:
  - Compute h64(ID) % M, replace zero with one, and check if it is in the reconstructed set; if NOT present, send the original packet back with `ttl=0` and the RSR flag (`0x10` in the packet header) set, to the requester only.
  - For announcements, send only the latest announcement per (sender peerID).
  - For broadcast messages, send all missing ones.

Announcement retention and pruning (consensus targets; Android differs as noted below):
- Store one announcement per peerID for sync purposes. Android replaces it on each accepted announcement, without comparing timestamps.
- Age-out policy: announcements older than 60 seconds MUST be removed from the sync candidate set.
- Pruning cadence: run pruning every 15 seconds to drop expired announcements.
- LEAVE handling: upon receiving a LEAVE message from a peer, immediately remove that peer’s stored announcement from the sync candidate set.
- Stale/offline peer handling: when a peer is considered stale/offline (e.g., last announcement older than 60 seconds), immediately remove that peer’s stored announcement from the sync candidate set.

Important: original packets are sent unmodified to preserve original signatures (e.g., ANNOUNCE), except for two header fields that are outside the signed bytes: TTL, and the RSR flag (`0x10`), which marks the packet as a solicited sync response so a receiver applying a timestamp freshness window can exempt it. They MUST NOT be relayed beyond immediate neighbors. Implementations SHOULD send these response packets with TTL=0 (local-only) and, when possible, route them only to the requesting peer.

## Scope and Types Included

Included in sync:
- Public broadcast messages: `MessageType.MESSAGE` with BROADCAST recipient (or null recipient).
- Identity announcements: `MessageType.ANNOUNCE`.
- Packets from other peers and our own public packets are eligible sync candidates. Archive and filter capacities limit which packets are retained and represented.
- Consensus target: announcements included in the GCS MUST be at most 60 seconds old at the time of filter construction; older announcements are excluded by pruning. Android does not currently enforce this target (see below).

Not included:
- Private messages and MESSAGE packets addressed to a non-broadcast recipient.

## Android receive and archive policy

Android uses a 180-second announcement age limit at archival and cleanup,
with 60 seconds between cleanup sweeps. Filters and responses do not recheck
age, allowing expired announcements to remain candidates between sweeps.
`removeAnnouncementForPeer` removes the sender's stored messages too.

For signed MESSAGE, FILE_TRANSFER, VOICE_FRAME and LEAVE packets, Android uses the
live signing key when available, otherwise the key persisted by a completed
authenticated peer-state exchange over Noise. A signature must verify with the
selected key. Packet-type checks still apply, including LEAVE's age bound and
the live-peer and age checks for public voice frames. This fallback is not
restricted to REQUEST_SYNC responses.

The broadcast message handler can display an absent sender under its cached
nickname, or peer ID, when authenticated signing state is persisted. A present
peer with an unverified nickname remains rejected by that handler.

For archiving another sender's broadcast MESSAGE, the shared gossip manager
checks the registered transport lookups. If none reports that sender present,
the message is not archived for later sync. Own broadcasts bypass this check;
ANNOUNCE retention is unchanged. This is a presence check at receipt time, not a
permanent marker of departure: a returning sender can be archived again. After
an announcement is purged, its age can no longer trigger removal of messages
inserted later; capacity eviction and explicit removal remain available.

## Configuration (Debug Sheet)

Exposed under “sync settings” in the debug settings sheet:
- Max packets per sync (default 500)
- Max GCS filter size in bytes (default 400, min 128, max 1024)
- GCS target FPR in percent (default 1%, 0.1%–5%)
- Derived values (display only): P and the estimated maximum number of elements that fit into the filter.

Backed by `DebugPreferenceManager` getters and setters:
- `getSeenPacketCapacity` / `setSeenPacketCapacity`
- `getGcsMaxFilterBytes` / `setGcsMaxFilterBytes`
- `getGcsFprPercent` / `setGcsFprPercent`

## Android Integration

- New/updated types and classes:
  - `MessageType.REQUEST_SYNC` (0x21) in `BinaryProtocol.kt`
  - `RequestSyncPacket` in `model/RequestSyncPacket.kt`
  - `GCSFilter` and `PacketIdUtil` in `sync/`
  - `GossipSyncManager` in `sync/`
- `BluetoothMeshService` wires and starts the sync manager, schedules per-peer initial and periodic syncs, and forwards seen public packets (including our own) to the manager.
- `PacketProcessor` handles REQUEST_SYNC and forwards to `BluetoothMeshService` which responds via the sync manager with responses targeted only to the requester.

## Compatibility Notes

- The core GCS hashing and TLV layout above must match between peers for filter membership checks to agree.
- Responses set the RSR flag (`0x10`) in the packet header. The flag is excluded from the signing preimage on both platforms, like TTL, so an archived packet can be marked when served without invalidating its signature.
- REQUEST_SYNC and responses are local-only and MUST NOT be relayed. Implementations SHOULD use TTL=0 to prevent relaying. If an implementation requires TTL>0 for local delivery, it MUST still ensure that REQUEST_SYNC and responses are not relayed beyond direct neighbors (e.g., by special-casing these types in relay logic).

### Android receive behavior

Public broadcast MESSAGE packets normally need a timestamp within 120 seconds
of the receiver's clock. A marked response needs an open request to the neighbor
that delivered it, and may carry a message up to six hours old. The future bound
remains 120 seconds for requested history too. The signature must verify against
the author's live or persisted authenticated key.

Requests stay open for 60 seconds, measured with a monotonic elapsed clock.
Periodic requests target the known direct neighbors every 30 seconds. A broadcast
request used before neighbors are known does not open a response window.
Repeated requests can keep a neighbor's response window continuously open.
The registry records a request when it is handed to the send delegate; it does
not acknowledge delivery or bind replies to a particular filter or request ID.
Matching uses the current transport-to-peer mapping, not proof of authenticated
link ownership. Reassembled packets use the completing fragment's ingress source;
this does not establish that every fragment arrived from that neighbor.
Reassembly suppresses relaying but retains the original inner TTL locally for
legacy-response classification.

Older clients omit the RSR flag. An unmarked TTL-zero message from a requested
neighbor is eligible for the same age exception. Other unmarked public messages
use the two-minute window. A marked public message without a matching request is
rejected, including when its timestamp is recent.

The request check uses the delivering neighbor, which can differ from the author.
Bluetooth resolves the incoming device address through its peer map. Wi-Fi Aware
resolves the socket's peer alias. The shared MeshCore also requires that resolved
peer to be direct. A missing binding prevents the history exception.

This policy leaves private packets, ANNOUNCE, FILE_TRANSFER and VOICE_FRAME on
their existing validation paths. It can reject live messages when clocks differ
by more than two minutes and history received after a request expires. A requested
neighbor can still replay signed messages within the accepted age range.


## Consensus vs. Configurable

The following items require consensus across all implementations to ensure interoperability:

- Packet ID recipe: first 16 bytes of SHA‑256(type | senderID | timestamp | payload).
- GCS hashing function and zero-to-one mapping as specified above (v1), and MSB-first bit packing for the bitstream.
- Payload encoding: TLV with 16‑bit big‑endian lengths; TLV types 0x01 = P (uint8), 0x02 = M (uint32), 0x03 = data (opaque).
- Core request type: REQUEST_SYNC = 0x21, intended for direct-neighbor exchange. Android synchronizes ANNOUNCE and broadcast MESSAGE, retaining one announcement per sender peerID.

The following are requester‑defined and communicated or local policy (no global agreement required):

- GCS parameters: P and M are carried in the REQUEST_SYNC and must be used by the receiver for membership tests. The sender chooses size and FPR; receivers MUST cap accepted data length for DoS protection.
- Local storage policy: how many packets to consider and how you determine the “latest” announcement per peer.
- Sync cadence: initial request delay and periodic request interval. Request-filter size is bounded by the debug setting and filter capacity.

Validation and limits (recommended):

- Reject malformed REQUEST_SYNC payloads (e.g., P < 1, M <= 0, or data length too large for local limits).
- Practical bounds: data length in [0, 1024]; P in [1, 24]; M up to 2^32‑1.

Versioning:

- This document defines a fixed GCS hashing scheme (“v1”) with no explicit version field in the payload. Changing the hashing or ID recipe would require a new message or an additional TLV in a future revision; current deployments must adhere to the constants above.
