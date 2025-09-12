# Bloom Filter Sync (REQUEST_SYNC)

This document specifies the gossip-based synchronization feature for BitChat, inspired by Plumtree. It ensures eventual consistency of public packets (ANNOUNCE and broadcast MESSAGE) across nodes via periodic sync requests containing a Bloom filter of recently seen packets.

## Overview

- Each node maintains a rolling set of public BitChat packets it has seen recently:
  - Broadcast messages (MessageType.MESSAGE where recipient is broadcast)
  - Identity announcements (MessageType.ANNOUNCE)
  - Default retention is 100 recent broadcast messages (configurable in the debug sheet).
- Nodes maintain a rotating Bloom filter summarizing packet IDs of all seen packets.
- Every 30 seconds, a node sends a REQUEST_SYNC packet to all immediate neighbors (local only; not relayed).
- Additionally, 5 seconds after the first announcement from a newly directly connected peer is detected, a node sends a REQUEST_SYNC only to that peer (unicast; local only).
- The receiver checks which packets are not in the Bloom filter and sends those packets back. For announcements, only the latest announcement per peerID is sent; for broadcast messages, all missing ones are sent.

This synchronization is strictly local (not relayed), ensuring only immediate neighbors participate and preventing wide-area flooding while converging content across the mesh.

## Packet ID

To compare packets across peers, a deterministic packet ID is used:

- ID = first 16 bytes of SHA-256 over: [type | senderID | timestamp | payload]
- This yields a 128-bit ID used in the Bloom filter.

Implementation: `com.bitchat.android.sync.PacketIdUtil`.

## Bloom Filter Rotation

Implementation: `com.bitchat.android.sync.SeenPacketsBloomFilter`.

- Parameters (configurable):
  - max size: up to 256 bytes (default 256)
  - target false positive rate (FPR): default 1%
- The filter computes the optimal number of hash functions k and the working capacity n for the target FPR.
- Two filters are maintained for seamless rotation:
  - When the active filter reaches 50% of its capacity (by insert count), a standby filter starts accepting new inserts as well (both filters receive inserts).
  - When the active filter reaches full capacity, it is cleared and swapped with the standby; the new standby is reset.
- The REQUEST_SYNC always carries the active filter snapshot.

Hashing scheme (fully specified for cross-impl compatibility):
- Let x be the 16-byte packet ID.
- h1 seed: 1469598103934665603 (FNV-1a 64-bit offset basis)
- h2 seed: 0x27d4eb2f165667c5 (fixed 64-bit constant)
- For each byte b in x:
  - h1 = (h1 XOR b) * 1099511628211 (FNV-1a 64-bit prime)
  - h2 = (h2 XOR b) * 0x100000001B3 (FNV-1 64-bit prime)
- Bit positions: for i in [0, k), index_i = ((h1 + i*h2) & 0x7fff_ffff_ffff_ffff) % mBits
- Bit packing: MSB-first within each byte (bit at index maps to byteIndex = index/8, bitIndex = 7 - (index%8)).

Filter parameters:
- mBytes: total bytes (mBits = mBytes*8)
- k: number of hash functions
- Capacity estimate n for target FPR p: n ≈ -(mBits * (ln 2)^2) / ln p
- Optimal k ≈ (mBits / n) * ln 2 (rounded up; min 1)

## REQUEST_SYNC Packet

MessageType: `REQUEST_SYNC (0x21)`

- Header: normal BitChat header with TTL indicating “local-only” semantics. Implementations SHOULD set TTL=0 to prevent any relay; neighbors still receive the packet over the direct link-layer. For periodic sync, recipient is broadcast; for per-peer initial sync, recipient is the specific peer.
- Payload: TLV with 16-bit big-endian length fields (type, length16, value)
  - 0x01: mBytes (uint16)
  - 0x02: k (uint8)
  - 0x03: bloom filter bits (opaque byte array of length mBytes)

Notes:
- The bloom bit array uses MSB-first packing (bit 7 is the first bit in each byte).
- mBytes must equal the length of the bit array. Receivers should validate bounds before reading.

Encode/Decode implementation: `com.bitchat.android.model.RequestSyncPacket`.

## Behavior

Sender behavior:
- Periodic: every 30 seconds, send REQUEST_SYNC with the active Bloom filter snapshot, broadcast to immediate neighbors, and mark as local-only (TTL=0 recommended; do not relay).
- Initial per-peer: upon receiving the first ANNOUNCE from a new directly connected peer, send a REQUEST_SYNC only to that peer after ~5 seconds (unicast; TTL=0 recommended; do not relay).

Receiver behavior:
- Decode the REQUEST_SYNC payload and build a membership checker using the provided mBytes, k, and bit array.
- For each locally stored public packet ID:
  - If the ID is NOT contained in the requester’s Bloom filter, send the original packet back with `ttl=1`.
  - For announcements, send only the latest announcement per (sender peerID).
  - For broadcast messages, send all missing ones.

Important: original packets are sent unmodified to preserve original signatures (e.g., ANNOUNCE). They MUST NOT be relayed beyond immediate neighbors. Implementations SHOULD send these response packets with TTL=0 (local-only) and, when possible, route them only to the requesting peer without altering the original packet contents.

## Scope and Types Included

Included in sync:
- Public broadcast messages: `MessageType.MESSAGE` with BROADCAST recipient (or null recipient).
- Identity announcements: `MessageType.ANNOUNCE`.
- Both packets produced by other peers and packets produced by the requester itself MUST be represented in the requester’s Bloom filter; the responder MUST track and consider its own produced public packets as candidates to return when they are missing on the requester.

Not included:
- Private messages and any packets addressed to a non-broadcast recipient.

## Configuration (Debug Sheet)

Exposed under “sync settings” in the debug settings sheet:
- Recent packets kept (default 100)
- Bloom filter size in bytes (default 256, min 16, max 256)
- Bloom target FPR in percent (default 1%, 0.1%–5%)

Backed by `DebugPreferenceManager` getters and setters:
- `getSeenPacketCapacity` / `setSeenPacketCapacity`
- `getBloomFilterBytes` / `setBloomFilterBytes`
- `getBloomFilterFprPercent` / `setBloomFilterFprPercent`

## Android Integration

- New types and classes:
  - `MessageType.REQUEST_SYNC` (0x21) in `BinaryProtocol.kt`
  - `RequestSyncPacket` in `model/RequestSyncPacket.kt`
  - `SeenPacketsBloomFilter` and `PacketIdUtil` in `sync/`
  - `GossipSyncManager` in `sync/`
- `BluetoothMeshService` wires and starts the sync manager, schedules per-peer initial (unicast) and periodic (broadcast) syncs, and forwards seen public packets (including our own) to the manager.
- `PacketProcessor` handles REQUEST_SYNC and forwards to `BluetoothMeshService` which responds via the sync manager with responses targeted only to the requester.

## Compatibility Notes

- Bloom filter hashing and TLV structures are fully specified above; other implementations should use the same hashing scheme and payload layout for interoperability.
- REQUEST_SYNC and responses are local-only and MUST NOT be relayed. Implementations SHOULD use TTL=0 to prevent relaying. If an implementation requires TTL>0 for local delivery, it MUST still ensure that REQUEST_SYNC and responses are not relayed beyond direct neighbors (e.g., by special-casing these types in relay logic).

## Consensus vs. Configurable

The following items require consensus across all implementations to ensure interoperability:

- Packet ID recipe: first 16 bytes of SHA‑256(type | senderID | timestamp | payload).
- Bloom hashing algorithm and constants: seeds, per‑byte mixing, index formula, and MSB‑first bit packing as specified above.
- Payload encoding: TLV with 16‑bit big‑endian lengths; TLV types
  - 0x01 = mBytes (uint16), 0x02 = k (uint8), 0x03 = bits (opaque mBytes).
- Packet type and scope: REQUEST_SYNC = 0x21; local-only (not relayed); only ANNOUNCE and broadcast MESSAGE are synchronized; ANNOUNCE de‑dupe is “latest per sender peerID”.

The following are requester‑defined and communicated or local policy (no global agreement required):

- Bloom capacity parameters: mBytes and k are carried in the REQUEST_SYNC and must be used by the receiver for membership tests.
- Local storage policy: how many recent broadcast messages to cache; how you determine the “latest” announcement per peer.
- Bloom rotation strategy: when to start/stop rotating and how many filters to maintain.
- Sync cadence: how often to send REQUEST_SYNC and initial delay after new neighbor connection; whether to use unicast for initial per-peer sync versus broadcast for periodic sync.

Validation and limits (recommended):

- Reject malformed REQUEST_SYNC payloads (e.g., k < 1, mBytes != bits.length, or mBytes too large for local limits).
- Practical bounds: mBytes in [16, 256], k in [1, 16]. Implementations may cap these to avoid memory/CPU abuse.

Versioning:

- This document defines a fixed hashing scheme (“v1”) with no version field in the payload. Changing the hashing or ID recipe would require a new message or an additional TLV in a future revision; current deployments must adhere to the constants above.
