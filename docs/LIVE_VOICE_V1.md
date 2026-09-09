# Live voice v1 wire protocol

This document defines the interoperable live push-to-talk protocol used by
Android, Wear OS, and iOS clients. Live packets are ephemeral and best-effort;
the ordinary voice note sent when the user releases the microphone remains the
reliable, persistent fallback.

All multi-byte integers in this document use network byte order (big-endian).

## Type assignments

| Layer | Name | Value | Purpose |
| --- | --- | --- | --- |
| Outer mesh packet | `MessageType.VOICE_FRAME` | `0x29` | Signed public-mesh burst packet |
| Noise inner payload | `NoisePayloadType.VOICE_FRAME` | `0x08` | Encrypted direct-message burst packet |
| Outer private packet | `MessageType.NOISE_ENCRYPTED` | `0x11` | Existing recipient-directed Noise envelope |

These values are canonical. Implementations must not emit live voice under a
different outer or inner type.

## Transport envelopes

### Public mesh

The sender emits a normal mesh packet with:

- version `1`;
- type `VOICE_FRAME (0x29)`;
- a broadcast recipient (either no recipient field or the eight-byte all-`FF`
  broadcast value; receivers accept both encodings);
- the encoded `VoiceBurstPacket` as its payload;
- origin TTL `7`; and
- an Ed25519 signature over the canonical outer packet signing bytes.

Receivers accept public live voice only from a known peer whose announcement
has established a verified nickname and signing key. They must reject an
unsigned packet, an invalid signature, a non-broadcast recipient, or a packet
whose timestamp is more than 30 seconds from the receiver's current time. A
rejected public voice packet must not be relayed.

Public live voice is not added to gossip sync. Older clients ignore the unknown
`0x29` type and therefore remain compatible.

### Direct message

The sender requires an established Noise session. It encodes:

```text
[NoisePayloadType.VOICE_FRAME: 0x08][VoiceBurstPacket]
```

and encrypts that value in a recipient-directed `NOISE_ENCRYPTED (0x11)` mesh
packet. The existing Noise session provides confidentiality and authentication.
Intermediate relays see only the ordinary Noise envelope and route it according
to the existing directed-packet rules.

There are no delivery acknowledgements or retransmissions for live packets.
If no established session exists when recording begins, the client records and
sends only the ordinary voice note.

## `VoiceBurstPacket`

Every packet in one press-and-hold gesture shares an opaque, randomly generated
eight-byte burst ID.

```text
+------------+-------------+-------------+------------------+
| burstID    | sequence    | flags       | payload          |
| 8 bytes    | UInt16 BE   | UInt8       | variant-specific |
+------------+-------------+-------------+------------------+
```

The fixed header is 11 bytes. `flags` is a complete discriminator, not a
bitset that may combine values. Unknown or combined flag values are invalid.

### START (`flags = 0x01`)

```text
[codec: UInt8]
```

Current senders use sequence `0`. The only v1 codec is:

| Codec | Value | Encoded frames |
| --- | --- | --- |
| AAC-LC, 16 kHz, mono | `0x01` | Raw AAC access units without ADTS headers |

Receivers must reject unsupported codec values. Because START can be lost on a
best-effort mesh, a receiver may establish a v1 assembly from a valid data
packet and use the sole v1 codec.

### Data (`flags = 0x00`)

The payload contains one to eight length-prefixed AAC access units:

```text
[length: UInt16 BE][AAC access unit] ...
```

Each length must be non-zero and must not extend past the packet. A sender uses
sequence `1` for the first data packet and increments the value for each later
data packet. The sequence counts data packets, not individual AAC frames.

The v1 sender budget is 210 bytes for the entire `VoiceBurstPacket`. This keeps
one live packet below the transport's fragmentation threshold after the Noise
type byte, authentication tag, and BLE padding are applied. An encoded frame
that cannot fit this budget is dropped rather than fragmented.

### END (`flags = 0x02`)

```text
[totalDataPackets: UInt16 BE][durationMs: UInt32 BE]
```

`totalDataPackets` is the number of data packets emitted for the burst; it does
not include START or END. `durationMs` describes the encoded audio duration.
The END packet uses the next sequence value after the final data packet.
Receivers use the total to account for tail loss before finalizing playback.

### CANCELED (`flags = 0x04`)

CANCELED has no payload and uses the next sequence value. A receiver stops live
playback, discards buffered audio, removes the transient message, and does not
wait for a finalized voice note.

## Sender lifecycle

A successful live gesture follows this order:

1. Generate one eight-byte burst ID.
2. Emit START at sequence `0` after the first encoded AAC access unit becomes
   available.
3. Emit data packets starting at sequence `1`.
4. On release, flush pending data and emit END.
5. Send the finalized M4A through the existing voice-note transfer path.

The finalized file name is `voice_<burst-id-hex>.m4a`, where the hexadecimal
component is the 16 lowercase characters representing the same eight-byte
burst ID. This lets the receiver replace the transient live capture with the
reliable final voice note without adding another wire field.

If capture is canceled or does not produce a valid recording, the sender emits
CANCELED and does not send a final note.

AAC-LC at 16 kHz uses 1,024 samples per access unit, so each encoded frame
represents 64 ms of audio. Live access units are ADTS-less; receivers may add an
ADTS header locally for streaming playback or temporary-file assembly.

## Receiver safety and ordering

Receivers must fail closed on malformed framing and must bound concurrent
assemblies, buffered out-of-order packets, bytes per burst, and inbound byte
rate. The Android/Wear OS v1 bounds are:

- 8 concurrent assemblies;
- 384 KiB per burst;
- 6,000 inbound bytes per second per assembly, with a two-second initial
  allowance; and
- 128 buffered out-of-order packets.

Duplicate and already-delivered sequence values are ignored. Android/Wear OS
wait 550 ms for a sequence gap before skipping it and finalize an idle partial
burst after 3 seconds. These timers are receiver policy rather than additional
wire fields.

## Relay behavior

Public `VOICE_FRAME` packets use the normal mesh relay path only after signature,
sender, timestamp, recipient, and burst validation succeeds. Relays add a small
8–25 ms jitter. In a mesh larger than six peers, the relayed TTL is capped at
five after the normal per-hop decrement. Live packets remain excluded from
gossip sync and file-transfer retransmission.

Private frames retain the existing `NOISE_ENCRYPTED` relay behavior because an
intermediate node cannot inspect the `0x08` inner type.

## Golden vectors

For burst ID `01 02 03 04 05 06 07 08`, the canonical encodings are:

```text
# START, sequence 0, AAC-LC/16 kHz/mono
01 02 03 04 05 06 07 08 00 00 01 01

# One data packet, sequence 1, one three-byte frame DE AD BE
01 02 03 04 05 06 07 08 00 01 00 00 03 DE AD BE

# END, sequence 42, 41 data packets, duration 2688 ms
01 02 03 04 05 06 07 08 00 2A 02 00 29 00 00 0A 80

# CANCELED, sequence 3
01 02 03 04 05 06 07 08 00 03 04
```

The corresponding executable Android vectors live in
`VoiceBurstPacketTest.encodedGoldenVectorsMatchIos`.

## Implementation references

- `app/src/main/java/com/bitchat/android/protocol/BinaryProtocol.kt`
- `app/src/main/java/com/bitchat/android/model/NoiseEncrypted.kt`
- `app/src/main/java/com/bitchat/android/features/voice/VoiceBurstPacket.kt`
- `app/src/main/java/com/bitchat/android/features/voice/LiveVoiceManager.kt`
- `app/src/test/java/com/bitchat/android/features/voice/VoiceBurstPacketTest.kt`
