# Source-Based Routing for BitChat Packets

This document specifies an optional source-based routing extension to the BitChat packet format. A sender may attach a hop-by-hop route (list of peer IDs) to instruct relays on the intended path. Relays that support this feature will try to forward to the next hop directly; otherwise, they fall back to regular broadcast relaying.

Status: optional and backward-compatible.

## Layering Overview

- Outer packet: BitChat binary packet with unchanged fixed header (version/type/ttl/timestamp/flags/payloadLength).
- Flags: adds a new bit `HAS_ROUTE (0x08)`.
- Variable sections (when present, in order):
  1) `SenderID` (8 bytes)
  2) `RecipientID` (8 bytes) if `HAS_RECIPIENT`
  3) `Route` (if `HAS_ROUTE`): `count` (1 byte) + `count * 8` bytes hop IDs
  4) `Payload` (with optional compression preamble)
  5) `Signature` (64 bytes) if `HAS_SIGNATURE`

Unknown flags are ignored by older implementations (they will simply not see a route and continue broadcasting as before).

## Route Field Encoding

- Presence: Signaled by the `HAS_ROUTE (0x08)` bit in `flags`.
- Layout (immediately after optional `RecipientID`):
  - `count`: 1 byte (0..255)
  - `hops`: concatenation of `count` peer IDs, each encoded as exactly 8 bytes
- Peer ID encoding (8 bytes): same as used elsewhere in BitChat (16 hex chars → 8 bytes; left-to-right conversion; pad with `0x00` if shorter). This matches the on‑wire `senderID`/`recipientID` encoding.
- Size impact: `1 + 8*N` bytes, where `N = count`.
- Empty route: `HAS_ROUTE` with `count = 0` is treated as no route (relays ignore it).

## Sender Behavior

- Applicability: Intended for addressed packets (i.e., where `recipientID` is set and is not the broadcast ID). For broadcast packets, omit the route.
- Path computation: Use Dijkstra’s shortest path (unit weights) on your internal mesh topology to find a route from `src` (your peerID) to `dst` (recipient peerID). The hop list SHOULD include the full path `[src, ..., dst]`.
- Encoding: Set `HAS_ROUTE`, write `count = path.length`, then the 8‑byte hop IDs in order. Keep `count <= 255`.
- Signing: The route is covered by the Ed25519 signature (recommended):
  - Signature input is the canonical encoding with `signature` omitted and `ttl = 0` (TTL excluded to allow relay decrement) — same rule as base protocol.

## Relay Behavior

When receiving a packet that is not addressed to you:

1) If `HAS_ROUTE` is not set, or the route is empty, relay using your normal broadcast logic (subject to TTL/probability policies).
2) If `HAS_ROUTE` is set and your peer ID appears at index `i` in the hop list:
   - If there is a next hop at `i+1`, attempt a targeted unicast to that next hop if you have a direct connection to it.
     - If successful, do NOT broadcast this packet further.
     - If not directly connected (or the send fails), fall back to broadcast relaying.
   - If you are the last hop (no `i+1`), proceed with standard handling (e.g., if not addressed to you, do not relay further).

TTL handling remains unchanged: relays decrement TTL by 1 before forwarding (whether targeted or broadcast). If TTL reaches 0, do not relay.

## Receiver Behavior (Destination)

- This extension does not change how addressed packets are handled by the final recipient. If the packet is addressed to you (`recipientID == myPeerID`), process it normally (e.g., decrypt Noise payload, verify signatures, etc.).
- Signature verification MUST include the route field when present; route tampering will invalidate the signature.

## Compatibility

- Omission: If `HAS_ROUTE` is omitted, legacy behavior applies. Relays that don’t implement this feature will ignore the route entirely, because they won’t set or check `HAS_ROUTE`.
- Partial support: If any relay on the path cannot directly reach the next hop, it will fall back to broadcast relaying; delivery is still probabilistic like the base protocol.

## Minimal Example (conceptual)

- Header (fixed 13 bytes): unchanged.
- Variable sections (ordered):
  - `SenderID(8)`
  - `RecipientID(8)` (if present)
  - `HAS_ROUTE` set → `count=3`, `hops = [H0 H1 H2]` where each `Hk` is 8 bytes
  - Payload (optionally compressed)
  - Signature (64)

Where `H0` is the sender’s peer ID, `H2` is the recipient’s peer ID, and `H1` is an intermediate relay. The receiver verifies the signature over the packet encoding (with `ttl = 0` and `signature` omitted), which includes the `hops` when `HAS_ROUTE` is set.

## Operational Notes

- Routing optimality depends on the freshness and completeness of the topology your implementation has learned (e.g., via gossip of direct neighbors). Recompute routes as needed.
- Route length should be kept small to reduce overhead and the probability of missing a direct link at some hop.
- Implementations may introduce policy controls (e.g., disable source routing, cap max route length).

