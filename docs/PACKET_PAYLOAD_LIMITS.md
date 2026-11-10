# Packet payload limits

Receive-side admission rules for the decoded payload of a mesh packet. They
match iOS `PacketPayloadLimits` (bitchat iOS #1719). Senders and the wire
format are unchanged; these rules only decide which frames a receiver decodes.

Implementation: `app/src/main/java/com/bitchat/android/protocol/PacketPayloadLimits.kt`,
enforced in `BinaryProtocol.decodeCore`.

## Why per type

A compressed frame declares its original size, and raw DEFLATE lets about 1 KB
on air stand for about 1 MB decoded. Fragment reassembly lets a large
uncompressed frame arrive as a stream of small fragments. Accepted packets are
held at their decoded size, relayed, and re-served by gossip sync, so a single
frame-wide ceiling turns airtime into memory on every node.

## Rules

1. The decoded payload of a packet of type `T` may be at most `cap(T)` bytes,
   whether it arrived compressed or not.
2. For a compressed payload, the declared original size is checked against
   `cap(T)` before any output buffer is allocated.
3. For a compressed payload, a declared original size greater than
   `1032 × compressedBytes` is rejected before inflating. 1032:1 is raw
   DEFLATE's hard expansion ceiling (every 258-byte match costs at least two
   bits), so no real stream can exceed it.
4. A frame that breaks a rule fails to decode and is dropped like any other
   malformed frame: it is not relayed or stored for sync.

## Caps

Caps are at least twice the largest payload any shipping iOS or Android encoder
produces for the type. Only media-carrying types keep the framed-file ceiling.

| Type | Value | Cap |
|---|---|---|
| `FILE_TRANSFER`, `NOISE_ENCRYPTED` | `0x22`, `0x11` | `MAX_PAYLOAD_LENGTH` (10 MiB) |
| `MESSAGE`, group message | `0x02`, `0x25` | 128 KiB |
| courier envelope, Nostr carrier | `0x04`, `0x28` | 64 KiB |
| `REQUEST_SYNC` | `0x21` | 8 KiB |
| `FRAGMENT` | `0x20` | 1,280 B |
| `ANNOUNCE`, `LEAVE`, `NOISE_HANDSHAKE`, `VOICE_FRAME`, board post, prekey bundle, ping, pong | `0x01`, `0x03`, `0x10`, `0x29`, `0x23`, `0x24`, `0x26`, `0x27` | 4 KiB |
| Any other value | | 128 KiB (a v1 frame stays relayable) |

Types marked by name only (group message, courier envelope, Nostr carrier,
board post, prekey bundle, ping, pong) are iOS-only today; Android caps them so
it can still relay them safely.

A new type that needs more than a v1 frame (65,535 bytes) must be added to this
table, on both platforms, before it ships, or current receivers drop it at
decode.

## Not covered here

iOS also bounds fragment reassembly by the frame its claimed type can fill and
gives the gossip stores byte budgets. Android has not ported those yet.

## Tests

- `PacketPayloadLimitsWireContractTest`: literal frames with real DEFLATE
  bodies at and one byte past the announce and message caps, the media
  exemption, and the 1032:1 rejection.
- `PacketPayloadLimitsTest`: every capped type at and past its cap, compressed
  and uncompressed.
