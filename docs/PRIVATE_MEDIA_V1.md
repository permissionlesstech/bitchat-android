# Private media v1 interoperability and migration

Private media reuses the canonical `BitchatFilePacket` TLV. A capable sender
wraps the complete encoded file TLV as Noise payload type `0x20`, encrypts it
for the recipient, and only then fragments the final outer packet.

## Encrypted wire contract

- Outer packet type: `MessageType.NOISE_ENCRYPTED` (`0x11`).
- Decrypted Noise payload type: `NoisePayloadType.FILE_TRANSFER` (`0x20`).
- Noise payload data: one complete encoded `BitchatFilePacket`.
- Outer recipient: the target peer ID; never broadcast for private media.

Prerelease iOS builds of #1434 briefly emitted the inner file type as `0x09`.
Android accepts that value on decode and immediately canonicalizes it to
`NoisePayloadType.FILE_TRANSFER`; every Android encode remains `0x20`. Do not
allocate or emit a second Noise payload type for this format.

The decode-only `0x09` alias may be removed only after every TestFlight/internal
build that emitted it has expired and the project's minimum-supported-client
policy excludes those builds. Track that release criterion explicitly; do not
remove the alias on an arbitrary calendar date.

## Capability announcement

Identity announcement TLV `0x05` is a minimal little-endian bitfield. Bit 8
means the peer implements private-media v1, so its exact encoding is:

```text
05 02 00 01
|  |  |----- capability bytes: 0x0100 little-endian
|  |-------- value length
|----------- capabilities TLV
```

Current Android builds include this TLV in broadcast and peer-directed
announcements over both BLE and Wi-Fi Aware. Older clients safely skip the
unknown TLV. Its absence, or a present TLV with bit 8 clear, describes a legacy
client and does not invalidate the announcement.

Decoders retain unknown low-64-bit capability bits and unknown announcement
TLVs so a decode/re-encode cycle does not erase newer extensions.

## Authenticated capability pinning

The capability bit is security-sensitive and is not trusted just because an
announcement is self-signed. A client may promote bit 8 only after both of
these checks succeed, in either arrival order:

1. The announcement has a valid Ed25519 signature using its advertised
   signing key.
2. Its advertised Noise static key exactly matches the remote static key
   authenticated by the live Noise handshake.

After promotion, store an HSTS-style pin keyed by the SHA-256 fingerprint of
that authenticated Noise static key. The pin is persistent, encrypted at rest,
and cleared by panic wipe. Never derive it from a peer-ID cache,
`PeerFingerprintManager`, an unverified announcement, or a Noise key that does
not match the signed announcement.

A pin prevents a later missing or cleared capability bit from downgrading the
same authenticated identity. It never substitutes for a live authenticated
Noise session when sending.

## Mixed-client send policy

- No live authenticated Noise remote-static key: emit no media or local echo,
  initiate one Noise handshake, and require the user to retry after it completes.
- Authenticated and pinned, or authenticated with a matching verified bit-8
  announcement: send encrypted `0x11` / `0x20`.
- Authenticated with a matching verified legacy announcement (TLV absent or bit
  clear): ask for explicit one-shot consent before sending this file.

The legacy consent path sends a recipient-directed raw
`MessageType.FILE_TRANSFER` (`0x22`) packet. Its contents are visible to relays,
so the UI must say that it is not end-to-end encrypted. The final routed packet
must carry a valid Ed25519 signature over the canonical packet bytes; signing
failure aborts the send. Receivers reject unsigned or invalid signed directed
raw files.

Consent is consumed at most once. On approval the sender re-runs the policy and
packet admission checks. If the capability became authenticated while the
dialog was open, the send upgrades to encrypted mode. Cancellation, duplicate
approval, panic wipe, and changed security state cannot cause a later send.

## Final-packet admission

Before creating a local echo or progress mapping, the sender builds the exact
encrypted-or-legacy packet, attaches its final source route, signs it, and
creates the exact transport fragment plan. Commit sends that prepared plan
without rebuilding it.

One packet may use at most 256 fragments. This limit is checked after route,
signature, encryption, and envelope overhead are known; therefore there is no
single safe file-byte estimate for every route. Fragment totals and indices
must also fit their unsigned 16-bit wire fields without truncation. A rejected
plan creates no local echo and sends no fragments.

The 256-fragment limit is a transport/reassembly safety bound, not a capability
negotiated through bit 8. A future larger transfer protocol needs a separate
capability and bounded streaming design.

## Compatibility summary

- New Android to new iOS/Android: encrypted Noise `0x20` after authenticated
  capability promotion.
- Prerelease iOS to new Android: encrypted Noise `0x09` is decoded,
  canonicalized to `0x20`, and delivered during the migration window.
- New Android to a verified older client: blocked until the user explicitly
  accepts one relay-visible signed raw `0x22` transfer.
- Old clients receiving a new announcement: ignore TLV `0x05` and continue
  operating normally.
- A previously capable authenticated identity cannot force a silent downgrade
  by later omitting the bit.

Public media remains signed broadcast `MessageType.FILE_TRANSFER` (`0x22`) and
is outside this private-media capability.
