# GCS Filter Sync (REQUEST_SYNC)

This document specifies the gossip-based synchronization feature for BitChat, inspired by Plumtree. It ensures eventual consistency of public packets (ANNOUNCE and broadcast MESSAGE) across nodes via periodic sync requests containing a compact Golomb‑Coded Set (GCS) of recently seen packets.

## Overview

- Each node maintains a rolling set of public BitChat packets it has seen recently:
  - Broadcast messages (MessageType.MESSAGE where recipient is broadcast)
  - Identity announcements (MessageType.ANNOUNCE)
  - Default retention is 100 recent packets (configurable in the debug sheet). This value is the maximum number of packets that are synchronized per request (across both types combined).
- Nodes do not maintain a rolling Bloom filter. Instead, they compute a GCS filter on demand when sending a REQUEST_SYNC.
- Every 30 seconds, a node sends a REQUEST_SYNC packet to all immediate neighbors (local only; not relayed).
- Additionally, 5 seconds after the first announcement from a newly directly connected peer is detected, a node sends a REQUEST_SYNC only to that peer (unicast; local only).
- The receiver checks which packets are not in the sender’s filter and sends those packets back. For announcements, only the latest announcement per peerID is sent; for broadcast messages, all missing ones are sent.

This synchronization is strictly local (not relayed), ensuring only immediate neighbors participate and preventing wide-area flooding while converging content across the mesh.

## Packet ID

To compare packets across peers, a deterministic packet ID is used:

- ID = first 16 bytes of SHA-256 over: [type | senderID | timestamp | payload]
- This yields a 128-bit ID used in the filter.

Implementation: `com.bitchat.android.sync.PacketIdUtil`.

## GCS Filter (On-demand)

Implementation: `com.bitchat.android.sync.GCSFilter`.

- Parameters (configurable):
  - size: 128–1024 bytes (default 256)
  - target false positive rate (FPR): default 1% (range 0.1%–5%)
- Derivations:
  - P = ceil(log2(1/FPR))
  - Maximum number of elements that fit into the filter is estimated as: N_max ≈ floor((8 * sizeBytes) / (P + 2))
    - This estimate is used to cap the set; the actual encoder will trim further if needed to stay within the configured size.
- What goes into the set:
  - Combine the following and sort by packet timestamp (descending):
    - Broadcast messages (MessageType 1)
    - The most recent ANNOUNCE per peer
  - Take at most `min(N_max, maxPacketsPerSync)` items from this ordered list.
  - Compute the 16-byte Packet ID (see below), then for hashing use the first 8 bytes of SHA‑256 over the 16‑byte ID.
  - Map each hash to [0, M) with M = N * 2^P; sort ascending and encode deltas with Golomb‑Rice parameter P.

Hashing scheme (fixed for cross‑impl compatibility):
- Packet ID: first 16 bytes of SHA‑256 over [type | senderID | timestamp | payload].
- GCS hash: h64 = first 8 bytes of SHA‑256 over the 16‑byte Packet ID, interpreted as an unsigned 64‑bit integer. Value = h64 % M.

## REQUEST_SYNC Packet

MessageType: `REQUEST_SYNC (0x21)`

- Header: normal BitChat header with TTL indicating “local-only” semantics. Implementations SHOULD set TTL=0 to prevent any relay; neighbors still receive the packet over the direct link-layer. For periodic sync, recipient is broadcast; for per-peer initial sync, recipient is the specific peer.
- Payload: TLV with 16‑bit big‑endian length fields (type, length16, value)
  - 0x01: P (uint8) — Golomb‑Rice parameter
  - 0x02: M (uint32) — hash range N * 2^P
  - 0x03: data (opaque) — GCS bitstream (MSB‑first bit packing)

Notes:
- The GCS bitstream uses MSB‑first packing (bit 7 is the first bit in each byte).
- Receivers MUST reject filters with data length exceeding the local maximum (default 1024 bytes) to avoid DoS.

Encode/Decode implementation: `com.bitchat.android.model.RequestSyncPacket`.

## Behavior

Sender behavior:
- Periodic: every 30 seconds, send REQUEST_SYNC with a freshly computed GCS snapshot, broadcast to immediate neighbors, and mark as local‑only (TTL=0 recommended; do not relay).
- Initial per-peer: upon receiving the first ANNOUNCE from a new directly connected peer, send a REQUEST_SYNC only to that peer after ~5 seconds (unicast; TTL=0 recommended; do not relay).

Receiver behavior:
- Decode the REQUEST_SYNC payload and reconstruct the sorted set of mapped values using the provided P, M, and bitstream.
- For each locally stored public packet ID:
  - Compute h64(ID) % M and check if it is in the reconstructed set; if NOT present, send the original packet back with `ttl=0` to the requester only.
  - For announcements, send only the latest announcement per (sender peerID).
  - For broadcast messages, send all missing ones.

Announcement retention and pruning (consensus):
- Store only the most recent announcement per peerID for sync purposes.
- Age-out policy: announcements older than 60 seconds MUST be removed from the sync candidate set.
- Pruning cadence: run pruning every 15 seconds to drop expired announcements.
- LEAVE handling: upon receiving a LEAVE message from a peer, immediately remove that peer’s stored announcement from the sync candidate set.
- Stale/offline peer handling: when a peer is considered stale/offline (e.g., last announcement older than 60 seconds), immediately remove that peer’s stored announcement from the sync candidate set.

Important: original packets are sent unmodified to preserve original signatures (e.g., ANNOUNCE). They MUST NOT be relayed beyond immediate neighbors. Implementations SHOULD send these response packets with TTL=0 (local-only) and, when possible, route them only to the requesting peer without altering the original packet contents.

## Scope and Types Included

Included in sync:
- Public broadcast messages: `MessageType.MESSAGE` with BROADCAST recipient (or null recipient).
- Identity announcements: `MessageType.ANNOUNCE`.
- Both packets produced by other peers and packets produced by the requester itself MUST be represented in the requester’s GCS; the responder MUST track and consider its own produced public packets as candidates to return when they are missing on the requester.
- Announcements included in the GCS MUST be at most 60 seconds old at the time of filter construction; older announcements are excluded by pruning.

Not included:
- Private messages and any packets addressed to a non-broadcast recipient.

## Configuration (Debug Sheet)

Exposed under “sync settings” in the debug settings sheet:
- Max packets per sync (default 100)
- Max GCS filter size in bytes (default 256, min 128, max 1024)
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
- `BluetoothMeshService` wires and starts the sync manager, schedules per-peer initial (unicast) and periodic (broadcast) syncs, and forwards seen public packets (including our own) to the manager.
- `PacketProcessor` handles REQUEST_SYNC and forwards to `BluetoothMeshService` which responds via the sync manager with responses targeted only to the requester.

## Compatibility Notes

- GCS hashing and TLV structures are fully specified above; other implementations should use the same hashing scheme and payload layout for interoperability.
- REQUEST_SYNC and responses are local-only and MUST NOT be relayed. Implementations SHOULD use TTL=0 to prevent relaying. If an implementation requires TTL>0 for local delivery, it MUST still ensure that REQUEST_SYNC and responses are not relayed beyond direct neighbors (e.g., by special-casing these types in relay logic).

## Consensus vs. Configurable

The following items require consensus across all implementations to ensure interoperability:

- Packet ID recipe: first 16 bytes of SHA‑256(type | senderID | timestamp | payload).
- GCS hashing function and mapping to [0, M) as specified above (v1), and MSB‑first bit packing for the bitstream.
- Payload encoding: TLV with 16‑bit big‑endian lengths; TLV types 0x01 = P (uint8), 0x02 = M (uint32), 0x03 = data (opaque).
- Packet type and scope: REQUEST_SYNC = 0x21; local-only (not relayed); only ANNOUNCE and broadcast MESSAGE are synchronized; ANNOUNCE de‑dupe is “latest per sender peerID”.

The following are requester‑defined and communicated or local policy (no global agreement required):

- GCS parameters: P and M are carried in the REQUEST_SYNC and must be used by the receiver for membership tests. The sender chooses size and FPR; receivers MUST cap accepted data length for DoS protection.
- Local storage policy: how many packets to consider and how you determine the “latest” announcement per peer.
- Sync cadence: how often to send REQUEST_SYNC and initial delay after new neighbor connection; whether to use unicast for initial per-peer sync versus broadcast for periodic sync. The number of packets included is bounded by the debug setting and filter capacity.

Validation and limits (recommended):

- Reject malformed REQUEST_SYNC payloads (e.g., P < 1, M <= 0, or data length too large for local limits).
- Practical bounds: data length in [0, 1024]; P in [1, 24]; M up to 2^32‑1.

Versioning:

- This document defines a fixed GCS hashing scheme (“v1”) with no explicit version field in the payload. Changing the hashing or ID recipe would require a new message or an additional TLV in a future revision; current deployments must adhere to the constants above.
