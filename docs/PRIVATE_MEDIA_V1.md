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

## Discovery hint

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
unknown TLV. Its absence, or a present TLV with bit 8 clear, does not invalidate
the announcement.

Decoders retain unknown low-64-bit capability bits and unknown announcement
TLVs so a decode/re-encode cycle does not erase newer extensions.

The announcement bit is discovery metadata only. A self-signed announcement
does not prove possession of its public Noise key, so it never authorizes
encrypted media, creates a capability pin, or satisfies a pending send.

## Authenticated peer state (`0x21`)

Every newly authenticated Noise generation, including a rekey with the same
static key, exchanges a fresh peer-state proof. Its decrypted Noise payload
type is `0x21`, followed by this canonical byte sequence:

```text
01                         version
01 <len 1...8> <value>     capabilities, minimal little-endian
02 20 <32 bytes>           Ed25519 signing public key
```

Both known TLVs are required exactly once. Decoders reject an unknown version,
truncated TLV, duplicate known TLV, a capability length outside `1...8`, a
non-minimal capability value, or an Ed25519 key whose length is not 32. Unknown
TLVs are skipped for forward compatibility, and the two known TLVs may arrive
in either order.

Each endpoint sends `0x21` when the generation authenticates and echoes its
local state at most once after accepting the peer's first valid proof for that
generation. Repeated identical proofs are idempotent; a different second proof
cannot replace the first within that generation. A five-second generation
watchdog distinguishes an older client that ignores `0x21` from a new client
that supplied a proof. Persisted state from a previous connection never
satisfies the fresh-generation watchdog.

Locally, the Noise handshake hash is the generation token. Decryption returns
that token with the plaintext, coordinator mutations hold a lease on that exact
session, and private-media encryption accepts the policy decision only while
the same token remains active. A same-static rekey therefore cannot reuse an
older proof or race policy into encrypting on an unproved generation.

The proof is bound by the Noise channel to the exact authenticated 32-byte
remote static key and canonical peer ID. Store its capabilities and 32-byte
Ed25519 key under the SHA-256 fingerprint of that static key in encrypted
identity state. A proof with bit 8 creates an HSTS-style private-media pin; a
later no-bit proof does not erase that history. Panic wipe clears both records
and prevents an in-flight pre-wipe controller from restoring them.

The persisted Ed25519 key is consulted before accepting later announcements.
This lets a valid proof recover from a copied-static, wrong-Ed preannouncement,
while preventing that preannouncement from winning again after restart. A
fresh proof in a later Noise generation may intentionally rotate the Ed25519
key; an announcement by itself cannot.

## Mixed-client send policy

- No live authenticated Noise remote-static key: retain the first send intent,
  emit no media or local echo, and initiate one Noise handshake.
- Live generation waiting for `0x21`: retain that same intent while the
  five-second peer-state watchdog runs.
- Fresh proof with bit 8: automatically retry the retained intent and send
  encrypted `0x11` / `0x20` after final-packet admission.
- Fresh proof without bit 8, or a five-second no-proof timeout for an unpinned
  old client: retry the retained intent by showing the existing explicit
  one-shot legacy consent prompt.
- A previously pinned identity that proves no bit, or fails to prove state in
  the current generation, is visibly blocked. It cannot silently fall back.

The exact automatic intent is reserved before its first policy evaluation, so a
proof or timeout callback racing that evaluation cannot be lost. It is bounded
and singular, and expires after 15 seconds with a visible system message.
Retries are serialized and always re-run current policy and exact packet
admission. No waiting, rejected, expired, or cancelled attempt creates a local
file echo or transmits raw media; terminal rejection is shown as a system
message rather than failing silently.

The legacy consent path sends a recipient-directed raw
`MessageType.FILE_TRANSFER` (`0x22`) packet. Its contents are visible to relays,
so the UI must say that it is not end-to-end encrypted. The final routed packet
must carry a valid Ed25519 signature over the canonical packet bytes; signing
failure aborts the send. Receivers reject unsigned or invalid signed directed
raw files.

Consent is consumed at most once. On approval the sender re-runs the policy and
packet admission checks. If a bit-8 proof arrived while the dialog was open,
the send upgrades to encrypted mode. Cancellation, duplicate approval, panic
wipe, and changed security state cannot cause a later send.

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

- New Android to new iOS/Android: generation-scoped authenticated `0x21`
  exchange, then encrypted Noise `0x20`.
- Prerelease iOS to new Android: encrypted Noise `0x09` is decoded,
  canonicalized to `0x20`, and delivered during the migration window.
- New Android to an older client: the unknown `0x21` is ignored; after the
  five-second watchdog, an unpinned identity may use one explicitly consented,
  relay-visible signed raw `0x22` transfer.
- Old clients receiving a new announcement: ignore TLV `0x05` and continue
  operating normally. Old clients receiving `0x21` drop the unknown inner type
  without affecting their existing Noise session or private messages.
- A previously capable authenticated identity cannot force a silent downgrade
  by omitting `0x21`, clearing the bit, or changing only its announcement.

Do not remove the `0x21` watchdog/legacy-consent migration path until the
minimum-supported Android and iOS versions both emit an authenticated bit-8
`0x21` proof on every Noise generation, and the released legacy population has
aged out under the project's explicit support policy. Merely observing an
announcement bit or waiting for an arbitrary date is not sufficient. The HSTS
pin remains necessary after that point; removing old-client consent must not
re-enable a raw automatic fallback.

Public media remains signed broadcast `MessageType.FILE_TRANSFER` (`0x22`) and
is outside this private-media capability.
