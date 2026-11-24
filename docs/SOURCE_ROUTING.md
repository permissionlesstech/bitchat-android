# Source-Based Routing for BitChat Packets (v2)

This document specifies the Source-Based Routing extension (v2) for the BitChat protocol. This upgrade enables efficient unicast routing across the mesh by allowing senders to specify an explicit path of intermediate relays.

**Status:** Implemented in Android and iOS. Backward compatible (v1 clients ignore routing data).

---

## 1. Protocol Versioning & Layering

To support source routing and larger payloads, the packet format has been upgraded to **Version 2**.

*   **Version 1 (Legacy):** 2-byte payload length limit. Ignores routing flags.
*   **Version 2 (Current):** 4-byte payload length limit. Supports Source Routing.

**Key Rule:** The `HAS_ROUTE (0x08)` flag is **only valid** if the packet `version >= 2`. Relays receiving a v1 packet must ignore this flag even if set.

---

## 2. Packet Structure Comparison

The following diagram illustrates the structural differences between a standard v1 packet and a source-routed v2 packet.

### V1 Packet (Legacy)
```text
+-------------------+---------------------------------------------------------+
| Fixed Header (14) | Variable Sections                                       |
+-------------------+----------+-------------+------------------+-------------+
| Ver: 1 (1B)       | SenderID | RecipientID | Payload          | Signature   |
| Type, TTL, etc.   | (8B)     | (8B)        | (Length in Head) | (64B)       |
| Len: 2 Bytes      |          | (Optional)  |                  | (Optional)  |
+-------------------+----------+-------------+------------------+-------------+
```

### V2 Packet (Source Routed)
```text
+-------------------+-----------------------------------------------------------------------------+
| Fixed Header (16) | Variable Sections                                                           |
+-------------------+----------+-------------+-----------------------+------------------+-------------+
| Ver: 2 (1B)       | SenderID | RecipientID | SOURCE ROUTE          | Payload          | Signature   |
| Type, TTL, etc.   | (8B)     | (8B)        | (Variable)            | (Length in Head) | (64B)       |
| Len: 4 Bytes      |          | (Required*) | Only if HAS_ROUTE=1   |                  | (Optional)  |
+-------------------+----------+-------------+-----------------------+------------------+-------------+
```

**(*) Note:** A `Route` can be attached to **any** packet type that has a `RecipientID` (flag `HAS_RECIPIENT` set).

### Fixed Header Differences

| Field | Size (v1) | Size (v2) | Description |
|---|---|---|---|
| **Version** | 1 byte | 1 byte | `0x01` vs `0x02` |
| **Payload Length** | **2 bytes** | **4 bytes** | `UInt32` in v2 to support large files. **Excludes** route/IDs/sig. |
| **Total Size** | **14 bytes** | **16 bytes** | V2 header is 2 bytes larger. |

---

## 3. Source Route Specification

The `Source Route` field is a variable-length list of **intermediate hops** that the packet must traverse.

*   **Location:** Immediately follows `RecipientID`.
*   **Structure:**
    *   `Count` (1 byte): Number of intermediate hops (`N`).
    *   `Hops` (`N * 8` bytes): Sequence of Peer IDs.

### Intermediate Hops Only
The route list MUST contain **only** the intermediate relays between the sender and the recipient.
*   **DO NOT** include the `SenderID` (it is already in the packet).
*   **DO NOT** include the `RecipientID` (it is already in the packet).

**Example:**
Topology: `Alice (Sender) -> Bob -> Charlie -> Dave (Recipient)`
*   Packet `SenderID`: Alice
*   Packet `RecipientID`: Dave
*   Packet `Route`: `[Bob, Charlie]` (Count = 2)

---

## 4. Topology Discovery (Gossip)

To calculate routes, nodes need a view of the network topology. This is achieved via a **Neighbor List** extension to the `IdentityAnnouncement` packet.

*   **Mechanism:** `IdentityAnnouncement` packets contain a TLV (Type-Length-Value) payload.
*   **New TLV Type:** `0x04` (Direct Neighbors).
*   **Content:** A list of Peer IDs that the announcing node is directly connected to.

**TLV Structure (Type 0x04):**
```text
[Type: 0x04] [Length: 1B] [Count: 1B] [NeighborID1 (8B)] [NeighborID2 (8B)] ...
```
Nodes receiving this TLV update their local mesh graph, linking the sender to the listed neighbors.

### Edge Verification (Two-Way Handshake)

To prevent spoofing and routing through stale connections, the Mesh Graph service implements a strict two-way handshake verification:

*   **Unconfirmed Edge:** If Peer A announces Peer B, but Peer B does *not* announce Peer A, the connection is treated as **unconfirmed**. Unconfirmed edges are visualized as dotted lines in debug tools but are **excluded** from route calculations.
*   **Confirmed Edge:** An edge is only valid for routing when **both** peers explicitly announce each other in their neighbor lists. This ensures that the connection is bidirectional and currently active from both perspectives.

---

## 5. Fragmentation & Source Routing

When a large source-routed packet (e.g., File Transfer) exceeds the MTU and requires fragmentation:

1.  **Version Inheritance:** All fragments MUST be marked as **Version 2**.
2.  **Route Inheritance:** All fragments MUST contain the **exact same Route field** as the parent packet.

**Why?** If fragments were sent as v1 packets or without routes, they would fall back to flooding, negating the bandwidth benefits of source routing for large data transfers.

---

## 6. Security & Signing

Source routing is fully secured by the existing Ed25519 signature scheme.

*   **Scope:** The signature covers the **entire packet structure** (Header + Sender + Recipient + Route + Payload).
*   **Verification:** The receiver verifies the signature against the `SenderID`'s public key.
*   **Integrity:** Any tampering with the route list by malicious relays will invalidate the signature, causing the packet to be dropped by the destination.

**Signature Input Construction:**
Serialize the packet exactly as transmitted, but temporarily set `TTL = 0` and remove the `Signature` bytes.

---

## 7. Relay Logic

When a node receives a packet **not** addressed to itself:

1.  **Check Route:**
    *   Is `Version >= 2`?
    *   Is `HAS_ROUTE` flag set?
    *   Is the route list non-empty?
2.  **If YES (Source Routed):**
    *   Find local Peer ID in the route list at index `i`.
    *   **Next Hop:** The peer at `i + 1`.
    *   **Last Hop:** If `i` is the last index, the Next Hop is the `RecipientID`.
    *   **Action:** Attempt to unicast (`sendToPeer`) to the Next Hop.
    *   **Fallback:** If the Next Hop is unreachable, **fall back to conservative broadcast/flood** (bypassing MPR suppression and neighbor pruning) to ensure delivery.
3.  **If NO (Broadcast / Flood):**
    *   **Strict 1-hop sync:** `REQUEST_SYNC` packets with `TTL <= 1` are never relayed.
    *   **Conservative flood classes:** Topology-healing control packets (`ANNOUNCE` for newly seen peers, neighbor-list changes, or stale/unknown originators; `LEAVE`), non-broadcast/unicast fallbacks (`NOISE_HANDSHAKE`, `NOISE_ENCRYPTED`, unicast `MESSAGE`/`FRAGMENT`/`FILE_TRANSFER`), and source-route fallbacks always relay and disable neighbor pruning (`skipNeighborPruning = true`).
    *   **Deterministic 2-hop MPR hub selection (zero wire changes):** For broadcast `MESSAGE`, `VOICE_FRAME`, unchanged periodic `ANNOUNCE` (1-covered, `k = 1`), and broadcast `FRAGMENT` / `FILE_TRANSFER` (2-covered, `k = 2`), when network size `> 3`, the immediate previous hop `P` is known from the current ingress link generation, and `P`'s local graph announcement is fresh (default `DEFAULT_EDGE_MAX_AGE_MS = 180s`):
        *   Compute `N1(P)` = fresh confirmed 1-hop neighbors of `P`, and `N2(P)` = fresh confirmed 1-hop neighbors of nodes in `N1(P)` excluding `{P} U N1(P)`.
        *   Step 1: Elect every `u in N1(P)` that is among the only `<= k` covers of some `w in N2(P)`.
        *   Step 2: While any `w in N2(P)` has fewer than `k` selected covers among available candidates, greedily elect the remaining `u in N1(P)` maximizing `(uncovered N2(P) reach, fresh confirmed degree, lexicographically smallest peerID)`.
        *   **Fail-safe rule:** A non-MPR node MUST still relay if (a) any active direct link on any transport has not yet mapped to a peer ID, (b) `P`'s announcement is missing or stale, or (c) the node has a direct neighbor `v` (other than `P` and packet origin) that is not in `N1(P)` and is not already `k`-covered by `P`'s selected MPRs (protecting legacy clients that omit TLV `0x04`, newly joined peers, and unconfirmed/stale links without collapsing `k`-covered suppression on confirmed `N2(P)` nodes).
    *   **Separate local-delivery vs. relay deduplication:** Local delivery deduplicates on first authenticated arrival, while relay tracking remembers MPR-suppressed packet IDs separately from already-relayed packet IDs. If an authenticated duplicate arrives later from an electing previous hop (and has not yet been relayed), the node relays it at most once without re-delivering it locally.
    *   **Previous-hop and origin neighbor pruning:** When relaying a non-conservative broadcast whose previous hop is known and fresh, transport broadcasters skip sending back to the incoming link, packet origin, previous hop, and any direct target that is a fresh confirmed neighbor of origin or previous hop. Unidentified target links are never pruned, and stale/unknown-previous-hop fallbacks disable neighbor pruning.
