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
from a self-signed announce. A direct announce creates only a provisional claim
and starts a fresh replacement handshake on that exact transport generation.
The alias is promoted only when the same challenged, still-active socket
delivers the Noise frame that completes bound authentication. A peer-ID-only,
unsolicited, cross-link, or stale-socket callback cannot authorize that rebind;
the expected-socket comparison and alias mutation are atomic with socket
replacement.
Promotion also refuses to displace a different live socket already authenticated
under the canonical peer ID.

Leave packets use the existing signed wire format and are accepted only when
the signature matches the key learned from a verified announcement and the
timestamp is within the five-minute security window. Invalid, stale, future,
or unsigned leaves therefore cannot evict the claimed peer or be relayed. A
valid leave removes the peer through the normal peer-manager path, which also
clears its active Noise session.

Announcements no longer write fingerprint mappings. Those mappings are created
only by the authenticated Noise-session callback. A known peer's signing key
also cannot change based on an announcement or merely because some session for
that peer ID is active. Rotation requires an authenticated peer-state proof
tied to the exact Noise channel; ambient session presence is not enough.
The same authenticated callback restores any existing Noise-key-to-Nostr
relationship under the canonical 16-hex mesh ID; unproven announcements never
write that routing index.

Generation-sensitive consumers use the 32-byte Noise handshake hash as a local
session token. The hash is cloned before handshake-state zeroization, and
session lookup, decrypt, expected-token encrypt, and leased identity mutation
share the Noise manager lock. A same-static replacement therefore cannot reuse
the previous generation's proof or destroy a session while a bound mutation is
in progress.

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

## Verification QR freshness

Verification QR timestamps are signed Unix seconds. The scanner accepts a
validly signed QR only when its timestamp is within 300 seconds of the local
clock in either direction (inclusive), unless the caller supplies a different
age limit. Future dating does not extend this window. An absolute timestamp
difference larger than `Long.MAX_VALUE` saturates to `Long.MAX_VALUE`; signed
subtraction or absolute-value overflow must never produce a small accepted age.

Fixed freshness vectors at synthetic receiver time `1700000000` seconds:

| QR timestamp | Absolute skew | Default freshness result |
|---|---:|---|
| `1699999700` | 300 | accept |
| `1700000300` | 300 | accept |
| `1699999699` | 301 | reject |
| `1700000301` | 301 | reject |
| `-9223372036854775808` | saturated | reject |
| `9223372036854775807` | greater than 300 | reject |

Signature and key validation still apply after this freshness check.
