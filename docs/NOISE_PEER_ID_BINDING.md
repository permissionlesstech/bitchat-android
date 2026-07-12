# Noise peer-ID binding

Mesh wire peer IDs are exactly 16 lowercase hexadecimal characters derived as
`hex(SHA-256(noiseStaticPublicKey)[0..<8])`. Android enforces that binding at
both identity entry points:

- A verified announcement must carry a 32-byte Noise static key whose derived
  ID matches both the packet sender and routed sender.
- A Noise XX initiator or responder must authenticate a remote static key whose
  derived ID matches the claimed session key before transport ciphers are
  exposed or an authentication callback runs.

Inbound rehandshakes use a separate responder candidate. An established
session remains active until the candidate completes and passes the binding;
failure or mismatch destroys only the candidate. BLE mappings, Wi-Fi socket
rebinds, gossip, sync, and peer-last-seen effects run only after announcement
validation succeeds. A Wi-Fi discovery identity is not destructively rebound
from a self-signed announce. A direct announce may start the canonical
handshake, but the alias is promoted only when the exact, still-active socket
delivers the Noise frame that completes bound authentication. A peer-ID-only
or stale-socket callback cannot authorize that rebind.

Leave packets use the existing signed wire format and are accepted only when
the signature matches the key learned from a verified announcement. Invalid or
unsigned leaves therefore cannot evict the claimed peer or be relayed. A valid
leave removes the peer through the normal peer-manager path, which also clears
its active Noise session.

Announcements no longer write fingerprint mappings. Those mappings are created
only by the authenticated Noise-session callback. A known peer's signing key
also cannot change based on an announcement or merely because some session for
that peer ID is active. Rotation requires an authenticated peer-state proof
tied to the exact Noise channel; ambient session presence is not enough.
The same authenticated callback restores any existing Noise-key-to-Nostr
relationship under the canonical 16-hex mesh ID; unproven announcements never
write that routing index.

## Remaining TOFU boundary

The first public announcement is still self-signed trust-on-first-use. An
attacker can copy a public Noise key and self-sign an announcement, but cannot
complete the bound Noise handshake for that ID. Public-mesh identity admission
is intentionally not gated behind an automatic handshake in this change; doing
so is a separate availability/protocol decision.

Consequently, discovery metadata or capability bits in an announcement are
hints, not proof of Noise-key possession. Security-sensitive capabilities must
be confirmed inside the authenticated Noise channel before they are pinned or
used to authorize a downgrade-sensitive behavior.
