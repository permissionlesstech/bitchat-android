# Source-Based Routing for BitChat Packets

This document specifies an optional source-based routing extension to the BitChat packet format. A sender may attach a hop-by-hop route (list of peer IDs) to instruct relays on the intended path. Relays that support this feature will try to forward to the next hop directly; otherwise, they fall back to regular broadcast relaying.

Status: optional and backward-compatible.

## Layering Overview

- Outer packet: BitChat binary packet with unchanged fixed header (version/type/ttl/timestamp/flags/payloadLength).
- Flags: adds a new bit `HAS_ROUTE (0x08)`. This flag is **only valid for packet version >= 2**.
- Variable sections (when present, in order):
  1) `SenderID` (8 bytes)
  2) `RecipientID` (8 bytes) if `HAS_RECIPIENT`
  3) `Route` (if `HAS_ROUTE` AND version >= 2): `count` (1 byte) + `count * 8` bytes hop IDs
  4) `Payload` (with optional compression preamble)
  5) `Signature` (64 bytes) if `HAS_SIGNATURE`

Unknown flags are ignored by older implementations. For v1 packets, the `HAS_ROUTE` flag MUST be ignored even if set, and the route field MUST NOT be present. This ensures strict backward compatibility.

## Detailed Packet Structure (v1 vs v2)

The Bitchat packet structure is designed to be compact and efficient for BLE transmission.

### Fixed Header
The fixed header is present in all packets. Its size depends on the version.

| Field | Size (v1) | Size (v2) | Description |
|---|---|---|---|
| Version | 1 byte | 1 byte | Protocol version (`0x01` or `0x02`). |
| Type | 1 byte | 1 byte | Message Type (e.g., `0x01` Announce, `0x02` Message). |
| TTL | 1 byte | 1 byte | Time-To-Live (hop limit). |
| Timestamp | 8 bytes | 8 bytes | `UInt64` (big-endian) creation time (ms since epoch). |
| Flags | 1 byte | 1 byte | Bitmask: `HAS_RECIPIENT(0x01)`, `HAS_SIGNATURE(0x02)`, `IS_COMPRESSED(0x04)`, `HAS_ROUTE(0x08)`. |
| Payload Length | **2 bytes** | **4 bytes** | `UInt16` (v1) or `UInt32` (v2) length of the *Payload* section only. **Does NOT include route or other headers.** |
| **Total Header** | **14 bytes** | **16 bytes** | |

### Variable Sections (In Order)

These fields follow the fixed header immediately.

1.  **Sender ID** (Fixed 8 bytes)
    *   Present in ALL packets.
    *   Derived from the sender's public key.

2.  **Recipient ID** (Optional, 8 bytes)
    *   Present only if `HAS_RECIPIENT` flag is set.
    *   Target peer ID for addressed messages.

3.  **Source Route** (Optional, Variable Length)
    *   **Condition:** Present **ONLY** if `HAS_ROUTE` flag is set **AND** `Version >= 2`.
    *   **Structure:**
        *   `Count` (1 byte): Number of hops (`N`).
        *   `Hops` (`N * 8` bytes): Sequence of 8-byte Peer IDs.
    *   **Note:** The size of this field (`1 + 8*N` bytes) is **NOT** included in the `Payload Length` field in the fixed header. It exists structurally between the Recipient ID and the Payload.

4.  **Payload** (Variable Length)
    *   Size is exactly the value specified in the `Payload Length` field of the fixed header.
    *   Contains the application data (e.g., encrypted message, announcement TLVs).
    *   If `IS_COMPRESSED` flag is set, the first 2 bytes are the original uncompressed size (UInt16), followed by the compressed bytes.

5.  **Signature** (Optional, 64 bytes)
    *   Present only if `HAS_SIGNATURE` flag is set.
    *   Ed25519 signature covering the entire packet (with TTL=0 and Signature excluded).

---

## Route Field Encoding

- Presence: Signaled by the `HAS_ROUTE (0x08)` bit in `flags` **AND** `version >= 2`.
- Layout (immediately after optional `RecipientID`):
  - `count`: 1 byte (0..255)
  - `hops`: concatenation of `count` peer IDs, each encoded as exactly 8 bytes
- Peer ID encoding (8 bytes): same as used elsewhere in BitChat (16 hex chars → 8 bytes; left-to-right conversion; pad with `0x00` if shorter). This matches the on‑wire `senderID`/`recipientID` encoding.
- Size impact: `1 + 8*N` bytes, where `N = count`.
- Empty route: `HAS_ROUTE` with `count = 0` is treated as no route (relays ignore it).

## Sender Behavior

- Applicability: Intended for addressed packets (i.e., where `recipientID` is set and is not the broadcast ID). For broadcast packets, omit the route.
- Path computation: Use Dijkstra’s shortest path (unit weights) on your internal mesh topology to find a route from the sender (your peerID) to the recipient (the destination peerID). The `BitchatPacket` already contains dedicated `senderID` and `recipientID` fields. The `Route` field's `hops` list **SHOULD** contain the sequence of intermediate peer IDs that the packet should traverse. It **SHOULD NOT** duplicate the `senderID` or `recipientID` if they are already present in the `BitchatPacket`'s dedicated fields. Instead, the `hops` list represents the explicit path *between* the sender and recipient, starting from the first relay and ending with the last relay before the recipient.
- Encoding: Ensure the packet `version` is set to 2 or higher. Set `HAS_ROUTE`, write `count = path.length`, then the 8‑byte hop IDs in order. Keep `count <= 255`.
- Signing: The route is covered by the Ed25519 signature (recommended):
  - Signature input is the canonical encoding with `signature` omitted and `ttl = 0` (TTL excluded to allow relay decrement) — same rule as base protocol.

## Relay Behavior

When receiving a packet that is not addressed to you:

1) If `HAS_ROUTE` is not set, or the route is empty, or the packet `version < 2`, relay using your normal broadcast logic (subject to TTL/probability policies).
2) If `HAS_ROUTE` is set AND `version >= 2`:
   - **Route Sanity Check**: Before processing, the relay **MUST** validate the route. If the route contains duplicate hops (i.e., the same peer ID appears more than once), the packet **MUST** be dropped to prevent loops.
   - If your peer ID appears at index `i` in the hop list:
     - If there is a next hop at `i+1`, attempt a targeted unicast to that next hop if you have a direct connection to it.
       - If successful, do NOT broadcast this packet further.
       - If not directly connected (or the send fails), fall back to broadcast relaying.
     - If you are the last hop (no `i+1`), the packet has reached the end of its explicit route. The relay should then attempt to deliver it to the final `recipientID` if directly connected, but SHOULD NOT relay it further as a broadcast.

TTL handling remains unchanged: relays decrement TTL by 1 before forwarding (whether targeted or broadcast). If TTL reaches 0, do not relay.

## Receiver Behavior (Destination)

- This extension does not change how addressed packets are handled by the final recipient. If the packet is addressed to you (`recipientID == myPeerID`), process it normally (e.g., decrypt Noise payload, verify signatures, etc.).
- Signature verification MUST include the route field when present; route tampering will invalidate the signature.

## Compatibility

- Omission: If `HAS_ROUTE` is omitted, legacy behavior applies. Relays that don’t implement this feature will ignore the route entirely.
- Version Constraint: Implementations MUST NOT parse or act on the `Route` field in v1 packets, even if the `HAS_ROUTE` flag is set. This prevents potential parsing ambiguities with legacy clients.
- Partial support: If any relay on the path cannot directly reach the next hop, it will fall back to broadcast relaying; delivery is still probabilistic like the base protocol.

## Minimal Example (conceptual)

- Header (fixed 13 bytes): unchanged.
- Variable sections (ordered):
  - `SenderID(8)`
  - `RecipientID(8)` (if present)
  - `HAS_ROUTE` set → `count=1`, `hops = [H1]` where `H1` is 8 bytes
  - Payload (optionally compressed)
  - Signature (64)

In this example, `SENDER_ID` is the sender, `RECIPIENT_ID` is the final recipient, and `H1` is the single intermediate relay. The `hops` list explicitly defines the path *between* the sender and recipient. The receiver verifies the signature over the packet encoding (with `ttl = 0` and `signature` omitted), which includes the `hops` when `HAS_ROUTE` is set.

## Operational Notes

- Routing optimality depends on the freshness and completeness of the topology your implementation has learned (e.g., via gossip of direct neighbors). Recompute routes as needed.
- Route length should be kept small to reduce overhead and the probability of missing a direct link at some hop.
- Implementations may introduce policy controls (e.g., disable source routing, cap max route length).

