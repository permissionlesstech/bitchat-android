# Peer ID Rotation: Android Phase 1

This document records Android's implementation and review of the cross-platform
peer-ID rotation proposal. Phase 1 is intentionally additive: Android advertises
capability bit 14, parses message type `0x2C`, and provides tested cryptographic
primitives. It still emits v1 announcements and ignores v2 announcements after
strict parsing. No rotating identity is used by the shipping mesh yet.

## Android review

- **O1 — one-hour epochs:** accept as the first interoperable constant. It is a
  reasonable compromise for rollout, and pinning it now is better than adding
  negotiation before operational data exists. Changing it later is a protocol
  revision because it moves every vector.
- **O4 — unsigned v2 announcements:** acceptable only with the proposed trust
  boundary. A parsed announcement must not establish liveness or enter the people
  list until a recognition-tag match or a completed Noise handshake. Phase 1
  therefore parses and discards it, and does not gossip-relay it.
- **O5 — rotation during live sessions:** prefer migration at the epoch boundary,
  but do not implement it until stable fingerprint-keyed session and durable-state
  migration exists. Indefinite deferral would create the strongest tracking handle
  for the most active peers.
- **O7 — trailing bytes:** Android's outer `BinaryProtocol` decoder currently
  accepts bytes after the declared packet fields; an executable test pins this.
  This does not make unilateral padding changes safe because padding participates
  in signed canonical bytes. Padding changes still need capability/version gating.

## Reproduced section 7 vectors

The Android JVM tests reproduce the three published vectors directly from the
specification:

```text
rotationSecret = fb82dfec0c0a2a4677beca44e2f72c80e7c5de773dd5fce6ee47af83d3c25f09
peerID(epoch=100) = f7c08c528506a374
tag A->B = 4568f61d61d6cbfb
tag B->A = 5313c7731f629959
```

The tests also cover a real two-sided X25519 exchange, directional tags,
consecutive epochs, the `epoch-1/current/epoch+1` window, fixed 64-byte tag
blocks, slot-independent matching, and fixed-width binding messages.

## Proposed joint vectors

These vectors were generated independently from the prose with Python standard
hash/HMAC primitives plus the `cryptography` Ed25519/X25519 primitives. They do
not depend on the iOS implementation.

### Binding signature

Inputs:

```text
Ed25519 private seed = 0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20
epoch = 100
peer ID = f7c08c528506a374
Noise X25519 public key derived from private bytes 01..20 =
07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c
```

Canonical message:

```text
626974636861742d7065657269642d62696e64696e672d763100000064f7c08c528506a37407a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c
```

Ed25519 signature:

```text
5b1235d4fd4ff0bdef76007578dc83141e34a2014f249aeb5df3e50c7d30199b2fe1c92f7a3674a6170a1b8db3e9213aeac6fa3690fb4b5e7e4432ca69d28a0e
```

### Full announceV2 packet

The packet uses version 1, type `0x2C`, TTL 3, timestamp `1700000000000`, no
flags or signature, sender `f7c08c528506a374`, epoch 100, the two published
directional tags followed by padding bytes `00..2f`, capability bit 14 encoded
minimal little-endian as `0040`, and no geohash.

```text
012c030000018bcfe5680000004cf7c08c528506a37401040000006402404568f61d61d6cbfb5313c7731f629959000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f03020040
```

The deterministic padding is only for the vector. Production tag padding must
use uniformly random bytes and tag order.
