# Announcement Gossip TLV (Direct Neighbors)

This document specifies an optional TLV extension to the BitChat `ANNOUNCE` message that allows peers to gossip which other peers they are currently connected to directly over Bluetooth. Implementations can use this to build a mesh topology view (nodes = peers, edges = direct connections).

Status: optional and backward-compatible.

## Layering Overview

- Outer packet: BitChat binary packet with `type = 0x01` (ANNOUNCE). Header is unchanged.
- Payload: A sequence of TLVs. Unknown TLVs MUST be ignored for forward compatibility.
- Signature: The packet MAY be signed using the Ed25519 public key carried in TLV `0x03`. The gossip TLV (if present) is part of the payload and therefore covered by the signature.

## TLV Format

Each TLV uses a compact layout:

- `type`: 1 byte
- `length`: 1 byte (0..255)
- `value`: `length` bytes

Existing TLVs (unchanged):

- `0x01` NICKNAME: UTF‑8 string (≤ 255 bytes)
- `0x02` NOISE_PUBLIC_KEY: Noise static public key bytes (typically 32 bytes for X25519)
- `0x03` SIGNING_PUBLIC_KEY: Ed25519 public key bytes (typically 32 bytes)

New TLV (optional):

- `0x04` DIRECT_NEIGHBORS: Concatenation of up to 10 peer IDs, each encoded as exactly 8 bytes. There is no inner count; the number of neighbors is `length / 8`. If `length` is not a multiple of 8, trailing partial bytes MUST be ignored.

### Peer ID Binary Encoding (8 bytes)

Peer IDs are represented as 8 raw bytes (16 hex chars) in “network order” (left‑to‑right):

- Take the peer ID hex string, lowercase/truncate to at most 16 hex chars.
- Convert each 2 hex chars to 1 byte from left to right.
- If fewer than 16 hex chars are available, pad the remaining bytes with `0x00` at the end to reach 8 bytes.

This matches the on‑wire 8‑byte `senderID`/`recipientID` encoding used in the BitChat packet header.

## Sender Behavior

- Build the base announcement payload by emitting TLVs `0x01`..`0x03` as usual.
- Optionally append TLV `0x04` with up to 10 unique, directly connected peer IDs.
  - Remove duplicates before encoding.
  - Order is arbitrary and not semantically significant.
- Sign the ANNOUNCE packet so the gossip TLV is covered (recommended):
  - Signature algorithm: Ed25519 using the key in TLV `0x03`.
  - Signature input: the binary packet encoding with the signature field omitted and the TTL normalized to `0`. This allows TTL to change during relays without invalidating the signature.
- The payload may be compressed per the base protocol; the gossip TLV is encoded prior to optional compression.

## Receiver Behavior

- Decompress payload if the packet’s compression flag is set, then parse TLVs in order.
- Parse TLVs `0x01`..`0x03` as usual; ignore any unknown TLVs.
- If a `0x04` TLV is present:
  - Interpret the value as `N = length / 8` peer IDs (ignore trailing non‑aligned bytes).
  - Each 8‑byte chunk is decoded back to a 16‑hex‑char peer ID string (lowercase).
  - De‑duplicate neighbors.
- Topology maintenance guidance (optional, but recommended for consistent behavior):
  - Maintain, for each announcing peer A, the last announcement timestamp and the neighbor list from TLV `0x04`.
  - When a newer announcement from A arrives (use the 8‑byte unsigned `timestamp` in the BitChat packet header), replace A’s previously recorded neighbor list with the new one. If older or equal, ignore the neighbor update.
  - Treat the neighbor list as a set of undirected edges `{A, B}` in your topology visualization; i.e., if A reports direct peers `[B, C]`, add edges A–B and A–C.

## Limits and Compatibility

- Max neighbors per TLV: 10. Senders MAY send fewer; receivers MUST accept any number `N ≥ 0` implied by `length / 8` up to the received `length`.
- Omission: If the TLV `0x04` is omitted, the announce remains valid. Peers can still chat and interoperate normally; the topology graph will just not include edges reported by that peer (other peers that include A in their neighbor lists can still introduce edges to A).
- Unknown TLVs MUST be ignored. This makes the extension safe for older implementations.

## Minimal Example (conceptual)

ANNOUNCE payload TLVs (concatenated):

- `01 [len=N] [UTF‑8 nickname]`
- `02 [len=32] [32 bytes X25519 pubkey]`
- `03 [len=32] [32 bytes Ed25519 pubkey]`
- `04 [len=8*M] [peerID1(8) || peerID2(8) || ... || peerIDM(8)]` (optional)

Where each `peerIDk(8)` is the 8‑byte binary form of the peer ID as specified above.

That’s the entire change; the outer packet header, message type, and relay/TTL behavior are unchanged.

