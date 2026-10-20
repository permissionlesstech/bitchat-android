# Optional pairwise Nostr DMs

This integration is enabled for compatible mutual favorites on Android and Apple.
Peers without the authenticated capability retain the existing relay envelope;
Wear does not advertise or enable NDR. This does not change the legacy relay
envelope or require the separate kind-1402 migration.

## Wire contract

- Capability bit 11 advertises support only while NDR is enabled.
- Invitations and responses travel inside authenticated Noise payload type
  `0x22`. This inner payload type is distinct from the outer public file packet
  type with the same numeric value. The Noise peer's authenticated identity must
  match the intended contact's Nostr binding before accepting the handshake.
- Either peer may initiate bootstrap. The native runtime converges crossed
  invitations onto one session. Each started invite finishes its finite retry
  budget, even if a crossed invite creates a local session first; accepting a
  repeated invite replays the same native response and bootstrap envelope.
- Relays carry only signed kind-1060 pairwise messages. They contain no recipient
  `p` tag. Subscription authors are ephemeral ratchet keys, not contact identity
  keys. A new native subscribe action replaces the filter even when its
  subscription ID is unchanged.
- The pairwise runtime and codec define encrypted payloads and authenticated
  kind-14 rumors. Both clients pin the same source revision. The host checks the
  authenticated sender, rumor shape, and expiration before application delivery.
- The host admits at most 64 KiB per encoded event, 64 tags, 16 values per tag,
  and 1,024 UTF-8 bytes per value. Native bounds also apply.

## Delivery and downgrade protection

The native runtime commits ratchet state and pending actions before the host
performs network or application side effects. Relay acceptance, authenticated
Noise handoff, and durable application consumption acknowledge their respective
actions. A temporary failure or cancelled receive epoch leaves delivery pending;
bounded-backoff retries do not require another message to arrive.

Invites retain a finite retry budget after a successful local Noise handoff:
the peer may still be waiting for its mutual-favorite notification. Retries
reuse the same invite and recheck the authenticated route and favorite binding
before every attempt. A local session alone does not prove the remote peer
received the response, so it does not cancel an already-started retry chain.

An established contact keeps a durable NDR-required binding. Disabling the
rollout gate, losing native state, restarting, or removing a favorite must not
silently enable legacy relay DMs for that binding. Legacy peers without such a
binding continue using the existing format. Expiry-bound sends never downgrade.

Identity changes retire the old native peer before committing the new binding.
The rebind journal blocks both identities during interrupted retirement. Account
epochs prevent callbacks from a previous account from mutating the current one.
Panic recovery retains a durable wipe marker until native state, identities and
contact bindings have all been cleared. These barriers protect separate failure
cases; the application must fail closed when their storage is unreadable.

## Builds and verification

`vendor/nostr-double-ratchet` and `app/src/main/ndr-ffi/SOURCE_REVISION` select the
same pairwise-only FFI source. The handwritten host adapter exposes only the
operations used by BitChat; generated Kotlin remains checked in so unit tests
and lint need no Rust compiler. Native generation verifies that binding before
packaging. Canonical releases export the exact committed submodule tree and
compile it with the existing pinned Arti native builder, never with ignored
host-built libraries. See the FFI provenance file for regeneration commands.

Focused checks cover subscription rotation, cancelled receive/retry, durable
delivery, replay, expiration, rebind, panic recovery and downgrade rejection.
The native pairwise suite also covers replay-window eviction and restart.
Hardware qualification must exercise Android/Apple bootstrap, relay delivery,
restart, fallback to legacy peers, and refusal to downgrade established peers.
Unit tests or a successful build alone do not establish physical interoperability.
